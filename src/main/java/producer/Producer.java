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
 *   AWS_REGION     — padrão: us-east-1
 *
 * Formato do arquivo JSON:
 *   [ { "question_id": "q1", "question": "Quanto é 2+2?", "expected_answer": 4, "strategy": "direct" }, ... ]
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

        SqsClient sqs = SqsClient.builder()
            .region(Region.of(region))
            .build();

        // Lê o arquivo de tasks
        List<Task> tasks = Arrays.asList(MAPPER.readValue(new File(tasksFile), Task[].class));
        log.info("=== Producer iniciando ===");
        log.info("Tasks     : {}", tasks.size());
        log.info("Fila SQS  : {}", queueUrl);

        int sent = 0, failed = 0;
        long startTime = System.currentTimeMillis();

        for (Task task : tasks) {
            // Garante que cada task tem um task_id único
            if (task.taskId == null || task.taskId.isBlank()) {
                task.taskId = UUID.randomUUID().toString();
            }
            // Marca o momento de publicação
            task.publishedAt = System.currentTimeMillis() / 1000.0;

            try {
                String body = MAPPER.writeValueAsString(task);
                sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());
                sent++;
                log.debug("Enviado task_id={} question_id={}", task.taskId, task.questionId);

            } catch (Exception e) {
                failed++;
                log.error("Falha ao enviar task_id={}: {}", task.taskId, e.getMessage());
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
}
