# Relatório Técnico — Tema 8: Simulador de Enxame de Agentes para Benchmarking

## Sumário

1. [Visão Geral](#1-visão-geral)
2. [Arquitetura do Sistema](#2-arquitetura-do-sistema)
3. [Infraestrutura AWS](#3-infraestrutura-aws)
4. [Estrutura do Projeto](#4-estrutura-do-projeto)
5. [Componentes e Implementação](#5-componentes-e-implementação)
6. [Estratégias de Prompting](#6-estratégias-de-prompting)
7. [Fluxo de Execução Completo](#7-fluxo-de-execução-completo)
8. [Engenharia de Contexto](#8-engenharia-de-contexto)
9. [Métricas e Observabilidade](#9-métricas-e-observabilidade)
10. [Tolerância a Falhas](#10-tolerância-a-falhas)
11. [Build e Deploy](#11-build-e-deploy)
12. [Como Executar](#12-como-executar)
13. [Requisitos Atendidos](#13-requisitos-atendidos)

---

## 1. Visão Geral

O projeto implementa um **harness de benchmarking distribuído** que avalia o desempenho de um modelo de linguagem (SLM) em problemas de matemática do benchmark **GSM8K** (Grade School Math 8K). Três estratégias de prompting são aplicadas em paralelo sobre as mesmas questões:

- **Zero-shot**: resposta direta sem exemplos ou raciocínio explícito
- **Chain-of-Thought (CoT)**: raciocínio passo a passo antes de fornecer a resposta
- **Self-Consistency**: múltiplas execuções independentes com voto majoritário final

O sistema é **completamente distribuído**: múltiplas instâncias EC2 consomem tarefas de uma fila SQS, invocam o modelo Llama 3.2:3b via Ollama e persistem os resultados no DynamoDB. Ao final, um agregador computa o voto majoritário para a estratégia self-consistency.

---

## 2. Arquitetura do Sistema

```
┌─────────────────────────────────────────────────────────────┐
│                        Máquina Local                        │
│                                                             │
│  tasks.json ──► Producer.jar ──► SQS (fila-benchmark)      │
└─────────────────────────────────────────────────────────────┘
                              │
                    AWS SQS (Long Polling)
                              │
          ┌───────────────────┴───────────────────┐
          ▼                                       ▼
┌─────────────────┐                   ┌─────────────────┐
│  EC2 Worker #1  │                   │  EC2 Worker #2  │
│  worker.jar     │                   │  worker.jar     │
│  1 thread       │                   │  1 thread       │
└────────┬────────┘                   └────────┬────────┘
         │                                     │
         └──────────────┬──────────────────────┘
                        │  HTTP POST /api/generate
                        ▼
              ┌──────────────────┐
              │  EC2 Ollama      │
              │  llama3.2:3b     │
              │  98.92.11.231    │
              └────────┬─────────┘
                       │ resposta + tokens
                       │
          ┌────────────┴────────────────────────┐
          ▼                                     ▼
 ┌─────────────────┐                 ┌──────────────────────┐
 │  DynamoDB       │                 │  SQS DLQ             │
 │  benchmark-     │                 │  benchmark-dlq-      │
 │  results        │◄── VOTE#* items │  redundancy          │
 └─────────────────┘     (Aggregator)└──────────────────────┘
```

### Comunicação entre serviços

| Origem | Destino | Protocolo | Descrição |
|--------|---------|-----------|-----------|
| Producer | SQS | AWS SDK v2 | Publica mensagens JSON com as tasks |
| Worker | SQS | AWS SDK v2 (long polling) | Consome mensagens (waitTime=20s) |
| Worker | Ollama | HTTP POST | Inferência do modelo (`/api/generate`) |
| Worker | DynamoDB | AWS SDK v2 | Persiste resultados |
| Aggregator | DynamoDB | AWS SDK v2 | Lê e escreve itens de voto |

---

## 3. Infraestrutura AWS

### Instâncias EC2

| Instância | Função | DNS/IP |
|-----------|--------|--------|
| Producer | Publica tasks na fila | ubuntu@34.238.170.123 |
| Worker #1 | Consome fila e invoca Ollama | ec2-44-192-53-95.compute-1.amazonaws.com |
| Worker #2 | Consome fila e invoca Ollama | ec2-3-237-4-25.compute-1.amazonaws.com |
| Ollama | Serve o modelo llama3.2:3b | 98.92.11.231:11434 |

> Todas as instâncias são `t3.medium` na região `us-east-1` (N. Virginia).

### SQS

- **Fila principal**: `fila-benchmark-harness`
  - URL: `https://sqs.us-east-1.amazonaws.com/431722041854/fila-benchmark-harness`
  - Long polling: 20 segundos
  - Visibility timeout: 120 segundos (2 minutos por mensagem)
- **Dead Letter Queue (DLQ)**: `benchmark-dlq-redundancy`
  - Recebe mensagens que excederem o número máximo de reprocessamentos

### DynamoDB

- **Tabela**: `benchmark-results`
- **Chave de partição**: `task_id` (String)
- **Região**: `us-east-1`

### Modelo de Linguagem

- **Modelo**: `llama3.2:3b` (Small Language Model — 3,2 bilhões de parâmetros)
- **Servidor**: Ollama (API REST compatível com OpenAI)
- **Inferência**: CPU-only (~58–89 segundos por requisição)
- **Endpoint**: `http://98.92.11.231:11434/api/generate`

---

## 4. Estrutura do Projeto

```
harness-engineering/
├── pom.xml                          # Build Maven — gera 3 JARs
├── start-workers.ps1                # Script PowerShell de deploy automático
├── docs/
│   └── RELATORIO.md                 # Este documento
├── prompts/
│   ├── tasks.json                   # 15 tarefas GSM8K (5 questões × 3 estratégias)
│   ├── zero-shot.txt                # Template de prompt zero-shot
│   ├── chain-of-thought.txt         # Template de prompt chain-of-thought
│   └── self-consistency.txt         # Template de prompt self-consistency
└── src/main/java/
    ├── llama/
    │   ├── LlamaClient.java         # Cliente HTTP para a API do Ollama
    │   └── GenerateResponse.java    # VO: texto + contadores de tokens
    ├── model/
    │   ├── Task.java                # Entidade de tarefa (desserializada do JSON)
    │   └── Result.java              # Entidade de resultado (salva no DynamoDB)
    ├── producer/
    │   └── Producer.java            # Entry point do producer.jar
    └── worker/
        ├── Worker.java              # Lógica principal: poll → prompt → infer → save
        ├── WorkerMain.java          # Entry point do worker.jar (thread pool)
        ├── WorkerConfig.java        # Leitura de variáveis de ambiente
        ├── VoteAggregator.java      # Computa voto majoritário (self-consistency)
        └── AggregatorMain.java      # Entry point do aggregator.jar
```

### JARs gerados pelo Maven

| JAR | Entry point | Função |
|-----|-------------|--------|
| `target/producer.jar` | `producer.Producer` | Publica tasks na fila SQS |
| `target/worker.jar` | `worker.WorkerMain` | Consome fila e executa inferência |
| `target/aggregator.jar` | `worker.AggregatorMain` | Agrega votos do self-consistency |

---

## 5. Componentes e Implementação

### 5.1 Producer (`Producer.java`)

Responsável por ler o arquivo `tasks.json` e publicar as tarefas na fila SQS.

**Lógica de expansão self-consistency:**
- Para tarefas com `strategy = "self-consistency"`, o Producer gera **N cópias** da mesma task, cada uma com `task_id` único (UUID) e `run_index` distinto (1..N).
- O valor padrão de N é 5, configurável pela variável `SELF_CONSISTENCY_RUNS`.
- Com 15 tarefas no arquivo (5 questões × 3 estratégias) e N=5, o Producer envia **25 mensagens** no total:
  - 5 zero-shot × 1 = 5 mensagens
  - 5 chain-of-thought × 1 = 5 mensagens
  - 5 self-consistency × 5 = 25 mensagens
  - **Total: 35 mensagens**

**Variáveis de ambiente:**

| Variável | Obrigatória | Padrão | Descrição |
|----------|-------------|--------|-----------|
| `SQS_QUEUE_URL` | ✅ | — | URL da fila SQS |
| `TASKS_FILE` | ✅ | — | Caminho do arquivo JSON |
| `AWS_REGION` | ❌ | `us-east-1` | Região AWS |
| `SELF_CONSISTENCY_RUNS` | ❌ | `5` | Cópias por task self-consistency |

---

### 5.2 Worker (`Worker.java` + `WorkerMain.java`)

Consome mensagens da fila SQS, constrói o prompt adequado, chama o Ollama e persiste o resultado no DynamoDB.

**Fluxo por mensagem:**
1. `poll()` — long polling na SQS (até 20s de espera)
2. Deserializa o JSON da mensagem para `Task`
3. `buildPrompt(task)` — carrega template externo e substitui `{{question}}`
4. `execute(task)` — chama Ollama com retry + backoff
5. `parseAnswer(rawText)` — extrai o último número inteiro da resposta
6. `saveResult(result)` — persiste todos os campos no DynamoDB
7. `deleteMessage(msg)` — remove a mensagem da fila (confirma processamento)

**Thread pool:**
- `WorkerMain` instancia um `ExecutorService` com `WORKER_THREADS` threads
- Cada thread executa um loop independente de poll → process
- Padrão configurado para 1 thread por EC2 (Ollama é single-threaded)

**Variáveis de ambiente:**

| Variável | Obrigatória | Padrão | Descrição |
|----------|-------------|--------|-----------|
| `SQS_QUEUE_URL` | ✅ | — | URL da fila SQS |
| `OLLAMA_URL` | ❌ | `http://localhost:11434` | Endereço do Ollama |
| `OLLAMA_MODEL` | ❌ | `llama3.2:3b` | Modelo a usar |
| `DYNAMODB_TABLE` | ❌ | `benchmark-results` | Tabela DynamoDB |
| `AWS_REGION` | ❌ | `us-east-1` | Região AWS |
| `MAX_RETRIES` | ❌ | `3` | Tentativas por task |
| `WORKER_THREADS` | ❌ | `1` | Threads por instância |
| `PROMPTS_DIR` | ❌ | `./prompts` | Diretório dos templates |

---

### 5.3 LlamaClient (`LlamaClient.java`)

Cliente HTTP para a API REST do Ollama.

- Usa `java.net.http.HttpClient` (padrão Java 11+)
- Envia `POST /api/generate` com `{ "model": "...", "prompt": "...", "stream": false }`
- Lê o campo `response` da resposta JSON como texto gerado
- Extrai `prompt_eval_count` (tokens do prompt) e `eval_count` (tokens gerados)
- Retorna um `GenerateResponse` com `text`, `promptTokens`, `completionTokens`
- Timeout configurável por chamada (padrão: 180 segundos)

---

### 5.4 VoteAggregator (`VoteAggregator.java`)

Executa a agregação de votos majoritários para a estratégia self-consistency.

**Algoritmo:**
1. Escaneia o DynamoDB com filtro `strategy = "self-consistency"` (com paginação automática)
2. Agrupa os itens por `question_id`
3. Para cada grupo, conta a frequência de cada `parsed_answer` (exclui nulls)
4. Elege o `parsed_answer` mais frequente como `majority_answer`
5. Compara com `expected_answer` para determinar `correct`
6. Salva o resultado de volta no DynamoDB com `task_id = "VOTE#<question_id>"`

**Campos salvos pelo agregador:**

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `task_id` | String | `VOTE#<question_id>` (PK) |
| `majority_answer` | Number | Resposta mais votada |
| `expected_answer` | Number | Resposta correta do GSM8K |
| `correct` | Boolean | `majority_answer == expected_answer` |
| `majority_votes` | Number | Quantidade de votos da resposta eleita |
| `total_runs` | Number | Total de rodadas processadas |
| `valid_runs` | Number | Rodadas com `parsed_answer` válido (não null) |
| `avg_latency_ms` | Number | Latência média entre as rodadas |
| `total_tokens` | Number | Total de tokens consumidos |
| `tally` | String | Distribuição completa (ex: `{72=4, 48=1}`) |
| `aggregated_at` | Number | Timestamp Unix da agregação |

---

### 5.5 Modelo de Dados

#### Task (mensagem SQS)

```json
{
  "task_id":        "uuid-gerado-pelo-producer",
  "question_id":    "gsm8k_001",
  "question":       "Natalia vendeu clipes para 48 amigos...",
  "expected_answer": 72,
  "strategy":       "self-consistency",
  "prompt_version": "v1",
  "published_at":   1718000000.123,
  "run_index":      3
}
```

#### Result (item DynamoDB)

```json
{
  "task_id":           "uuid",
  "question_id":       "gsm8k_001",
  "worker_id":         "w-abc12345",
  "strategy":          "chain-of-thought",
  "prompt_version":    "v1",
  "question":          "Natalia vendeu clipes...",
  "expected_answer":   72,
  "parsed_answer":     72,
  "correct":           true,
  "fallback":          false,
  "latency_ms":        63420,
  "finished_at":       1718000060,
  "run_index":         0,
  "prompt_tokens":     85,
  "completion_tokens": 134,
  "total_tokens":      219,
  "raw_response":      "Passo 1: Natalia vendeu 48 clipes em abril..."
}
```

---

## 6. Estratégias de Prompting e Engenharia de Contexto

### Como os prompts são enviados tecnicamente

Cada prompt é construído em tempo de execução pelo Worker seguindo o fluxo:

```
1. Worker recebe mensagem SQS com campo "strategy" (ex: "chain-of-thought")
2. buildPrompt(task) lê o template do disco: /home/ec2-user/prompts/chain-of-thought.txt
3. Substitui {{question}} pelo texto real da pergunta
4. LlamaClient monta o JSON: { "model": "llama3.2:3b", "prompt": "<texto>", "stream": false }
5. POST HTTP para http://98.92.11.231:11434/api/generate com timeout de 180s
6. Ollama retorna: { "response": "...", "prompt_eval_count": 85, "eval_count": 134 }
7. Worker extrai o texto e os contadores de tokens
```

O campo `stream: false` é intencional — o Ollama retorna a resposta completa em uma única resposta HTTP, ao invés de enviar tokens progressivamente (streaming). Isso simplifica o parsing e é adequado para o benchmark, onde o que importa é a resposta final, não a experiência interativa.

### Por que prompts em arquivos externos

Os templates ficam em `prompts/` separados do código Java e são copiados para cada EC2 durante o deploy. Isso atende diretamente ao requisito 4.3 da lauda: *"system prompts versionados e documentados em arquivos separados do código de aplicação"*. O campo `prompt_version = "v1"` é gravado em cada resultado no DynamoDB, permitindo rastrear qual versão do prompt gerou cada resposta.

### O placeholder `{{question}}`

O único ponto variável em cada template é `{{question}}`, substituído em tempo de execução. O restante do prompt é fixo e igual para todas as questões da mesma estratégia. Isso garante que a única variável entre execuções da mesma estratégia seja o conteúdo da pergunta — isolando o efeito do benchmark.

---

### Zero-shot (`zero-shot.txt`)

```
Responda APENAS com o número inteiro final, sem nenhuma explicação,
sem unidade, sem texto adicional.

Pergunta: {{question}}
```

**Por que foi escolhido:** o zero-shot é a estratégia mais simples possível — nenhuma instrução de raciocínio, nenhum exemplo. Serve como **linha de base (baseline)** do benchmark. Se o modelo acerta no zero-shot, significa que já tem o conhecimento procedimental internalizado. Se erra, não necessariamente indica incapacidade — pode ser que o modelo precise de orientação para estruturar o raciocínio.

**Decisão de design do prompt:** a instrução *"APENAS o número inteiro final"* foi incluída deliberadamente para forçar uma saída parseável. Sem isso, o modelo tenderia a responder com frases como "A resposta é 72 clipes", dificultando a extração automática pelo `parseAnswer()`. O foco é na extração do último número inteiro da resposta via regex.

**Tokens esperados:** menor consumo de tokens entre as três estratégias, pois o prompt é curto e a resposta esperada é mínima.

---

### Chain-of-Thought (`chain-of-thought.txt`)

```
Resolva o problema passo a passo, mostrando cada etapa do raciocínio de forma clara.
Ao final, escreva APENAS o número inteiro da resposta na última linha, sem texto adicional.

Pergunta: {{question}}
```

**Por que foi escolhido:** o Chain-of-Thought (Wei et al., 2022) é uma técnica de prompting que melhora significativamente a acurácia de modelos em problemas aritméticos e de raciocínio composto. Ao instruir o modelo a "mostrar o trabalho", ele decompõe o problema em subproblemas menores, reduzindo erros de cálculo. Para o GSM8K — que exige múltiplas etapas aritméticas — o CoT é reconhecidamente superior ao zero-shot em modelos menores.

**Decisão de design do prompt:** a instrução final *"na última linha, APENAS o número inteiro"* é crucial. Como o modelo vai gerar várias linhas de raciocínio, o `parseAnswer()` usa a estratégia de pegar o **último número encontrado** na resposta — o que naturalmente coincide com a resposta final em uma resolução passo a passo bem estruturada.

**Tokens esperados:** maior consumo de tokens que o zero-shot, pois a resposta inclui todo o raciocínio intermediário. Isso é um trade-off intencional: mais tokens, potencialmente mais acurácia.

---

### Self-Consistency (`self-consistency.txt`)

```
Resolva o problema passo a passo, mostrando cada etapa do raciocínio de forma clara.
Considere múltiplas abordagens se necessário, mas chegue a uma única conclusão.
Ao final, escreva APENAS o número inteiro da resposta na última linha, sem texto adicional.

Pergunta: {{question}}
```

**Por que foi escolhido:** o Self-Consistency (Wang et al., 2022) é uma extensão do CoT que explora a **variabilidade natural** de um modelo de linguagem. Como os SLMs são estocásticos (a mesma entrada pode gerar saídas ligeiramente diferentes), executar o mesmo prompt N vezes produz múltiplos "caminhos de raciocínio". O voto majoritário sobre as respostas finais é estatisticamente mais robusto do que confiar em uma única execução.

**Por que o mesmo template do CoT com variação mínima:** a diferença para o CoT é a frase *"Considere múltiplas abordagens se necessário"*. Isso incentiva o modelo a variar o caminho de raciocínio entre chamadas, aumentando a diversidade de votos e tornando o voto majoritário mais significativo. Se todos os votos fossem idênticos, o self-consistency se reduziria a CoT com custo 5× maior.

**Como o paralelismo entra aqui:** as 5 rodadas de cada questão são publicadas como **5 mensagens independentes na fila SQS** pelo Producer (com `run_index` de 1 a 5). Cada mensagem é consumida por qualquer worker disponível — podem ser processadas em instâncias EC2 diferentes, de forma completamente paralela. O aggregator só entra depois, consolidando os resultados via DynamoDB.

**Tokens esperados:** o maior consumo total, pois são 5× o custo do CoT por questão. O campo `total_tokens` no item `VOTE#<question_id>` registra a soma acumulada de todas as rodadas.

---

### Comparação entre as estratégias

| Estratégia | Prompt (linhas) | Resposta típica | Tokens/execução | Execuções/questão | Decisão final |
|---|---|---|---|---|---|
| Zero-shot | 3 | 1 número | ~50–90 | 1 | Resposta direta |
| Chain-of-Thought | 4 | Raciocínio + número | ~150–300 | 1 | Último número extraído |
| Self-Consistency | 5 | Raciocínio + número | ~150–300 | 5 (paralelas) | Voto majoritário |

### Por que GSM8K como benchmark

O GSM8K é o benchmark padrão da academia para avaliar raciocínio matemático em linguagem natural. Suas questões exigem entre 2 e 8 etapas aritméticas, são em linguagem coloquial (não fórmulas) e têm respostas inteiras conhecidas — ideal para avaliação automática sem juiz humano. As 5 questões escolhidas cobrem diferentes graus de dificuldade e foram traduzidas para PT-BR para testar também a capacidade multilíngue do llama3.2:3b.

---

## 7. Fluxo de Execução Completo

```
1. PRODUCER (local ou EC2)
   ├── Lê prompts/tasks.json (15 tasks)
   ├── Para self-consistency: expande 5×5 = 25 mensagens (run_index 1..5)
   ├── Para zero-shot e CoT: 5+5 = 10 mensagens (run_index 0)
   └── Publica 35 mensagens na fila SQS

2. WORKERS (2 × EC2)
   ├── Poll SQS (long polling 20s)
   ├── Deserializa Task do JSON
   ├── Carrega template de /home/ec2-user/prompts/<strategy>.txt
   ├── Substitui {{question}} pela pergunta
   ├── Chama Ollama (timeout 180s, até 3 tentativas)
   │   ├── Sucesso: extrai texto + tokens da resposta
   │   └── Falha: backoff 1s/2s/3s → fallback (parsed_answer=-1)
   ├── Extrai último número inteiro da resposta (parseAnswer)
   ├── Compara com expected_answer (campo `correct`)
   ├── Salva Result no DynamoDB
   └── Deleta mensagem da SQS

3. AGGREGATOR (local)
   ├── Escaneia DynamoDB filtrando strategy=self-consistency
   ├── Agrupa por question_id
   ├── Computa voto majoritário (tally)
   ├── Determina majority_answer e correct
   └── Salva VOTE#<question_id> no DynamoDB
```

---

## 8. Engenharia de Contexto

### Prompts externos e versionados

Os templates de prompt são mantidos em arquivos `.txt` separados do código, carregados em tempo de execução. Isso permite:

- Modificar os prompts sem recompilar o projeto
- Versionar os prompts (campo `prompt_version` na task e no resultado)
- Testar diferentes versões de forma controlada

### Cache de templates

Os templates são carregados uma única vez por processo usando `ConcurrentHashMap`, evitando leitura em disco a cada mensagem.

### Particionamento por estratégia

Cada task carrega sua `strategy` como metadado. O Worker seleciona o template correspondente com base nesse campo, garantindo que cada questão seja avaliada de forma independente por cada abordagem de prompting.

### Fallback inline

Se o arquivo de template não for encontrado no `PROMPTS_DIR`, o Worker usa um prompt genérico inline e registra um aviso no log, garantindo que o processamento não seja interrompido por ausência de arquivo.

---

## 9. Métricas e Observabilidade

### Campos no DynamoDB (por resultado)

| Métrica | Campo | Descrição |
|---------|-------|-----------|
| Latência | `latency_ms` | Tempo total da chamada ao Ollama (ms) |
| Tokens do prompt | `prompt_tokens` | `prompt_eval_count` retornado pelo Ollama |
| Tokens gerados | `completion_tokens` | `eval_count` retornado pelo Ollama |
| Total de tokens | `total_tokens` | `prompt_tokens + completion_tokens` |
| Acerto | `correct` | Boolean: `parsed_answer == expected_answer` |
| Fallback | `fallback` | Boolean: todas as tentativas falharam |
| Estratégia | `strategy` | `zero-shot`, `chain-of-thought`, `self-consistency` |
| Versão do prompt | `prompt_version` | `v1` (atual) |
| Worker | `worker_id` | Identificador da thread/instância |
| Timestamp | `finished_at` | Unix timestamp do fim do processamento |

### Cálculo de throughput (a partir do DynamoDB)

O throughput agregado pode ser calculado sobre os dados históricos:

$$\text{throughput} = \frac{\text{total de tasks processadas}}{(\max(\text{finished\_at}) - \min(\text{finished\_at}))}$$

### Logs estruturados

O sistema usa **SLF4J + Logback**. Cada linha de log inclui timestamp, nível, nome da classe e contexto:

```
[w01] task=gsm8k_001-zs | correto=true | latência=63420ms | tokens=219 | resposta=72
[w01] FALLBACK ativado para task=gsm8k_003-sc3 question_id=gsm8k_003 — Ollama indisponível após 3 tentativas
```

### Taxa de erro

Calculável via DynamoDB:
- `fallback = true` → falha total (todas as tentativas esgotadas)
- `parsed_answer = null` → modelo respondeu mas sem número extraível
- `correct = false` → modelo respondeu mas com resposta errada

---

## 10. Tolerância a Falhas

### Retry com backoff exponencial

```java
for (int attempt = 1; attempt <= config.maxRetries; attempt++) {
    try {
        // chama Ollama...
    } catch (Exception e) {
        if (attempt < config.maxRetries) {
            Thread.sleep(1000L * attempt); // 1s, 2s, 3s
        }
    }
}
```

- Até 3 tentativas por padrão (`MAX_RETRIES=3`)
- Pausa crescente entre tentativas: 1s → 2s → 3s

### Estratégia de fallback

Após esgotar todas as tentativas:
- O resultado é salvo no DynamoDB com `fallback=true` e `parsed_answer=-1`
- A mensagem é removida da fila (não vai para DLQ)
- O campo `error` registra a mensagem de exceção para diagnóstico
- O valor sentinela `-1` distingue "falhou" de "sem número na resposta"

### Dead Letter Queue (DLQ)

A fila `benchmark-dlq-redundancy` está provisionada na AWS como ponto de recepção para mensagens que excedam o `maxReceiveCount`. Mensagens que forem recebidas mas não processadas (ex: crash do worker antes do `deleteMessage`) serão automaticamente redirecionadas após o visibility timeout.

### Visibilidade de mensagens

O `visibilityTimeout` de 120 segundos garante que, se um worker travar durante o processamento, a mensagem volta a ficar visível na fila para outro worker consumir.

### Idempotência

Cada mensagem tem um `task_id` único (UUID). O DynamoDB usa `PutItem` sem condição de existência, portanto reprocessamentos sobrescrevem o resultado anterior, evitando duplicações no banco.

---

## 11. Build e Deploy

### Pré-requisitos

- Java 21 (Zulu JDK)
- Maven 3.8+
- AWS CLI configurado com credenciais válidas
- OpenSSH (para SCP/SSH às EC2s)

### Compilação

```powershell
# Definir Java 21
$env:JAVA_HOME = "C:\Program Files\zulu21.50.19-ca-jdk21.0.11-win_x64"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# Compilar e empacotar os 3 JARs
mvn clean package -DskipTests
```

Resultado:
- `target/producer.jar`
- `target/worker.jar`
- `target/aggregator.jar`

### Deploy automático (script `start-workers.ps1`)

O script PowerShell automatiza todo o ciclo de deploy:

1. Define as credenciais AWS e variáveis de ambiente
2. Executa `mvn clean package -DskipTests`
3. Para os workers em execução (`pkill -f worker.jar`)
4. Cria o diretório `~/prompts` nas EC2s
5. Copia `worker.jar` e os 3 arquivos de prompt via SCP
6. Inicia o worker em background com `nohup`

```powershell
.\start-workers.ps1
```

### Verificar logs do worker (SSH)

```bash
ssh -i aws-academy-key.pem ec2-user@ec2-44-192-53-95.compute-1.amazonaws.com
tail -f ~/worker.log
```

---

## 12. Como Executar

### Passo 1 — Atualizar credenciais AWS

No arquivo `start-workers.ps1`, atualizar:
```powershell
$AWS_ACCESS_KEY_ID     = "<nova chave>"
$AWS_SECRET_ACCESS_KEY = "<novo secret>"
$AWS_SESSION_TOKEN     = "<novo token>"
```

> As credenciais do AWS Academy expiram a cada sessão de laboratório.

### Passo 2 — Publicar as tasks

```powershell
$env:JAVA_HOME = "C:\Program Files\zulu21.50.19-ca-jdk21.0.11-win_x64"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:SQS_QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/431722041854/fila-benchmark-harness"
$env:TASKS_FILE = "prompts/tasks.json"
$env:AWS_ACCESS_KEY_ID = "..."
$env:AWS_SECRET_ACCESS_KEY = "..."
$env:AWS_SESSION_TOKEN = "..."

java -jar target/producer.jar
```

### Passo 3 — Subir os workers nas EC2s

```powershell
.\start-workers.ps1
```

### Passo 4 — Aguardar processamento

Monitorar via log:
```bash
tail -f ~/worker.log
```

Ou consultar o DynamoDB:
```bash
aws dynamodb scan --table-name benchmark-results \
  --select COUNT \
  --region us-east-1
```

### Passo 5 — Executar o agregador

```powershell
$env:DYNAMODB_TABLE = "benchmark-results"
$env:AWS_REGION = "us-east-1"
java -jar target/aggregator.jar
```

### Passo 6 — Consultar resultados no DynamoDB

```bash
# Todos os itens de voto majoritário
aws dynamodb scan \
  --table-name benchmark-results \
  --filter-expression "begins_with(task_id, :v)" \
  --expression-attribute-values '{":v":{"S":"VOTE#"}}' \
  --region us-east-1
```

---

## 13. Requisitos Atendidos

### 4.1 Paralelismo Real

| Requisito | Status | Implementação |
|-----------|--------|---------------|
| Múltiplas instâncias paralelas | ✅ | 2 EC2s worker simultâneas |
| Comunicação via mensageria | ✅ | AWS SQS com long polling |
| Sem coordenação central | ✅ | Workers são stateless e independentes |

### 4.2 Integração com LLM

| Requisito | Status | Implementação |
|-----------|--------|---------------|
| Modelo de linguagem auto-hospedado | ✅ | Ollama + llama3.2:3b em EC2 |
| API REST | ✅ | `LlamaClient.java` via `java.net.http` |
| SLM (bônus) | ✅ | llama3.2:3b (3,2B parâmetros) |

### 4.3 Engenharia de Contexto

| Requisito | Status | Implementação |
|-----------|--------|---------------|
| System prompts externos | ✅ | `prompts/*.txt` carregados dinamicamente |
| Versionamento de prompts | ✅ | Campo `prompt_version` em Task e Result |
| Particionamento de tarefas | ✅ | Por `strategy` e `question_id` |
| Placeholder substituível | ✅ | `{{question}}` no template |

### 4.4 Métricas e Observabilidade

| Requisito | Status | Implementação |
|-----------|--------|---------------|
| Latência por requisição | ✅ | Campo `latency_ms` no DynamoDB |
| Throughput agregado | ✅ | Calculável via `finished_at` no DynamoDB |
| Tokens consumidos | ✅ | `prompt_tokens`, `completion_tokens`, `total_tokens` |
| Taxa de erro | ✅ | Campo `fallback` e `correct` no DynamoDB |
| Logs estruturados | ✅ | SLF4J + Logback com contexto por mensagem |

### 4.5 Tolerância a Falhas

| Requisito | Status | Implementação |
|-----------|--------|---------------|
| Retry com backoff | ✅ | Até 3 tentativas, pausa 1s/2s/3s |
| Dead Letter Queue | ✅ | `benchmark-dlq-redundancy` provisionada na AWS |
| Fallback documentado | ✅ | `parsed_answer=-1`, `fallback=true`, log explícito |
| Mensagem não perdida | ✅ | `deleteMessage` só após sucesso ou fallback |

### Tema 8 — Simulador de Enxame

| Requisito | Status | Implementação |
|-----------|--------|---------------|
| N instâncias paralelas | ✅ | 2 workers EC2 + pool de threads por instância |
| 3 estratégias de prompting | ✅ | zero-shot, chain-of-thought, self-consistency |
| Voto majoritário | ✅ | `VoteAggregator` com campo `tally` e `majority_answer` |
| Benchmark GSM8K | ✅ | 5 questões em PT-BR com respostas conhecidas |
| Análise de acurácia | ✅ | Campo `correct` em cada resultado e em cada voto |
