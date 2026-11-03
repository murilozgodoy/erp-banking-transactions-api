package com.erpbanking.transactions.portal.seed;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.Receivable;
import com.erpbanking.transactions.domain.Sale;
import com.erpbanking.transactions.domain.Transaction;
import com.erpbanking.transactions.portal.seed.SeedData.ReceivableDraft;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.InventoryItemRepository;
import com.erpbanking.transactions.repository.PayableRepository;
import com.erpbanking.transactions.repository.ReceivableRepository;
import com.erpbanking.transactions.repository.SaleRepository;
import com.erpbanking.transactions.repository.TransactionRepository;
import com.erpbanking.transactions.service.AccountService;

/**
 * Popula o portal do cliente PJ (evento {@code customer.onboarding_submitted}). Tudo numa transação:
 * abre a conta corrente se ainda não existir (PJ não recebe {@code customer.created}), grava vendas,
 * recebíveis, contas a pagar, estoque e o extrato dos últimos 30 dias, e ajusta o saldo da conta.
 * Idempotente: se o cliente já tem vendas, não faz nada.
 */
@Service
public class PortalSeeder {

    public enum Outcome { SEEDED, ALREADY_SEEDED }

    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private static final Logger log = LoggerFactory.getLogger(PortalSeeder.class);

    private final AccountService accountService;
    private final AccountRepository accounts;
    private final SaleRepository sales;
    private final ReceivableRepository receivables;
    private final PayableRepository payables;
    private final InventoryItemRepository inventory;
    private final TransactionRepository transactions;
    private final PortalDataGenerator generator = new PortalDataGenerator();
    private final Clock clock;

    @Autowired
    public PortalSeeder(AccountService accountService, AccountRepository accounts, SaleRepository sales,
                        ReceivableRepository receivables, PayableRepository payables,
                        InventoryItemRepository inventory, TransactionRepository transactions) {
        this(accountService, accounts, sales, receivables, payables, inventory, transactions, Clock.system(ZONE));
    }

    PortalSeeder(AccountService accountService, AccountRepository accounts, SaleRepository sales,
                 ReceivableRepository receivables, PayableRepository payables, InventoryItemRepository inventory,
                 TransactionRepository transactions, Clock clock) {
        this.accountService = accountService;
        this.accounts = accounts;
        this.sales = sales;
        this.receivables = receivables;
        this.payables = payables;
        this.inventory = inventory;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional
    public Outcome seed(SeedProfile profile) {
        long customerId = profile.customerId();
        if (sales.existsByCustomerId(customerId)) {
            return Outcome.ALREADY_SEEDED;
        }
        Account account = accountService.openForNewCustomer(customerId)
                .orElseGet(() -> accounts.findByCustomerIdOrderByIdDesc(customerId).get(0));

        SeedData data = generator.generate(profile, LocalDate.now(clock), clock.getZone());

        List<Sale> saved = sales.saveAll(data.sales());
        List<Receivable> recs = data.receivables().stream().map(d -> link(d, saved)).toList();
        receivables.saveAll(recs);
        payables.saveAll(data.payables());
        inventory.saveAll(data.inventory());
        for (Transaction t : data.transactions()) {
            t.setAccountId(account.getId());
        }
        transactions.saveAll(data.transactions());
        account.setSaldo(account.getSaldo().add(data.saldo()));
        accounts.save(account);

        log.info("Portal do cliente {} populado: {} vendas, {} recebiveis, {} contas a pagar, {} itens de estoque, "
                        + "conta {} com saldo {}", customerId, saved.size(), recs.size(), data.payables().size(),
                data.inventory().size(), account.getId(), account.getSaldo());
        return Outcome.SEEDED;
    }

    private static Receivable link(ReceivableDraft draft, List<Sale> saved) {
        Receivable rec = draft.receivable();
        rec.setSaleId(saved.get(draft.saleIndex()).getId());
        return rec;
    }
}
