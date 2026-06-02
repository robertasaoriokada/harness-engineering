package model;

public class Result {

    public String taskId;
    public String questionId;
    public String workerId;
    public String strategy;
    public String promptVersion;
    public String question;
    public int expectedAnswer;
    public String rawResponse;
    public Integer parsedAnswer;   // null se não conseguiu parsear
    public boolean correct;
    public long latencyMs;
    public long finishedAt;        // epoch ms
    public String error;           // null se sucesso

    /** Resultado de sucesso */
    public static Result success(Task task, String workerId,
                                  String rawResponse, Integer parsedAnswer,
                                  long latencyMs) {
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
        r.latencyMs     = latencyMs;
        r.finishedAt    = System.currentTimeMillis();
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
        r.finishedAt    = System.currentTimeMillis();
        r.error         = error;
        return r;
    }
}
