package worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * VoteAggregator — computa o voto majoritário das rodadas de self-consistency.
 *
 * Para cada question_id com strategy=self-consistency, lê todas as N rodadas
 * do DynamoDB, conta a frequência de cada parsed_answer e elege o mais votado.
 * Em caso de empate, qualquer dos mais frequentes é aceito (comportamento estável).
 *
 * O resultado de cada questão é gravado de volta no DynamoDB na tabela
 * DYNAMODB_TABLE com pk = "VOTE#<question_id>".
 *
 * Variáveis de ambiente:
 *   DYNAMODB_TABLE — padrão: benchmark-results
 *   AWS_REGION     — padrão: us-east-1
 */
public class VoteAggregator {

    private static final Logger log = LoggerFactory.getLogger(VoteAggregator.class);

    private final DynamoDbClient dynamo;
    private final String         table;

    public VoteAggregator(String table, String region) {
        this.table  = table;
        this.dynamo = DynamoDbClient.builder()
            .region(Region.of(region))
            .build();
    }

    /**
     * Executa a agregação completa:
     * 1. Escaneia a tabela buscando itens com strategy=self-consistency
     * 2. Agrupa por question_id
     * 3. Computa voto majoritário
     * 4. Salva resultado de volta no DynamoDB
     */
    public void run() {
        log.info("=== Agregador de voto majoritário iniciando ===");
        log.info("Tabela DynamoDB: {}", table);

        Map<String, List<Map<String, AttributeValue>>> grouped = scanSelfConsistency();

        if (grouped.isEmpty()) {
            log.warn("Nenhum resultado de self-consistency encontrado na tabela '{}'.", table);
            return;
        }

        log.info("Questões encontradas: {}", grouped.size());

        int saved = 0;
        for (Map.Entry<String, List<Map<String, AttributeValue>>> entry : grouped.entrySet()) {
            String questionId = entry.getKey();
            List<Map<String, AttributeValue>> runs = entry.getValue();

            VoteResult vote = computeMajority(questionId, runs);
            logVote(vote);
            saveVote(vote);
            saved++;
        }

        log.info("=== Agregação concluída | questões={} ===", saved);
    }

    // ── Scan DynamoDB ─────────────────────────────────────────────────────────

    private Map<String, List<Map<String, AttributeValue>>> scanSelfConsistency() {
        Map<String, List<Map<String, AttributeValue>>> grouped = new LinkedHashMap<>();

        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":sc", AttributeValue.builder().s("self-consistency").build());

        ScanRequest req = ScanRequest.builder()
            .tableName(table)
            .filterExpression("strategy = :sc")
            .expressionAttributeValues(expressionValues)
            .build();

        String lastKey = null;
        do {
            ScanRequest.Builder builder = req.toBuilder();
            if (lastKey != null) {
                // paginação simples pelo ExclusiveStartKey não se aplica aqui —
                // usamos o token retornado pelo SDK
            }

            ScanResponse resp = dynamo.scan(req);
            for (Map<String, AttributeValue> item : resp.items()) {
                String qid = attrStr(item, "question_id");
                if (qid == null) continue;
                grouped.computeIfAbsent(qid, k -> new ArrayList<>()).add(item);
            }

            if (resp.hasLastEvaluatedKey() && !resp.lastEvaluatedKey().isEmpty()) {
                req = req.toBuilder().exclusiveStartKey(resp.lastEvaluatedKey()).build();
            } else {
                break;
            }
        } while (true);

        return grouped;
    }

    // ── Voto majoritário ──────────────────────────────────────────────────────

    private VoteResult computeMajority(String questionId,
                                        List<Map<String, AttributeValue>> runs) {
        // Conta votos por parsed_answer
        Map<Integer, Long> tally = runs.stream()
            .filter(r -> attrNum(r, "parsed_answer") != null)
            .collect(Collectors.groupingBy(
                r -> attrNum(r, "parsed_answer"),
                Collectors.counting()
            ));

        int totalRuns    = runs.size();
        int validRuns    = (int) tally.values().stream().mapToLong(Long::longValue).sum();
        int expectedAnswer = attrNumOrDefault(runs.get(0), "expected_answer", -1);

        Integer majorityAnswer = null;
        long    majorityCount  = 0;

        for (Map.Entry<Integer, Long> e : tally.entrySet()) {
            if (e.getValue() > majorityCount) {
                majorityCount  = e.getValue();
                majorityAnswer = e.getKey();
            }
        }

        boolean correct = majorityAnswer != null && majorityAnswer == expectedAnswer;

        // Latência média das rodadas
        OptionalDouble avgLatency = runs.stream()
            .mapToLong(r -> attrNumOrDefault(r, "latency_ms", 0))
            .average();

        // Total de tokens acumulados nas N rodadas
        long totalTokens = runs.stream()
            .mapToLong(r -> attrNumOrDefault(r, "total_tokens", 0))
            .sum();

        return new VoteResult(questionId, expectedAnswer, majorityAnswer,
                              majorityCount, totalRuns, validRuns, correct,
                              tally, avgLatency.orElse(0), totalTokens);
    }

    // ── Log ──────────────────────────────────────────────────────────────────

    private void logVote(VoteResult v) {
        log.info("question_id={} | voto={} | esperado={} | correto={} | {}/{} votos válidos | tally={}",
                 v.questionId, v.majorityAnswer, v.expectedAnswer, v.correct,
                 v.validRuns, v.totalRuns, v.tally);
    }

    // ── Salva resultado no DynamoDB ───────────────────────────────────────────

    private void saveVote(VoteResult v) {
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("task_id",          str("VOTE#" + v.questionId));
            item.put("question_id",      str(v.questionId));
            item.put("strategy",         str("self-consistency#aggregated"));
            item.put("majority_answer",  v.majorityAnswer != null ? num(v.majorityAnswer) : str("null"));
            item.put("expected_answer",  num(v.expectedAnswer));
            item.put("correct",          bool(v.correct));
            item.put("majority_votes",   num((int) v.majorityCount));
            item.put("total_runs",       num(v.totalRuns));
            item.put("valid_runs",       num(v.validRuns));
            item.put("avg_latency_ms",   num((long) v.avgLatencyMs));
            item.put("total_tokens",     num((int) v.totalTokens));
            item.put("tally",            str(v.tally.toString()));
            item.put("aggregated_at",    num(System.currentTimeMillis()));

            dynamo.putItem(PutItemRequest.builder()
                .tableName(table)
                .item(item)
                .build());

            log.debug("Voto salvo para question_id={}", v.questionId);
        } catch (Exception e) {
            log.error("Erro ao salvar voto para question_id={}: {}", v.questionId, e.getMessage(), e);
        }
    }

    // ── Helpers DynamoDB ──────────────────────────────────────────────────────

    private static String attrStr(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return (v != null && v.s() != null) ? v.s() : null;
    }

    private static Integer attrNum(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        if (v == null || v.n() == null) return null;
        try { return Integer.parseInt(v.n()); }
        catch (NumberFormatException e) { return null; }
    }

    private static int attrNumOrDefault(Map<String, AttributeValue> item, String key, int def) {
        Integer v = attrNum(item, key);
        return v != null ? v : def;
    }

    private static AttributeValue str(String v)  { return AttributeValue.builder().s(v != null ? v : "").build(); }
    private static AttributeValue num(int v)      { return AttributeValue.builder().n(String.valueOf(v)).build(); }
    private static AttributeValue num(long v)     { return AttributeValue.builder().n(String.valueOf(v)).build(); }
    private static AttributeValue bool(boolean v) { return AttributeValue.builder().bool(v).build(); }

    // ── Value object ──────────────────────────────────────────────────────────

    public static class VoteResult {
        public final String           questionId;
        public final int              expectedAnswer;
        public final Integer          majorityAnswer;
        public final long             majorityCount;
        public final int              totalRuns;
        public final int              validRuns;
        public final boolean          correct;
        public final Map<Integer,Long> tally;
        public final double           avgLatencyMs;
        public final long             totalTokens;

        VoteResult(String questionId, int expectedAnswer, Integer majorityAnswer,
                   long majorityCount, int totalRuns, int validRuns, boolean correct,
                   Map<Integer,Long> tally, double avgLatencyMs, long totalTokens) {
            this.questionId     = questionId;
            this.expectedAnswer = expectedAnswer;
            this.majorityAnswer = majorityAnswer;
            this.majorityCount  = majorityCount;
            this.totalRuns      = totalRuns;
            this.validRuns      = validRuns;
            this.correct        = correct;
            this.tally          = tally;
            this.avgLatencyMs   = avgLatencyMs;
            this.totalTokens    = totalTokens;
        }
    }
}
