package worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point do Agregador de Voto Majoritário.
 *
 * Deve ser executado APÓS os workers terminarem de processar a fila.
 *
 * Variáveis de ambiente opcionais:
 *   DYNAMODB_TABLE — padrão: benchmark-results
 *   AWS_REGION     — padrão: us-east-1
 *
 * Build:   mvn package   → gera target/aggregator.jar
 * Execução: java -jar target/aggregator.jar
 */
public class AggregatorMain {

    private static final Logger log = LoggerFactory.getLogger(AggregatorMain.class);

    public static void main(String[] args) {
        String table  = env("DYNAMODB_TABLE", "benchmark-results");
        String region = env("AWS_REGION",     "us-east-1");

        log.info("Agregador iniciando | tabela={} região={}", table, region);

        new VoteAggregator(table, region).run();
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}
