package producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Producer — lê um arquivo JSON com lista de Tasks e publica na fila SQS.
 *
 * Variáveis de ambiente obrigatórias:
 *   SQS_QUEUE_URL  — URL da fila SQS
 *   TASKS_FILE     — caminho do arquivo JSON com as tasks
 *
 * Variáveis opcionais:
 *   AWS_REGION               — padrão: us-east-1
 *   SELF_CONSISTENCY_RUNS    — quantas cópias disparar por task self-consistency (padrão: 5)
 *
 * Estratégias suportadas:
 *   zero-shot        → 1 mensagem por task
 *   chain-of-thought → 1 mensagem por task
 *   self-consistency → N mensagens por task (run_index 1..N, para voto majoritário)
 *
 * Build:   mvn package   → gera target/producer.jar
 * Execução: java -jar target/producer.jar
 */
public class Producer {

    private static final Logger log = LoggerFactory.getLogger(Producer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String queueUrl  = require("SQS_QUEUE_URL");
        String tasksFile = require("TASKS_FILE");
        String region    = env("AWS_REGION", "us-east-1");
        int scRuns       = Integer.parseInt(env("SELF_CONSISTENCY_RUNS", "5"));

        SqsClient sqs = SqsClient.builder()
            .region(Region.of(region))
            .build();

        // Lê o arquivo de tasks
        List<Task> tasks = Arrays.asList(MAPPER.readValue(new File(tasksFile), Task[].class));
        log.info("=== Producer iniciando ===");
        log.info("Tasks                : {}", tasks.size());
        log.info("Fila SQS             : {}", queueUrl);
        log.info("Self-consistency runs: {}", scRuns);

        int sent = 0, failed = 0;
        long startTime = System.currentTimeMillis();

        for (Task task : tasks) {
            if (task.taskId == null || task.taskId.isBlank()) {
                task.taskId = UUID.randomUUID().toString();
            }

            boolean isSC = "self-consistency".equalsIgnoreCase(task.strategy);
            int copies = isSC ? scRuns : 1;

            for (int run = 1; run <= copies; run++) {
                // Para self-consistency, clona o task com runIndex distinto
                Task t = isSC ? copyWithRun(task, run) : task;
                t.publishedAt = System.currentTimeMillis() / 1000.0;

                try {
                    String body = MAPPER.writeValueAsString(t);
                    sqs.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(body)
                        .build());
                    sent++;
                    log.debug("Enviado question_id={} strategy={} run={}/{}",
                              t.questionId, t.strategy, run, copies);
                } catch (Exception e) {
                    failed++;
                    log.error("Falha ao enviar question_id={} run={}: {}", t.questionId, run, e.getMessage());
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("=== Concluído em {}ms | enviados={} falhas={} ===", elapsed, sent, failed);
        sqs.close();
    }

    private static String require(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank())
            throw new IllegalStateException("Variável obrigatória não definida: " + key);
        return v;
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    /** Cria uma cópia da task com task_id único e run_index definido (para self-consistency). */
    private static Task copyWithRun(Task original, int runIndex) {
        Task t = new Task();
        t.taskId        = UUID.randomUUID().toString();
        t.questionId    = original.questionId;
        t.question      = original.question;
        t.expectedAnswer = original.expectedAnswer;
        t.strategy      = original.strategy;
        t.promptVersion = original.promptVersion;
        t.runIndex      = runIndex;
        return t;
    }
}
