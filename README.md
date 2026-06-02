# Swarm Benchmark

Benchmark distribuído com workers em EC2 consumindo tarefas via SQS e chamando Llama (Ollama).

## Arquitetura

```
[producer.jar]  →  SQS  →  [worker.jar EC2 #1]  →  DynamoDB
                        →  [worker.jar EC2 #2]  →  DynamoDB
```

Sem orquestrador central. A SQS garante que cada mensagem é processada por exatamente um worker.

---

## Build

```bash
mvn package
# Gera:
#   target/producer.jar
#   target/worker.jar
```

---

## Producer (máquina local ou EC2 separada)

Publica as tasks na fila SQS a partir de um arquivo JSON.

```bash
export SQS_QUEUE_URL="https://sqs.us-east-1.amazonaws.com/123456789/benchmark-queue"
export TASKS_FILE="prompts/tasks.json"
export AWS_REGION="us-east-1"

java -jar target/producer.jar
```

**Formato do arquivo de tasks** (`prompts/tasks.json`):
```json
[
  {
    "question_id": "q001",
    "question": "Quanto é 2 + 2?",
    "expected_answer": 4,
    "strategy": "direct"
  }
]
```

Estratégias disponíveis: `direct`, `chain-of-thought` (ou `cot`), qualquer outro valor usa o prompt padrão.

---

## Worker (cada EC2)

Consome mensagens da SQS, chama o Ollama e salva resultados no DynamoDB.

```bash
export SQS_QUEUE_URL="https://sqs.us-east-1.amazonaws.com/123456789/benchmark-queue"
export OLLAMA_URL="http://<IP-DA-EC2-COM-LLAMA>:11434"
export OLLAMA_MODEL="llama3.2:3b"
export DYNAMODB_TABLE="benchmark-results"
export AWS_REGION="us-east-1"
export WORKER_THREADS="1"   # threads por instância (normalmente 1, pois Ollama é single-threaded)

java -jar target/worker.jar
```

Para parar: `Ctrl+C` ou `kill` — o shutdown hook encerra as threads limpo.

---

## DynamoDB — schema da tabela

Crie a tabela com:
- **Partition key**: `task_id` (String)

```bash
aws dynamodb create-table \
  --table-name benchmark-results \
  --attribute-definitions AttributeName=task_id,AttributeType=S \
  --key-schema AttributeName=task_id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region us-east-1
```

Campos gravados por resultado:
| Campo | Tipo | Descrição |
|---|---|---|
| `task_id` | String (PK) | ID único da task |
| `question_id` | String | ID da pergunta |
| `worker_id` | String | Qual worker processou |
| `strategy` | String | Estratégia de prompt |
| `question` | String | Texto da pergunta |
| `expected_answer` | Number | Resposta esperada |
| `parsed_answer` | Number | Resposta extraída do LLM |
| `correct` | Boolean | Se acertou |
| `latency_ms` | Number | Tempo de resposta do Ollama |
| `finished_at` | Number | Epoch ms de conclusão |
| `raw_response` | String | Texto bruto do modelo |
| `error` | String | Mensagem de erro (se falhou) |

---

## IAM — permissões necessárias nas EC2

Associe uma IAM Role às instâncias EC2 com:
```json
{
  "Effect": "Allow",
  "Action": [
    "sqs:ReceiveMessage",
    "sqs:DeleteMessage",
    "sqs:GetQueueAttributes",
    "dynamodb:PutItem"
  ],
  "Resource": "*"
}
```

Para o Producer, adicione também `sqs:SendMessage`.
