package com.erpbanking.transactions.service.lock;

import jakarta.persistence.EntityManager;

/**
 * PostgreSQL: {@code pg_advisory_xact_lock} na chave {@code hashtext('conta:' || customer_id)}, liberada pelo próprio
 * banco no fim da transação (commit ou rollback). Vale entre instâncias da API, que é a garantia de produção.
 *
 * <p>Forma escolhida: a de 1 argumento {@code bigint}. {@code hashtext(text)} devolve {@code integer}, e o
 * {@code CAST(... AS bigint)} deixa explícita a assinatura {@code pg_advisory_xact_lock(bigint)}. A forma de 2 ints
 * ({@code pg_advisory_xact_lock(hashtext('conta'), customer_id::int)}) truncaria ids acima de 2^31 e o {@code ::} se
 * confunde com o parâmetro nomeado; por isso não foi usada. Colisão de hash só serializa dois clientes à toa, nunca
 * libera uma corrida.
 *
 * <p>A função devolve {@code void}, que o Hibernate não sabe mapear numa coluna de resultado; por isso ela roda numa
 * subconsulta e o SELECT externo devolve só {@code 1}. Por ser {@code VOLATILE}, a função é sempre avaliada.
 */
public class PostgresAdvisoryCustomerLock implements CustomerAccountLock {

    static final String SQL = "SELECT 1 FROM (SELECT pg_advisory_xact_lock("
            + "CAST(hashtext('" + KEY_PREFIX + "' || CAST(:customerId AS text)) AS bigint))) AS conta_lock";

    private final EntityManager entityManager;

    public PostgresAdvisoryCustomerLock(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void lock(long customerId) {
        entityManager.createNativeQuery(SQL)
                .setParameter("customerId", customerId)
                .getSingleResult();
    }
}
