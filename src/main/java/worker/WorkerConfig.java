package worker;

public class WorkerConfig {

    public final String sqsQueueUrl;
    public final String ollamaUrl;
    public final String ollamaModel;
    public final String dynamoTable;
    public final String awsRegion;
    public final int    maxRetries;
    public final int    workerThreads;   // threads por instância EC2

    private WorkerConfig(Builder b) {
        this.sqsQueueUrl   = b.sqsQueueUrl;
        this.ollamaUrl     = b.ollamaUrl;
        this.ollamaModel   = b.ollamaModel;
        this.dynamoTable   = b.dynamoTable;
        this.awsRegion     = b.awsRegion;
        this.maxRetries    = b.maxRetries;
        this.workerThreads = b.workerThreads;
    }

    public static WorkerConfig fromEnv() {
        return new Builder()
            .sqsQueueUrl  (require("SQS_QUEUE_URL"))
            .ollamaUrl    (env("OLLAMA_URL",      "http://localhost:11434"))
            .ollamaModel  (env("OLLAMA_MODEL",    "llama3.2:3b"))
            .dynamoTable  (env("DYNAMODB_TABLE",  "benchmark-results"))
            .awsRegion    (env("AWS_REGION",      "us-east-1"))
            .maxRetries   (Integer.parseInt(env("MAX_RETRIES",     "3")))
            .workerThreads(Integer.parseInt(env("WORKER_THREADS",  "1")))
            .build();
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    private static String require(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank())
            throw new IllegalStateException("Variável obrigatória não definida: " + key);
        return v;
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static class Builder {
        String sqsQueueUrl, ollamaUrl, ollamaModel, dynamoTable, awsRegion;
        int maxRetries = 3, workerThreads = 1;

        public Builder sqsQueueUrl(String v)   { this.sqsQueueUrl   = v; return this; }
        public Builder ollamaUrl(String v)     { this.ollamaUrl     = v; return this; }
        public Builder ollamaModel(String v)   { this.ollamaModel   = v; return this; }
        public Builder dynamoTable(String v)   { this.dynamoTable   = v; return this; }
        public Builder awsRegion(String v)     { this.awsRegion     = v; return this; }
        public Builder maxRetries(int v)       { this.maxRetries    = v; return this; }
        public Builder workerThreads(int v)    { this.workerThreads = v; return this; }
        public WorkerConfig build()            { return new WorkerConfig(this); }
    }
}
