package com.erpbanking.transactions.portal.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
import com.erpbanking.transactions.portal.PortalExceptions.BusinessRuleException;
import com.erpbanking.transactions.portal.PortalMetrics;
import com.erpbanking.transactions.portal.PortalExceptions.NotFoundException;
import com.erpbanking.transactions.portal.PortalExceptions.SaldoInsuficienteException;
import com.erpbanking.transactions.portal.payment.PaymentDtos.Comprovante;
import com.erpbanking.transactions.portal.read.PortalCalculations;
import com.erpbanking.transactions.portal.payment.PaymentDtos.ContaOrigem;
import com.erpbanking.transactions.portal.payment.PaymentDtos.PaymentCreated;
import com.erpbanking.transactions.portal.payment.PaymentDtos.PaymentRequest;
import com.erpbanking.transactions.portal.payment.PaymentDtos.PaymentView;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.PayableRepository;
import com.erpbanking.transactions.repository.PaymentRepository;
import com.erpbanking.transactions.service.AccountService;
import com.erpbanking.transactions.service.TransactionService;

/**
 * Pagamentos (PIX, boleto, TED) do portal. O efetivado debita a conta pelo {@link TransactionService}
 * (mesmo evento {@code transaction.created} e métricas); o agendado só registra e marca a conta a pagar.
 */
@Service
public class PortalPaymentService {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

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
    public PaymentCreated pay(Long customerId, PaymentRequest req) {
        Payable payable = req.payableId() == null ? null : loadPayable(customerId, req.payableId());
        Account account = accountOf(customerId);
        LocalDate today = LocalDate.now(clock);
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

        BigDecimal saldoApos;
        if (agendado) {
            p.setStatus(PaymentStatus.AGENDADO);
            p.setAgendadoPara(req.agendadoPara());
            saldoApos = account.getSaldo();
            if (payable != null) {
                payable.setStatus(PayableStatus.AGENDADO);
            }
        } else {
            if (account.getSaldo().compareTo(req.valor()) < 0) {
                metrics.payment(req.tipo(), PortalMetrics.RECUSADO, req.valor());
                throw new SaldoInsuficienteException(req.valor().subtract(account.getSaldo()));
            }
            TransactionService.TransactionResult result = transactionService.register(
                    new TransactionRequest(account.getId(), TransactionType.DEBIT, req.valor()));
            p.setStatus(PaymentStatus.EFETIVADO);
            p.setTransactionId(result.transaction().getId());
            saldoApos = result.saldoAtual();
            if (payable != null) {
                payable.setStatus(PayableStatus.PAGO);
                payable.setPagoEm(now);
            }
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

    private Payable loadPayable(Long customerId, Long payableId) {
        Payable payable = payableRepository.findById(payableId)
                .filter(pb -> customerId.equals(pb.getCustomerId()))
                .orElseThrow(() -> new NotFoundException("CONTA_A_PAGAR_NAO_ENCONTRADA",
                        "Conta a pagar " + payableId + " não encontrada"));
        if (payable.getStatus() == PayableStatus.PAGO) {
            throw new BusinessRuleException("CONTA_JA_PAGA", "Conta a pagar " + payableId + " já está paga");
        }
        return payable;
    }

    /** Conta corrente mais recente do cliente; abre uma (saldo zero) se ainda não existir. */
    private Account accountOf(Long customerId) {
        List<Account> accounts = accountRepository.findByCustomerIdOrderByIdDesc(customerId);
        if (!accounts.isEmpty()) {
            return accounts.get(0);
        }
        return accountService.openForNewCustomer(customerId)
                .orElseGet(() -> accountRepository.findByCustomerIdOrderByIdDesc(customerId).get(0));
    }
}
