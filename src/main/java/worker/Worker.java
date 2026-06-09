package worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import llama.GenerateResponse;
import llama.LlamaClient;
import model.Result;
import model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Worker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+");

    /** Cache de templates de prompt carregados do disco (evita I/O repetido). */
    private static final ConcurrentHashMap<String, String> PROMPT_CACHE = new ConcurrentHashMap<>();

    private final String       workerId;
    private final WorkerConfig config;
    private final SqsClient    sqs;
    private final DynamoDbClient dynamo;
    private final LlamaClient  llama;

    public Worker(String workerId, WorkerConfig config) {
        this.workerId = workerId;
        this.config   = config;

        this.sqs = SqsClient.builder()
            .region(Region.of(config.awsRegion))
            .build();

        this.dynamo = DynamoDbClient.builder()
            .region(Region.of(config.awsRegion))
            .build();

        this.llama = new LlamaClient(config.ollamaUrl, config.ollamaModel);
    }

    @Override
    public void run() {
        log.info("[{}] Worker iniciado. Aguardando mensagens em {}", workerId, config.sqsQueueUrl);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<Message> messages = poll();
                if (messages.isEmpty()) {
                    log.debug("[{}] Fila vazia, aguardando...", workerId);
                    continue;
                }

                for (Message msg : messages) {
                    processMessage(msg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[{}] Worker interrompido.", workerId);
                break;
            } catch (Exception e) {
                log.error("[{}] Erro inesperado no loop principal: {}", workerId, e.getMessage(), e);
            }
        }
    }

    // ── Poll SQS ─────────────────────────────────────────────────────────────

    private List<Message> poll() {
        ReceiveMessageRequest req = ReceiveMessageRequest.builder()
            .queueUrl(config.sqsQueueUrl)
            .maxNumberOfMessages(1)
            .waitTimeSeconds(20)          // long polling — reduz custo e latência
            .visibilityTimeout(120)       // 2 min para processar
            .build();

        return sqs.receiveMessage(req).messages();
    }

    // ── Process ───────────────────────────────────────────────────────────────

    private void processMessage(Message msg) throws InterruptedException {
        Task task;
        try {
            task = MAPPER.readValue(msg.body(), Task.class);
        } catch (Exception e) {
            log.error("[{}] Mensagem inválida (não é um Task): {} | body: {}",
                      workerId, e.getMessage(), msg.body());
            deleteMessage(msg); // descarta mensagens malformadas
            return;
        }

        log.info("[{}] Processando task={} question_id={}", workerId, task.taskId, task.questionId);

        Result result = execute(task);
        saveResult(result);
        deleteMessage(msg);

        log.info("[{}] task={} | correto={} | latência={}ms | tokens={} | resposta={}",
                 workerId, task.taskId, result.correct, result.latencyMs,
                 result.promptTokens + result.completionTokens, result.parsedAnswer);
    }

    // ── Execute with retry ────────────────────────────────────────────────────

    private Result execute(Task task) throws InterruptedException {
        String prompt = buildPrompt(task);
        Exception lastError = null;

        for (int attempt = 1; attempt <= config.maxRetries; attempt++) {
            long start = System.currentTimeMillis();
            try {
                GenerateResponse gr = llama.generate(prompt, 180);
                long latency = System.currentTimeMillis() - start;
                Integer parsed = parseAnswer(gr.text);
                return Result.success(task, workerId, gr.text, parsed, latency,
                                     gr.promptTokens, gr.completionTokens);

            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                lastError = e;
                log.warn("[{}] Tentativa {}/{} falhou ({}ms): {}",
                         workerId, attempt, config.maxRetries, latency, e.getMessage());

                if (attempt < config.maxRetries) {
                    Thread.sleep(1000L * attempt); // back-off exponencial simples
                }
            }
        }

        // ── FALLBACK ─────────────────────────────────────────────────────────
        // Todas as tentativas esgotadas. Estratégia de fallback:
        //   1. Registra o erro explicitamente com fallback=true no DynamoDB
        //   2. parsed_answer = -1  (sentinela — distingue "falhou" de "sem número")
        //   3. O item fica disponível para reprocessamento manual ou nova rodada
        log.error("[{}] FALLBACK ativado para task={} question_id={} — Ollama indisponível após {} tentativas. Erro: {}",
                  workerId, task.taskId, task.questionId, config.maxRetries, lastError.getMessage());

        return Result.fallback(task, workerId, lastError.getMessage());
    }

    // ── Prompt builder ────────────────────────────────────────────────────────

    private String buildPrompt(Task task) {
        String strategy = (task.strategy == null) ? "zero-shot" : task.strategy.toLowerCase();
        String fileName = switch (strategy) {
            case "chain-of-thought", "cot" -> "chain-of-thought.txt";
            case "self-consistency"        -> "self-consistency.txt";
            default                        -> "zero-shot.txt";
        };

        String template = PROMPT_CACHE.computeIfAbsent(fileName, f -> loadPromptFile(f));
        return template.replace("{{question}}", task.question);
    }

    /**
     * Carrega um arquivo de prompt do diretório PROMPTS_DIR (env) ou ./prompts/.
     * Em caso de falha, usa um fallback inline para não travar o worker.
     */
    private static String loadPromptFile(String fileName) {
        String dir = System.getenv("PROMPTS_DIR");
        if (dir == null || dir.isBlank()) dir = "prompts";
        Path path = Paths.get(dir, fileName);
        try {
            String template = Files.readString(path);
            log.info("Prompt carregado: {}", path.toAbsolutePath());
            return template;
        } catch (IOException e) {
            log.warn("Falha ao carregar prompt '{}': {} — usando fallback inline.", path, e.getMessage());
            return "Responda a pergunta. Coloque o número inteiro na última linha.\n\nPergunta: {{question}}";
        }
    }

    // ── Answer parser ─────────────────────────────────────────────────────────

    /**
     * Extrai o primeiro número inteiro encontrado na resposta.
     * Pega o último número caso haja vários (comum em CoT).
     */
    private Integer parseAnswer(String raw) {
        Matcher m = NUMBER_PATTERN.matcher(raw);
        Integer last = null;
        while (m.find()) {
            try {
                last = Integer.parseInt(m.group());
            } catch (NumberFormatException ignored) {}
        }
        return last;
    }

    // ── DynamoDB ──────────────────────────────────────────────────────────────

    private void saveResult(Result r) {
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("task_id",         str(r.taskId));
            item.put("question_id",     str(r.questionId));
            item.put("worker_id",       str(r.workerId));
            item.put("strategy",        str(r.strategy));
            item.put("prompt_version",  str(r.promptVersion));
            item.put("question",        str(r.question));
            item.put("expected_answer", num(r.expectedAnswer));
            item.put("parsed_answer",   r.parsedAnswer != null ? num(r.parsedAnswer) : str("null"));
            item.put("correct",         bool(r.correct));
            item.put("fallback",        bool(r.fallback));
            item.put("latency_ms",      num(r.latencyMs));
            item.put("finished_at",     num(r.finishedAt));
            item.put("run_index",          num(r.runIndex));
            item.put("prompt_tokens",       num(r.promptTokens));
            item.put("completion_tokens",   num(r.completionTokens));
            item.put("total_tokens",        num(r.promptTokens + r.completionTokens));
            if (r.rawResponse != null)  item.put("raw_response", str(r.rawResponse));
            if (r.error != null)        item.put("error",        str(r.error));

            dynamo.putItem(PutItemRequest.builder()
                .tableName(config.dynamoTable)
                .item(item)
                .build());

        } catch (Exception e) {
            log.error("[{}] Erro ao salvar no DynamoDB (task={}): {}", workerId, r.taskId, e.getMessage(), e);
        }
    }

    // ── SQS delete ────────────────────────────────────────────────────────────

    private void deleteMessage(Message msg) {
        try {
            sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(config.sqsQueueUrl)
                .receiptHandle(msg.receiptHandle())
                .build());
        } catch (Exception e) {
            log.warn("[{}] Não foi possível deletar mensagem da SQS: {}", workerId, e.getMessage());
        }
    }

    // ── AttributeValue helpers ────────────────────────────────────────────────

    private static AttributeValue str(String v) {
        return AttributeValue.builder().s(v != null ? v : "").build();
    }
    private static AttributeValue num(long v) {
        return AttributeValue.builder().n(String.valueOf(v)).build();
    }
    private static AttributeValue num(int v) {
        return AttributeValue.builder().n(String.valueOf(v)).build();
    }
    private static AttributeValue bool(boolean v) {
        return AttributeValue.builder().bool(v).build();
    }
}
