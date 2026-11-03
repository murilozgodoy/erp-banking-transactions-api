package com.erpbanking.transactions.portal.read;

import static com.erpbanking.transactions.portal.read.PortalCalculations.brl;
import static com.erpbanking.transactions.portal.read.PortalCalculations.dataBr;
import static com.erpbanking.transactions.portal.read.PortalCalculations.money;
import static com.erpbanking.transactions.portal.read.PortalCalculations.statusEfetivo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.CreditRequest;
import com.erpbanking.transactions.domain.CreditStatus;
import com.erpbanking.transactions.domain.InventoryItem;
import com.erpbanking.transactions.domain.Payable;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.portal.read.MeReadDtos.Acao;
import com.erpbanking.transactions.portal.read.MeReadDtos.CreditoSugerido;
import com.erpbanking.transactions.portal.read.MeReadDtos.Pendencia;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.CreditRequestRepository;
import com.erpbanking.transactions.repository.InventoryItemRepository;
import com.erpbanking.transactions.repository.PayableRepository;

/**
 * Pendências do cliente (GET /me/pendencias), em ordem de urgência: contas atrasadas, saldo insuficiente para
 * os próximos 7 dias, contas que vencem hoje, propostas de crédito em análise, estoque baixo e estoque parado.
 * Os ids são estáveis entre chamadas (ex.: {@code payable-12}, {@code inventory-low-7}).
 */
@Service
@Transactional(readOnly = true)
public class PendenciasService {

    public static final String CONTA_ATRASADA = "CONTA_ATRASADA";
    public static final String VENCE_HOJE = "VENCE_HOJE";
    public static final String ESTOQUE_BAIXO = "ESTOQUE_BAIXO";
    public static final String ESTOQUE_PARADO = "ESTOQUE_PARADO";
    public static final String SALDO_INSUFICIENTE = "SALDO_INSUFICIENTE";
    public static final String PROPOSTA_CREDITO = "PROPOSTA_CREDITO";

    public static final String CAPITAL_DE_GIRO = "CAPITAL_DE_GIRO";
    public static final String FINANCIAMENTO_ESTOQUE = "FINANCIAMENTO_ESTOQUE";

    static final String ROTA_PAGAMENTOS = "/app/pagamentos";
    static final String ROTA_ESTOQUE = "/app/estoque";
    static final String ROTA_CREDITO = "/app/credito";

    private static final List<String> PRIORIDADE = List.of(CONTA_ATRASADA, SALDO_INSUFICIENTE, VENCE_HOJE,
            PROPOSTA_CREDITO, ESTOQUE_BAIXO, ESTOQUE_PARADO);

    private final AccountRepository accounts;
    private final PayableRepository payables;
    private final InventoryItemRepository inventory;
    private final CreditRequestRepository creditRequests;
    private final Clock clock;

    @Autowired
    public PendenciasService(AccountRepository accounts, PayableRepository payables,
                             InventoryItemRepository inventory, CreditRequestRepository creditRequests) {
        this(accounts, payables, inventory, creditRequests, Clock.system(PortalCalculations.ZONE));
    }

    PendenciasService(AccountRepository accounts, PayableRepository payables, InventoryItemRepository inventory,
                      CreditRequestRepository creditRequests, Clock clock) {
        this.accounts = accounts;
        this.payables = payables;
        this.inventory = inventory;
        this.creditRequests = creditRequests;
        this.clock = clock;
    }

    public List<Pendencia> pendencias(Long customerId) {
        LocalDate hoje = LocalDate.now(clock.withZone(PortalCalculations.ZONE));
        BigDecimal saldo = accounts.findByCustomerIdOrderByIdDesc(customerId).stream().findFirst()
                .map(Account::getSaldo).orElse(BigDecimal.ZERO);
        List<Pendencia> out = new ArrayList<>();

        List<Payable> abertos = payables.findByCustomerIdOrderByVencimentoAsc(customerId).stream()
                .filter(p -> p.getStatus() != PayableStatus.PAGO).toList();
        for (Payable p : abertos) {
            PayableStatus s = statusEfetivo(p, hoje);
            if (s == PayableStatus.ATRASADO) {
                out.add(atrasada(p, hoje, saldo));
            } else if (s == PayableStatus.ABERTO && p.getVencimento().isEqual(hoje)) {
                out.add(venceHoje(p, saldo));
            }
        }

        BigDecimal proximos7 = abertos.stream()
                .filter(p -> !p.getVencimento().isBefore(hoje) && !p.getVencimento().isAfter(hoje.plusDays(7)))
                .map(Payable::getValor).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (proximos7.compareTo(saldo) > 0) {
            BigDecimal faltam = money(proximos7.subtract(saldo));
            out.add(new Pendencia("saldo-insuficiente", SALDO_INSUFICIENTE,
                    "Saldo insuficiente para os próximos 7 dias",
                    "Você tem " + brl(proximos7) + " em contas vencendo nos próximos 7 dias e " + brl(saldo)
                            + " em conta. Faltam " + brl(faltam) + ". Um capital de giro ErpIa cobre a diferença.",
                    faltam, hoje, new Acao("Simular capital de giro", ROTA_CREDITO,
                            new CreditoSugerido(CAPITAL_DE_GIRO, faltam))));
        }

        for (CreditRequest c : creditRequests.findByCustomerIdOrderByIdDesc(customerId)) {
            if (c.getStatus() == CreditStatus.REVISAO) {
                out.add(new Pendencia("credit-request-" + c.getId(), PROPOSTA_CREDITO,
                        "Proposta de crédito em análise",
                        "Sua solicitação de " + brl(c.getValorSolicitado()) + " em " + c.getPrazoMeses()
                                + " meses está em análise pela equipe ErpIa. Avisaremos assim que sair a decisão.",
                        money(c.getValorSolicitado()), c.getCriadoEm().atZone(PortalCalculations.ZONE).toLocalDate(),
                        new Acao("Acompanhar proposta", ROTA_CREDITO, null)));
            }
        }

        for (InventoryItem i : inventory.findByCustomerIdOrderBySkuAsc(customerId)) {
            if (i.getQuantidade() < i.getMinimo()) {
                int faltamUn = i.getMinimo() - i.getQuantidade();
                BigDecimal valor = money(i.getCustoUnitario().multiply(BigDecimal.valueOf(faltamUn)));
                out.add(new Pendencia("inventory-low-" + i.getId(), ESTOQUE_BAIXO,
                        "Estoque baixo: " + i.getNome(),
                        "Restam " + i.getQuantidade() + " unidades (mínimo " + i.getMinimo() + "). Repor "
                                + faltamUn + " unidades custa cerca de " + brl(valor)
                                + "; dá para financiar a compra com o ErpIa.",
                        valor, hoje, new Acao("Ver estoque", ROTA_ESTOQUE,
                                new CreditoSugerido(FINANCIAMENTO_ESTOQUE, valor))));
            }
            if (i.getGiroDias() > PortalReadService.GIRO_PARADO_DIAS) {
                BigDecimal valor = money(PortalReadService.valorEstoque(i));
                out.add(new Pendencia("inventory-idle-" + i.getId(), ESTOQUE_PARADO,
                        "Estoque parado: " + i.getNome(),
                        brl(valor) + " em mercadoria com giro de " + i.getGiroDias()
                                + " dias. Uma promoção libera caixa; se precisar de fôlego, use o estoque como garantia.",
                        valor, i.getUltimaVenda() != null ? i.getUltimaVenda() : hoje,
                        new Acao("Ver estoque", ROTA_ESTOQUE, new CreditoSugerido(FINANCIAMENTO_ESTOQUE, valor))));
            }
        }

        out.sort(Comparator.comparingInt((Pendencia p) -> PRIORIDADE.indexOf(p.tipo()))
                .thenComparing(Pendencia::data).thenComparing(Pendencia::id));
        return out;
    }

    private static Pendencia atrasada(Payable p, LocalDate hoje, BigDecimal saldo) {
        long dias = PortalCalculations.diasEntre(p.getVencimento(), hoje);
        BigDecimal encargos = PortalCalculations.encargosAtraso(p.getValor(), dias);
        BigDecimal total = money(p.getValor().add(encargos));
        String descricao = "Venceu em " + dataBr(p.getVencimento()) + " (há " + dias + (dias == 1 ? " dia" : " dias")
                + "). Multa de 2% + juros de 1% ao mês: cerca de " + brl(encargos) + " de encargos até hoje, total de "
                + brl(total) + ". Pague agora para não acumular mais juros.";
        return new Pendencia("payable-" + p.getId(), CONTA_ATRASADA, "Conta atrasada: " + p.getFornecedor(),
                descricao, money(p.getValor()), p.getVencimento(),
                new Acao("Pagar agora", ROTA_PAGAMENTOS, creditoSeFaltar(total, saldo)));
    }

    private static Pendencia venceHoje(Payable p, BigDecimal saldo) {
        BigDecimal valor = money(p.getValor());
        return new Pendencia("payable-" + p.getId(), VENCE_HOJE, "Vence hoje: " + p.getFornecedor(),
                "Conta de " + brl(valor) + " vence hoje. Pague até o fim do dia para evitar multa de 2% e juros.",
                valor, p.getVencimento(), new Acao("Pagar", ROTA_PAGAMENTOS, creditoSeFaltar(valor, saldo)));
    }

    /** Sugere capital de giro só quando o saldo não cobre o valor (sugestão = o que falta). */
    static CreditoSugerido creditoSeFaltar(BigDecimal valor, BigDecimal saldo) {
        if (valor.compareTo(saldo) <= 0) {
            return null;
        }
        return new CreditoSugerido(CAPITAL_DE_GIRO, money(valor.subtract(saldo.max(BigDecimal.ZERO))));
    }
}
