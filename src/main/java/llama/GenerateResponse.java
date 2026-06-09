package llama;

/**
 * Resposta do Ollama com texto gerado e contadores de tokens.
 *
 * Campos retornados pelo /api/generate (stream=false):
 *   response          — texto gerado
 *   prompt_eval_count — tokens do prompt (input)
 *   eval_count        — tokens gerados (output)
 */
public class GenerateResponse {

    public final String text;
    public final int    promptTokens;   // prompt_eval_count
    public final int    completionTokens; // eval_count

    public GenerateResponse(String text, int promptTokens, int completionTokens) {
        this.text             = text;
        this.promptTokens     = promptTokens;
        this.completionTokens = completionTokens;
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
