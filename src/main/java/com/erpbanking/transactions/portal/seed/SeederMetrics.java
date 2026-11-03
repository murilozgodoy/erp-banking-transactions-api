package com.erpbanking.transactions.portal.seed;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Execuções do seeder do portal: {@code seeder_runs_total{result}} com {@code result} = ok (massa gerada),
 * skipped (cliente já tinha dados) e error. Contrato com o Grafana; as três séries nascem com zero.
 */
@Component
public class SeederMetrics {

    public static final String SEEDER_RUNS = "seeder.runs";
    public static final String OK = "ok";
    public static final String SKIPPED = "skipped";
    public static final String ERROR = "error";

    private final MeterRegistry registry;

    public SeederMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String result : new String[]{OK, SKIPPED, ERROR}) {
            counter(result);
        }
    }

    public void run(String result) {
        counter(result).increment();
    }

    private Counter counter(String result) {
        return Counter.builder(SEEDER_RUNS)
                .description("Execuções do seeder de dados do portal do cliente")
                .tag("result", result)
                .register(registry);
    }
}
