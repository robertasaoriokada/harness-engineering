package llama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class LlamaClient {

    private static final Logger log = LoggerFactory.getLogger(LlamaClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String     generateUrl;
    private final String     model;

    public LlamaClient(String baseUrl, String model) {
        this.generateUrl = baseUrl.replaceAll("/$", "") + "/api/generate";
        this.model       = model;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Envia um prompt ao Ollama e retorna texto + contadores de tokens.
     *
     * @param prompt texto do prompt
     * @param timeoutSeconds timeout total da requisição
     * @return GenerateResponse com texto, promptTokens e completionTokens
     * @throws IOException em caso de erro HTTP ou parsing
     */
    public GenerateResponse generate(String prompt, int timeoutSeconds) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model",  model);
        body.put("prompt", prompt);
        body.put("stream", false);

        String requestJson = MAPPER.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(generateUrl))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestJson))
            .build();

        log.debug("POST {} | prompt: {}", generateUrl, prompt.substring(0, Math.min(80, prompt.length())));

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Ollama retornou HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = MAPPER.readTree(response.body());
        JsonNode responseNode = json.get("response");
        if (responseNode == null || responseNode.isNull()) {
            throw new IOException("Campo 'response' ausente na resposta do Ollama: " + response.body());
        }

        int promptTokens     = json.path("prompt_eval_count").asInt(0);
        int completionTokens = json.path("eval_count").asInt(0);

        log.debug("Tokens — prompt={} completion={} total={}",
                  promptTokens, completionTokens, promptTokens + completionTokens);

        return new GenerateResponse(responseNode.asText().trim(), promptTokens, completionTokens);
    }
}
