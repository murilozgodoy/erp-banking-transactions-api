package com.erpbanking.transactions.portal.payment;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Executa os pagamentos agendados cuja data ({@code agendado_para}) já chegou, no fuso de São Paulo.
 * Cada pagamento roda na sua própria transação; um erro num deles não impede os demais.
 * Desligável com {@code portal.payments.scheduler-enabled=false} (ex.: testes).
 */
@Component
@ConditionalOnProperty(name = "portal.payments.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledPaymentsJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPaymentsJob.class);

    private final PortalPaymentService payments;
    private final Clock clock;

    @Autowired
    public ScheduledPaymentsJob(PortalPaymentService payments) {
        this(payments, Clock.system(PortalPaymentService.ZONE));
    }

    ScheduledPaymentsJob(PortalPaymentService payments, Clock clock) {
        this.payments = payments;
        this.clock = clock;
    }

    /** @return quantos pagamentos foram efetivados nesta rodada */
    @Scheduled(fixedDelayString = "${portal.payments.scheduler-delay-ms:300000}",
            initialDelayString = "${portal.payments.scheduler-initial-delay-ms:60000}")
    public int run() {
        LocalDate today = LocalDate.now(clock);
        List<Long> due = payments.dueScheduledIds(today);
        int efetivados = 0;
        for (Long id : due) {
            try {
                if (payments.executeScheduled(id, today)) {
                    efetivados++;
                }
            } catch (RuntimeException e) {
                log.warn("Falha ao executar o pagamento agendado {}: {}", id, e.toString());
            }
        }
        if (!due.isEmpty()) {
            log.info("Pagamentos agendados: {} vencidos, {} efetivados", due.size(), efetivados);
        }
        return efetivados;
    }
}
