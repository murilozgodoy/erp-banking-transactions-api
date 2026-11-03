package com.erpbanking.transactions.portal.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erpbanking.transactions.domain.CreditSimulation;
import com.erpbanking.transactions.domain.CreditStatus;
import com.erpbanking.transactions.domain.InventoryItem;
import com.erpbanking.transactions.domain.Receivable;
import com.erpbanking.transactions.domain.ReceivableStatus;
import com.erpbanking.transactions.domain.Sale;
import com.erpbanking.transactions.dto.CreditResponseDto;
import com.erpbanking.transactions.portal.PortalExceptions.BusinessRuleException;
import com.erpbanking.transactions.portal.PortalExceptions.NotFoundException;
import com.erpbanking.transactions.portal.PortalExceptions.ValidationException;
import com.erpbanking.transactions.portal.PortalMetrics;
import com.erpbanking.transactions.portal.credit.CreditDtos.Offer;
import com.erpbanking.transactions.portal.credit.CreditDtos.ParcelaView;
import com.erpbanking.transactions.portal.credit.CreditDtos.PortalCreditRequest;
import com.erpbanking.transactions.portal.credit.CreditDtos.SimulationRequest;
import com.erpbanking.transactions.portal.credit.CreditDtos.SimulationResponse;
import com.erpbanking.transactions.portal.credit.CreditPricing.Aprovacao;
import com.erpbanking.transactions.portal.credit.CreditPricing.Quote;
import com.erpbanking.transactions.portal.credit.ScoreAnalysisClient.ScoreAnalysis;
import com.erpbanking.transactions.repository.CreditSimulationRepository;
import com.erpbanking.transactions.repository.InventoryItemRepository;
import com.erpbanking.transactions.repository.ReceivableRepository;
import com.erpbanking.transactions.repository.SaleRepository;
import com.erpbanking.transactions.service.CreditService;
import com.erpbanking.transactions.service.CreditService.PricedDecision;

/** Crédito do portal: ofertas, simulações (persistidas) e pedidos pelo fluxo de crédito existente. */
@Service
public class PortalCreditService {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    static final int VALIDADE_DIAS = 7;
    static final int RECEITA_DIAS = 30;

    private final ScoreAnalysisClient scoreAnalysis;
    private final CreditSimulationRepository simulations;
    private final ReceivableRepository receivables;
    private final InventoryItemRepository inventory;
    private final SaleRepository sales;
    private final CreditService creditService;
    private final PortalMetrics metrics;
    private final Clock clock;

    @Autowired
    public PortalCreditService(ScoreAnalysisClient scoreAnalysis, CreditSimulationRepository simulations,
                               ReceivableRepository receivables, InventoryItemRepository inventory,
                               SaleRepository sales, CreditService creditService, PortalMetrics metrics) {
        this(scoreAnalysis, simulations, receivables, inventory, sales, creditService, metrics, Clock.system(ZONE));
    }

    PortalCreditService(ScoreAnalysisClient scoreAnalysis, CreditSimulationRepository simulations,
                        ReceivableRepository receivables, InventoryItemRepository inventory,
                        SaleRepository sales, CreditService creditService, PortalMetrics metrics, Clock clock) {
        this.scoreAnalysis = scoreAnalysis;
        this.simulations = simulations;
        this.receivables = receivables;
        this.inventory = inventory;
        this.sales = sales;
        this.creditService = creditService;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Limite e taxa inicial por produto; vazio para faixa MUITO_ALTO (crédito negado). */
    public List<Offer> offers(Long customerId) {
        Optional<ScoreAnalysis> analysis = scoreAnalysis.myAnalysis();
        String faixa = analysis.map(ScoreAnalysis::faixaRisco).orElse(null);
        if (CreditPricing.negada(faixa)) {
            return List.of();
        }
        LocalDate today = LocalDate.now(clock);
        BigDecimal recebiveis = sumNet(previstos(customerId));
        BigDecimal estoque = inventoryValue(customerId);
        BigDecimal receita = revenue30d(customerId, today);
        return Arrays.stream(CreditProduct.values()).filter(CreditProduct::ofertado)
                .map(p -> new Offer(p, CreditPricing.limite(p, recebiveis, estoque, receita),
                        CreditPricing.taxaMensal(p, faixa), p.prazoMaxMeses()))
                .toList();
    }

    @Transactional
    public SimulationResponse simulate(Long customerId, SimulationRequest req) {
        return toResponse(createSimulation(customerId, req.produto(), req.valor(), req.prazoMeses(),
                req.receivableIds()));
    }

    /** Pedido a partir de uma simulação ({@code simulation_id}) ou de produto, valor e prazo. */
    @Transactional
    public CreditResponseDto request(Long customerId, PortalCreditRequest req) {
        CreditSimulation sim;
        if (req.simulationId() != null) {
            sim = simulations.findById(req.simulationId())
                    .filter(s -> customerId.equals(s.getCustomerId()))
                    .orElseThrow(() -> new NotFoundException("SIMULACAO_NAO_ENCONTRADA",
                            "Simulação " + req.simulationId() + " não encontrada"));
            if (sim.getCreditRequestId() != null) {
                throw new BusinessRuleException("SIMULACAO_JA_UTILIZADA",
                        "A simulação " + sim.getId() + " já virou o pedido " + sim.getCreditRequestId());
            }
            if (sim.getValidade().isBefore(LocalDate.now(clock))) {
                throw new BusinessRuleException("SIMULACAO_EXPIRADA",
                        "A simulação " + sim.getId() + " venceu em " + sim.getValidade());
            }
        } else {
            if (req.produto() == null || req.valor() == null || req.prazoMeses() == null) {
                throw new ValidationException("Informe simulation_id ou produto, valor e prazo_meses");
            }
            sim = createSimulation(customerId, req.produto(), req.valor(), req.prazoMeses(), null);
        }

        Aprovacao aprovacao = Aprovacao.valueOf(sim.getAprovacao());
        CreditStatus status = switch (aprovacao) {
            case PRE_APROVADO -> CreditStatus.APROVADO;
            case ANALISE -> CreditStatus.REVISAO;
            case NEGADO -> CreditStatus.NEGADO;
        };
        String motivo = switch (aprovacao) {
            case PRE_APROVADO -> "Pré-aprovado pelo ErpIa — " + sim.getProduto() + " a " + sim.getTaxaMensal() + "% a.m.";
            case ANALISE -> "Em análise — " + motivoAnalise(sim);
            case NEGADO -> "Negado — risco " + sim.getFaixaRisco();
        };
        BigDecimal taxaFracao = sim.getTaxaMensal().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        CreditResponseDto resp = creditService.solicitarPrecificado(new PricedDecision(customerId, sim.getValor(),
                sim.getPrazoMeses(), sim.getScore(), sim.getFaixaRisco(), status, motivo, taxaFracao));
        sim.setCreditRequestId(resp.id());
        simulations.save(sim);
        metrics.portalRequest(sim.getProduto());
        return resp;
    }

    public List<CreditResponseDto> requests(Long customerId) {
        return creditService.listar(customerId);
    }

    private CreditSimulation createSimulation(Long customerId, CreditProduct produto, BigDecimal valor,
                                              int prazoMeses, List<Long> receivableIds) {
        if (prazoMeses > produto.prazoMaxMeses()) {
            throw new ValidationException("prazo_meses acima do máximo de " + produto.prazoMaxMeses()
                    + " meses para " + produto);
        }
        LocalDate today = LocalDate.now(clock);
        Optional<ScoreAnalysis> analysis = scoreAnalysis.myAnalysis();
        String faixa = analysis.map(ScoreAnalysis::faixaRisco).orElse(null);
        boolean concluida = analysis.map(ScoreAnalysis::concluida).orElse(false);

        List<Long> selecionados = produto == CreditProduct.ANTECIPACAO_RECEBIVEIS && receivableIds != null
                && !receivableIds.isEmpty() ? List.copyOf(new LinkedHashSet<>(receivableIds)) : null;
        BigDecimal limite = CreditPricing.negada(faixa) ? BigDecimal.ZERO.setScale(2) : limitFor(customerId,
                produto, selecionados, today);
        BigDecimal valorMoney = CreditPricing.money(valor);
        Aprovacao aprovacao = CreditPricing.aprovacao(faixa, concluida, valorMoney, limite);

        CreditSimulation sim = new CreditSimulation();
        sim.setCustomerId(customerId);
        sim.setProduto(produto.name());
        sim.setValor(valorMoney);
        sim.setPrazoMeses(prazoMeses);
        sim.setTaxaMensal(CreditPricing.taxaMensal(produto, faixa));
        sim.setFaixaRisco(faixa);
        sim.setScore(analysis.map(ScoreAnalysis::score).orElse(null));
        sim.setAprovacao(aprovacao.name());
        sim.setLimite(limite);
        sim.setReceivableIds(selecionados == null ? null
                : selecionados.stream().map(String::valueOf).collect(Collectors.joining(",")));
        sim.setValidade(today.plusDays(VALIDADE_DIAS));
        sim.setCriadoEm(clock.instant());
        CreditSimulation saved = simulations.save(sim);
        metrics.simulation(produto.name(), aprovacao.name());
        return saved;
    }

    private SimulationResponse toResponse(CreditSimulation sim) {
        LocalDate base = sim.getValidade().minusDays(VALIDADE_DIAS);
        Quote q = CreditPricing.quote(sim.getValor(), sim.getPrazoMeses(), sim.getTaxaMensal(), base);
        return new SimulationResponse(sim.getId(), CreditProduct.valueOf(sim.getProduto()), sim.getValor(),
                q.valorLiquido(), sim.getTaxaMensal(), q.cetAnual(), q.iof(),
                q.parcelas().stream().map(ParcelaView::of).toList(), q.totalPago(),
                Aprovacao.valueOf(sim.getAprovacao()), sim.getLimite(), sim.getValidade());
    }

    private static String motivoAnalise(CreditSimulation sim) {
        if (sim.getFaixaRisco() == null) {
            return "análise do negócio ainda não disponível";
        }
        if (!CreditPricing.BAIXO.equals(sim.getFaixaRisco()) && !CreditPricing.MEDIO.equals(sim.getFaixaRisco())) {
            return "risco " + sim.getFaixaRisco();
        }
        if (sim.getValor().compareTo(sim.getLimite()) > 0) {
            return "valor acima do limite pré-aprovado de R$ " + sim.getLimite();
        }
        return "análise do negócio em andamento";
    }

    private BigDecimal limitFor(Long customerId, CreditProduct produto, List<Long> selecionados, LocalDate today) {
        return switch (produto) {
            case ANTECIPACAO_RECEBIVEIS -> CreditPricing.limite(produto,
                    sumNet(selecionados == null ? previstos(customerId) : selected(customerId, selecionados)),
                    BigDecimal.ZERO, BigDecimal.ZERO);
            case FINANCIAMENTO_ESTOQUE -> CreditPricing.limite(produto, BigDecimal.ZERO,
                    inventoryValue(customerId), BigDecimal.ZERO);
            case CAPITAL_DE_GIRO, ALAVANCAGEM, CONSOLIDACAO_DIVIDAS -> CreditPricing.limite(produto,
                    BigDecimal.ZERO, BigDecimal.ZERO, revenue30d(customerId, today));
        };
    }

    private List<Receivable> previstos(Long customerId) {
        return receivables.findByCustomerIdOrderByVencimentoAsc(customerId).stream()
                .filter(r -> r.getStatus() == ReceivableStatus.PREVISTO).toList();
    }

    private List<Receivable> selected(Long customerId, List<Long> ids) {
        List<Receivable> found = receivables.findAllById(ids).stream()
                .filter(r -> customerId.equals(r.getCustomerId())).toList();
        if (found.size() != ids.size()) {
            Set<Long> ok = found.stream().map(Receivable::getId).collect(Collectors.toSet());
            Long missing = ids.stream().filter(id -> !ok.contains(id)).findFirst().orElse(null);
            throw new NotFoundException("RECEBIVEL_NAO_ENCONTRADO", "Recebível " + missing + " não encontrado");
        }
        found.stream().filter(r -> r.getStatus() != ReceivableStatus.PREVISTO).findFirst().ifPresent(r -> {
            throw new BusinessRuleException("RECEBIVEL_INDISPONIVEL",
                    "Recebível " + r.getId() + " está " + r.getStatus() + " e não pode ser antecipado");
        });
        return found;
    }

    private static BigDecimal sumNet(List<Receivable> list) {
        return list.stream().map(Receivable::getValorLiquido).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal inventoryValue(Long customerId) {
        return inventory.findByCustomerIdOrderBySkuAsc(customerId).stream()
                .map(PortalCreditService::stockValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal stockValue(InventoryItem i) {
        return i.getCustoUnitario().multiply(BigDecimal.valueOf(i.getQuantidade()));
    }

    /** Receita das vendas nos últimos 30 dias (hoje incluso). */
    private BigDecimal revenue30d(Long customerId, LocalDate today) {
        LocalDate from = today.minusDays(RECEITA_DIAS - 1L);
        return sales.findByCustomerIdOrderByDataAsc(customerId).stream()
                .filter(s -> !s.getData().isBefore(from) && !s.getData().isAfter(today))
                .map(Sale::getValor).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
