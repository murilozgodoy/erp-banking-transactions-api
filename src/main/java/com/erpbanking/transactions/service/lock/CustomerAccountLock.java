package com.erpbanking.transactions.service.lock;

/**
 * Trava por cliente, no escopo da transação atual, para a abertura automática de conta (check-then-insert em
 * {@code AccountService.openForNewCustomer}). Duas entregas paralelas para o mesmo cliente sem conta são
 * serializadas: a segunda só faz a verificação depois que a primeira comitou, e não abre uma segunda conta.
 *
 * <p>O modelo continua permitindo várias contas por cliente (o backoffice pode abrir mais de uma); a trava só vale
 * para a abertura automática. A garantia real em produção vem do PostgreSQL
 * ({@link PostgresAdvisoryCustomerLock}); no H2 (dev/testes) há um fallback dentro da JVM
 * ({@link JvmStripedCustomerLock}).
 *
 * <p>Deve ser chamada dentro de uma transação ativa; a trava é liberada no commit/rollback.
 */
public interface CustomerAccountLock {

    /** Prefixo da chave da trava: {@code 'conta:' || customer_id}. */
    String KEY_PREFIX = "conta:";

    void lock(long customerId);
}
