package worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Entry point do Worker.
 *
 * Roda em cada EC2 worker. Sobe N threads (WORKER_THREADS) que consomem
 * a mesma fila SQS de forma independente.
 *
 * Variáveis de ambiente obrigatórias:
 *   SQS_QUEUE_URL   — URL da fila SQS
 *
 * Variáveis opcionais:
 *   OLLAMA_URL      — padrão: http://localhost:11434
 *   OLLAMA_MODEL    — padrão: llama3.2:3b
 *   DYNAMODB_TABLE  — padrão: benchmark-results
 *   AWS_REGION      — padrão: us-east-1
 *   MAX_RETRIES     — padrão: 3
 *   WORKER_THREADS  — threads por EC2 (padrão: 1)
 *
 * Build:   mvn package   → gera target/worker.jar
 * Execução: java -jar target/worker.jar
 */
public class WorkerMain {

    private static final Logger log = LoggerFactory.getLogger(WorkerMain.class);

    public static void main(String[] args) throws InterruptedException {
        WorkerConfig config = WorkerConfig.fromEnv();

        log.info("=== Worker iniciando ===");
        log.info("SQS        : {}", config.sqsQueueUrl);
        log.info("Ollama     : {} ({})", config.ollamaUrl, config.ollamaModel);
        log.info("DynamoDB   : {}", config.dynamoTable);
        log.info("Região     : {}", config.awsRegion);
        log.info("Threads    : {}", config.workerThreads);

        ExecutorService pool = Executors.newFixedThreadPool(config.workerThreads);
        List<Future<?>> futures = new ArrayList<>();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Encerrando workers...");
            pool.shutdownNow();
        }));

        for (int i = 1; i <= config.workerThreads; i++) {
            String id = String.format("w%02d", i);
            futures.add(pool.submit(new Worker(id, config)));
            log.info("Thread {} iniciada", id);
        }

        for (Future<?> f : futures) {
            try { f.get(); }
            catch (ExecutionException e) { log.error("Worker encerrou com erro: {}", e.getCause().getMessage()); }
            catch (CancellationException e) { log.info("Worker cancelado."); }
        }

        pool.shutdown();
        log.info("=== Todas as threads encerradas ===");
    }
}
