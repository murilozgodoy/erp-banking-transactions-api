package com.erpbanking.transactions.portal.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.Payable;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.domain.Payment;
import com.erpbanking.transactions.domain.PaymentStatus;
import com.erpbanking.transactions.domain.TransactionType;
import com.erpbanking.transactions.dto.TransactionRequest;
import com.erpbanking.transactions.portal.CurrentCustomer;
import com.erpbanking.transactions.portal.PermissionDeniedException;
import com.erpbanking.transactions.portal.Permissoes;
import com.erpbanking.transactions.portal.PortalExceptions.BusinessRuleException;
import com.erpbanking.transactions.portal.PortalExceptions.NotFoundException;
import com.erpbanking.transactions.portal.PortalExceptions.SaldoInsuficienteException;
import com.erpbanking.transactions.portal.PortalExceptions.ValidationException;
import com.erpbanking.transactions.portal.PortalMetrics;
import com.erpbanking.transactions.portal.payment.PaymentDtos.Comprovante;
import com.erpbanking.transactions.portal.payment.PaymentDtos.ContaOrigem;
import com.erpbanking.transactions.portal.payment.PaymentDtos.PaymentCreated;
import com.erpbanking.transactions.portal.payment.PaymentDtos.PaymentRequest;
import com.erpbanking.transactions.portal.payment.PaymentDtos.PaymentView;
import com.erpbanking.transactions.portal.read.PortalCalculations;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.PayableRepository;
import com.erpbanking.transactions.repository.PaymentRepository;
import com.erpbanking.transactions.service.AccountService;
import com.erpbanking.transactions.service.TransactionService;

/**
 * Pagamentos (PIX, boleto, TED) do portal. O efetivado debita a conta pelo {@link TransactionService}
 * (mesmo evento {@code transaction.created} e métricas); o agendado só registra e marca a conta a pagar,
 * e é executado na data pelo {@link ScheduledPaymentsJob}.
 *
 * <p>Concorrência: a conta a pagar e a conta corrente são lidas com {@code SELECT ... FOR UPDATE} antes de
 * qualquer verificação, então dois pagamentos em paralelo da mesma conta/saldo são serializados.
 */
@Service
public class PortalPaymentService {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    /** {@code motivo} do 403 SEM_PERMISSAO quando o autor tenta aprovar ou rejeitar o próprio pagamento. */
    public static final String MOTIVO_AUTOR = "AUTOR";
    /** Agendamento máximo: {@code agendado_para} até 1 ano à frente. */
    static final int MAX_AGENDAMENTO_DIAS = 365;

    private static final Logger log = LoggerFactory.getLogger(PortalPaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PayableRepository payableRepository;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final TransactionService transactionService;
    private final PortalMetrics metrics;
    private final Clock clock;

    @Autowired
    public PortalPaymentService(PaymentRepository paymentRepository, PayableRepository payableRepository,
                                AccountRepository accountRepository, AccountService accountService,
                                TransactionService transactionService, PortalMetrics metrics) {
        this(paymentRepository, payableRepository, accountRepository, accountService, transactionService,
                metrics, Clock.system(ZONE));
    }

    PortalPaymentService(PaymentRepository paymentRepository, PayableRepository payableRepository,
                         AccountRepository accountRepository, AccountService accountService,
                         TransactionService transactionService, PortalMetrics metrics, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.payableRepository = payableRepository;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.transactionService = transactionService;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** A conta aberta aqui permanece mesmo se o pagamento falhar por saldo insuficiente. */
    @Transactional(noRollbackFor = SaldoInsuficienteException.class)
    public PaymentCreated pay(CurrentCustomer who, PaymentRequest req) {
        Long customerId = who.customerId();
        LocalDate today = LocalDate.now(clock);
        if (req.agendadoPara() != null && req.agendadoPara().isBefore(today)) {
            throw new ValidationException("agendado_para não pode ser anterior a hoje (" + today + ")");
        }
        if (req.agendadoPara() != null && req.agendadoPara().isAfter(today.plusDays(MAX_AGENDAMENTO_DIAS))) {
            throw new ValidationException("agendado_para não pode passar de " + MAX_AGENDAMENTO_DIAS + " dias");
        }
        Payable payable = req.payableId() == null ? null : loadPayable(customerId, req.payableId(), req.valor());
        Account account = lockedAccountOf(customerId);
        boolean agendado = req.agendadoPara() != null && req.agendadoPara().isAfter(today);
        Instant now = clock.instant();

        Payment p = new Payment();
        p.setCustomerId(customerId);
        p.setTipo(req.tipo());
        p.setValor(req.valor());
        p.setDestinatario(req.destinatario().trim());
        p.setDescricao(req.descricao());
        p.setPayableId(req.payableId());
        p.setCodigoBarras(req.codigoBarras() != null ? req.codigoBarras()
                : payable != null ? payable.getCodigoBarras() : null);
        p.setAutenticacao(newAuthentication());
        p.setCriadoEm(now);
        p.setCreatedBySub(who.sub());
        p.setCreatedByNome(who.nome());

        BigDecimal saldoApos;
        if (who.membro().excedeLimite(req.valor())) {
            // Acima do limite do membro: fica para aprovação, sem debitar e sem mexer na conta a pagar (que fica
            // bloqueada para outro pagamento enquanto houver um pendente; ver loadPayable).
            p.setStatus(PaymentStatus.PENDENTE_APROVACAO);
            if (agendado) {
                p.setAgendadoPara(req.agendadoPara());
            }
            saldoApos = account.getSaldo();
        } else if (agendado) {
            p.setStatus(PaymentStatus.AGENDADO);
            p.setAgendadoPara(req.agendadoPara());
            saldoApos = account.getSaldo();
            if (payable != null) {
                payable.setStatus(PayableStatus.AGENDADO);
            }
        } else {
            // Saldo lido com a conta travada: o "faltam" é exato e o débito abaixo não falha por saldo.
            if (account.getSaldo().compareTo(req.valor()) < 0) {
                metrics.payment(req.tipo(), PortalMetrics.RECUSADO, req.valor());
                throw new SaldoInsuficienteException(req.valor().subtract(account.getSaldo()));
            }
            saldoApos = debit(account, p, payable, now);
        }
        if (payable != null) {
            payableRepository.save(payable);
        }
        Payment saved = paymentRepository.save(p);
        metrics.payment(saved.getTipo(), saved.getStatus().name(), saved.getValor());

        Comprovante comprovante = new Comprovante(saved.getTipo(), saved.getValor(), saved.getDestinatario(),
                saved.getDescricao(), now, saved.getAgendadoPara(), saved.getAutenticacao(),
                contaOrigem(account), saved.getCodigoBarras());
        return new PaymentCreated(saved.getId(), saved.getStatus(), saved.getAutenticacao(), saldoApos, comprovante);
    }

    /** Pagamentos PENDENTE_APROVACAO da empresa, do mais recente para o mais antigo. */
    public List<PaymentView> pending(Long customerId) {
        return paymentRepository.findByCustomerIdAndStatusOrderByIdDesc(customerId, PaymentStatus.PENDENTE_APROVACAO)
                .stream().map(PaymentView::of).toList();
    }

    /**
     * Aprova um pagamento PENDENTE_APROVACAO e o executa como um pagamento normal: com {@code agendado_para}
     * futuro vira AGENDADO; senão debita na hora (422 SALDO_INSUFICIENTE se não houver saldo, e ele continua
     * pendente). Trava o pagamento, a conta a pagar e a conta corrente, nessa ordem.
     */
    @Transactional(noRollbackFor = SaldoInsuficienteException.class)
    public PaymentCreated approve(CurrentCustomer who, Long paymentId) {
        Payment p = lockedPending(who, paymentId);
        LocalDate today = LocalDate.now(clock);
        Instant now = clock.instant();
        Payable payable = p.getPayableId() == null ? null
                : payableRepository.findByIdForUpdate(p.getPayableId()).orElse(null);
        if (payable != null) {
            checkPayable(payable);
        }
        Account account = lockedAccountOf(p.getCustomerId());
        boolean agendado = p.getAgendadoPara() != null && p.getAgendadoPara().isAfter(today);

        BigDecimal saldoApos;
        if (agendado) {
            p.setStatus(PaymentStatus.AGENDADO);
            saldoApos = account.getSaldo();
            if (payable != null) {
                payable.setStatus(PayableStatus.AGENDADO);
            }
        } else {
            if (account.getSaldo().compareTo(p.getValor()) < 0) {
                metrics.payment(p.getTipo(), PortalMetrics.RECUSADO, p.getValor());
                throw new SaldoInsuficienteException(p.getValor().subtract(account.getSaldo()));
            }
            p.setAgendadoPara(null);
            saldoApos = debit(account, p, payable, now);
        }
        p.setApprovedBySub(who.sub());
        p.setApprovedByNome(who.nome());
        p.setApprovedAt(now);
        if (payable != null) {
            payableRepository.save(payable);
        }
        Payment saved = paymentRepository.save(p);
        metrics.payment(saved.getTipo(), saved.getStatus().name(), saved.getValor());
        log.info("Pagamento {} do cliente {} aprovado por {} ({})", saved.getId(), saved.getCustomerId(), who.sub(),
                saved.getStatus());

        Comprovante comprovante = new Comprovante(saved.getTipo(), saved.getValor(), saved.getDestinatario(),
                saved.getDescricao(), now, saved.getAgendadoPara(), saved.getAutenticacao(),
                contaOrigem(account), saved.getCodigoBarras());
        return new PaymentCreated(saved.getId(), saved.getStatus(), saved.getAutenticacao(), saldoApos, comprovante);
    }

    /** Rejeita um pagamento PENDENTE_APROVACAO (motivo opcional); nada é debitado e a conta a pagar fica livre. */
    @Transactional
    public PaymentView reject(CurrentCustomer who, Long paymentId, String motivo) {
        Payment p = lockedPending(who, paymentId);
        p.setStatus(PaymentStatus.REJEITADO);
        p.setRejectedBySub(who.sub());
        p.setRejectedByNome(who.nome());
        p.setRejectedAt(clock.instant());
        p.setMotivoRejeicao(motivo == null || motivo.isBlank() ? null : motivo.trim());
        Payment saved = paymentRepository.save(p);
        metrics.payment(saved.getTipo(), PortalMetrics.REJEITADO, saved.getValor());
        return PaymentView.of(saved);
    }

    /**
     * Pagamento travado ({@code SELECT ... FOR UPDATE}) da empresa e ainda pendente: 404 se não existe ou é de outra
     * empresa, 422 PAGAMENTO_NAO_PENDENTE, e 403 SEM_PERMISSAO (motivo AUTOR) se quem decide é quem pagou.
     */
    private Payment lockedPending(CurrentCustomer who, Long paymentId) {
        Payment p = paymentRepository.findByIdForUpdate(paymentId)
                .filter(x -> who.customerId().equals(x.getCustomerId()))
                .orElseThrow(() -> new NotFoundException("PAGAMENTO_NAO_ENCONTRADO",
                        "Pagamento " + paymentId + " não encontrado"));
        if (p.getStatus() != PaymentStatus.PENDENTE_APROVACAO) {
            throw new BusinessRuleException("PAGAMENTO_NAO_PENDENTE",
                    "O pagamento " + paymentId + " está " + p.getStatus() + " e não aguarda aprovação");
        }
        if (who.sub() != null && who.sub().equals(p.getCreatedBySub())) {
            throw new PermissionDeniedException(Permissoes.APROVAR_PAGAMENTOS, MOTIVO_AUTOR);
        }
        return p;
    }

    /** Ids dos agendados com data até {@code today} (inclusive), na ordem em que devem ser executados. */
    public List<Long> dueScheduledIds(LocalDate today) {
        return paymentRepository.findIdsDue(PaymentStatus.AGENDADO, today);
    }

    /**
     * Executa um pagamento agendado vencido: trava o pagamento e a conta, debita pelo {@link TransactionService}
     * e marca o pagamento EFETIVADO e a conta a pagar PAGO. Sem saldo, continua AGENDADO (nova tentativa na
     * próxima rodada).
     *
     * @return {@code true} se debitou
     */
    @Transactional
    public boolean executeScheduled(Long paymentId, LocalDate today) {
        Payment p = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (p == null || p.getStatus() != PaymentStatus.AGENDADO || p.getAgendadoPara() == null
                || p.getAgendadoPara().isAfter(today)) {
            return false;
        }
        Optional<Account> account = lockedExistingAccount(p.getCustomerId());
        if (account.isEmpty()) {
            log.warn("Pagamento agendado {} do cliente {} sem conta corrente; continua AGENDADO",
                    p.getId(), p.getCustomerId());
            return false;
        }
        if (account.get().getSaldo().compareTo(p.getValor()) < 0) {
            log.warn("Pagamento agendado {} do cliente {} sem saldo (saldo {}, valor {}); continua AGENDADO",
                    p.getId(), p.getCustomerId(), account.get().getSaldo(), p.getValor());
            return false;
        }
        Payable payable = p.getPayableId() == null ? null
                : payableRepository.findByIdForUpdate(p.getPayableId()).orElse(null);
        debit(account.get(), p, payable, clock.instant());
        if (payable != null) {
            payableRepository.save(payable);
        }
        paymentRepository.save(p);
        metrics.payment(p.getTipo(), PaymentStatus.EFETIVADO.name(), p.getValor());
        log.info("Pagamento agendado {} do cliente {} efetivado ({})", p.getId(), p.getCustomerId(), p.getValor());
        return true;
    }

    /** Histórico, do mais recente para o mais antigo. */
    public List<PaymentView> history(Long customerId) {
        return paymentRepository.findByCustomerIdOrderByIdDesc(customerId).stream().map(PaymentView::of).toList();
    }

    /** Mesma agência e número (com DV) do {@code GET /me/overview}. */
    static ContaOrigem contaOrigem(Account account) {
        return new ContaOrigem(account.getId(), PortalCalculations.AGENCIA,
                PortalCalculations.numeroConta(account.getId()));
    }

    /** Código de autenticação único, ex.: {@code 9F3A-1C2B-77D0-4E8A-B1C2-D3E4-F5A6-B7C8}. */
    static String newAuthentication() {
        String hex = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 4) {
            if (i > 0) {
                sb.append('-');
            }
            sb.append(hex, i, i + 4);
        }
        return sb.toString();
    }

    /** Debita a conta (já travada), efetiva o pagamento e quita a conta a pagar; devolve o saldo após. */
    private BigDecimal debit(Account account, Payment p, Payable payable, Instant now) {
        TransactionService.TransactionResult result = transactionService.register(
                new TransactionRequest(account.getId(), TransactionType.DEBIT, p.getValor()));
        p.setStatus(PaymentStatus.EFETIVADO);
        p.setTransactionId(result.transaction().getId());
        if (payable != null) {
            payable.setStatus(PayableStatus.PAGO);
            payable.setPagoEm(now);
        }
        return result.saldoAtual();
    }

    /** Conta a pagar já paga ou já agendada não aceita outro pagamento (422). */
    private static void checkPayable(Payable payable) {
        if (payable.getStatus() == PayableStatus.PAGO) {
            throw new BusinessRuleException("CONTA_JA_PAGA", "Conta a pagar " + payable.getId() + " já está paga");
        }
        if (payable.getStatus() == PayableStatus.AGENDADO) {
            throw new BusinessRuleException("CONTA_JA_AGENDADA",
                    "Conta a pagar " + payable.getId() + " já tem pagamento agendado");
        }
    }

    private Payable loadPayable(Long customerId, Long payableId, BigDecimal valor) {
        Payable payable = payableRepository.findByIdForUpdate(payableId)
                .filter(pb -> customerId.equals(pb.getCustomerId()))
                .orElseThrow(() -> new NotFoundException("CONTA_A_PAGAR_NAO_ENCONTRADA",
                        "Conta a pagar " + payableId + " não encontrada"));
        checkPayable(payable);
        // Com a conta a pagar travada acima, dois pagamentos dela não passam daqui ao mesmo tempo.
        if (paymentRepository.existsByPayableIdAndStatus(payableId, PaymentStatus.PENDENTE_APROVACAO)) {
            throw new BusinessRuleException("PAGAMENTO_PENDENTE",
                    "Conta a pagar " + payableId + " já tem pagamento aguardando aprovação");
        }
        if (payable.getValor().compareTo(valor) != 0) {
            throw new BusinessRuleException("VALOR_DIVERGENTE", "O valor " + valor
                    + " difere do valor da conta a pagar " + payableId + " (" + payable.getValor() + ")");
        }
        return payable;
    }

    /** Conta corrente mais recente do cliente, travada; abre uma (saldo zero) se ainda não existir. */
    private Account lockedAccountOf(Long customerId) {
        return lockedExistingAccount(customerId).orElseGet(() -> accountService.openForNewCustomer(customerId)
                .orElseGet(() -> lockedExistingAccount(customerId).orElseThrow()));
    }

    /** Busca só o id e depois trava a linha, para que o saldo venha do banco já sob o lock. */
    private Optional<Account> lockedExistingAccount(Long customerId) {
        List<Long> ids = accountRepository.findIdsByCustomerId(customerId);
        return ids.isEmpty() ? Optional.empty() : accountRepository.findByIdForUpdate(ids.get(0));
    }
}
