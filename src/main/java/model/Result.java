package model;

public class Result {

    public String taskId;
    public String questionId;
    public String workerId;
    public String strategy;
    public int    runIndex;
    public String promptVersion;
    public String question;
    public int expectedAnswer;
    public String rawResponse;
    public Integer parsedAnswer;   // null se não conseguiu parsear
    public boolean correct;
    public long latencyMs;
    public long finishedAt;        // epoch ms
    public int  promptTokens;      // tokens do prompt (input)
    public int  completionTokens;  // tokens gerados (output)
    public boolean fallback;       // true = Ollama falhou, resultado via fallback
    public String error;           // null se sucesso

    /** Resultado de sucesso */
    public static Result success(Task task, String workerId,
                                  String rawResponse, Integer parsedAnswer,
                                  long latencyMs, int promptTokens, int completionTokens) {
        Result r = new Result();
        r.taskId        = task.taskId;
        r.questionId    = task.questionId;
        r.workerId      = workerId;
        r.strategy      = task.strategy;
        r.promptVersion = task.promptVersion;
        r.question      = task.question;
        r.expectedAnswer = task.expectedAnswer;
        r.rawResponse   = rawResponse;
        r.parsedAnswer  = parsedAnswer;
        r.correct       = parsedAnswer != null && parsedAnswer == task.expectedAnswer;
        r.latencyMs          = latencyMs;
        r.finishedAt         = System.currentTimeMillis();
        r.runIndex           = task.runIndex;
        r.promptTokens       = promptTokens;
        r.completionTokens   = completionTokens;
        return r;
    }

    /** Resultado de falha */
    public static Result failure(Task task, String workerId, String error, long latencyMs) {
        Result r = new Result();
        r.taskId        = task.taskId;
        r.questionId    = task.questionId;
        r.workerId      = workerId;
        r.strategy      = task.strategy;
        r.promptVersion = task.promptVersion;
        r.question      = task.question;
        r.expectedAnswer = task.expectedAnswer;
        r.correct       = false;
        r.latencyMs     = latencyMs;
        r.runIndex      = task.runIndex;
        r.finishedAt    = System.currentTimeMillis();
        r.error         = error;
        return r;
    }

    /**
     * Resultado de fallback: todas as tentativas esgotadas.
     * parsed_answer = -1 como sentinela (distingue de "sem número na resposta").
     */
    public static Result fallback(Task task, String workerId, String error) {
        Result r = new Result();
        r.taskId         = task.taskId;
        r.questionId     = task.questionId;
        r.workerId       = workerId;
        r.strategy       = task.strategy;
        r.promptVersion  = task.promptVersion;
        r.question       = task.question;
        r.expectedAnswer = task.expectedAnswer;
        r.parsedAnswer   = -1;
        r.correct        = false;
        r.fallback       = true;
        r.latencyMs      = 0;
        r.runIndex       = task.runIndex;
        r.finishedAt     = System.currentTimeMillis();
        r.error          = error;
        return r;
    }
}
