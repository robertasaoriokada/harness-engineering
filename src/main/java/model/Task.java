package model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Task {

    @JsonProperty("task_id")
    public String taskId;

    @JsonProperty("question_id")
    public String questionId;

    @JsonProperty("question")
    public String question;

    @JsonProperty("expected_answer")
    public int expectedAnswer;

    @JsonProperty("strategy")
    public String strategy;

    @JsonProperty("prompt_version")
    public String promptVersion = "v1";

    @JsonProperty("published_at")
    public double publishedAt;

    /** Índice da rodada (1..N) para self-consistency; 0 nas demais estratégias. */
    @JsonProperty("run_index")
    public int runIndex = 0;
}
