package com.erpbanking.transactions.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erpbanking.transactions.domain.Contract;
import com.erpbanking.transactions.domain.CreditRequest;
import com.erpbanking.transactions.domain.CreditStatus;
import com.erpbanking.transactions.dto.CreditRequestDto;
import com.erpbanking.transactions.dto.CreditResponseDto;
import com.erpbanking.transactions.dto.ScoreResponse;
import com.erpbanking.transactions.events.CreditDecidedEvent;
import com.erpbanking.transactions.exception.ApiExceptions.CreditRequestNotFoundException;
import com.erpbanking.transactions.repository.ContractRepository;
import com.erpbanking.transactions.repository.CreditRequestRepository;
import com.erpbanking.transactions.service.client.CreditScoreClient;

@Service
public class CreditService {

    private final CreditRequestRepository creditRequestRepository;
    private final ContractRepository contractRepository;
    private final CreditScoreClient scoreClient;
    private final ApplicationEventPublisher events;

    public CreditService(CreditRequestRepository creditRequestRepository,
                         ContractRepository contractRepository,
                         CreditScoreClient scoreClient,
                         ApplicationEventPublisher events) {
        this.creditRequestRepository = creditRequestRepository;
        this.contractRepository = contractRepository;
        this.scoreClient = scoreClient;
        this.events = events;
    }

    @Transactional
    public CreditResponseDto solicitar(CreditRequestDto dto) {
        CreditRequest cr = new CreditRequest();
        cr.setCustomerId(dto.customerId());
        cr.setValorSolicitado(dto.valor());
        cr.setPrazoMeses(dto.prazoMeses());

        Optional<ScoreResponse> maybeScore = scoreClient.getLatestScore(dto.customerId());
        if (maybeScore.isEmpty()) {
            cr.setStatus(CreditStatus.NEGADO);
            cr.setMotivo("Cliente sem score de crédito registrado");
            creditRequestRepository.save(cr);
            publishDecision(cr, null, BigDecimal.ZERO);
            return CreditResponseDto.of(cr, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        ScoreResponse score = maybeScore.get();
        cr.setScoreSnapshot(score.score());

        DecisionOutcome outcome = decide(score.faixaRisco(), dto.valor());
        cr.setStatus(outcome.status);
        cr.setMotivo(outcome.motivo);
        creditRequestRepository.save(cr);

        BigDecimal valorAprovado = BigDecimal.ZERO;
        BigDecimal taxa = BigDecimal.ZERO;
        if (outcome.status == CreditStatus.APROVADO) {
            valorAprovado = dto.valor();
            taxa = outcome.taxa;
            contractRepository.save(new Contract(cr.getId(), taxa, valorAprovado));
        }
        publishDecision(cr, score.faixaRisco(), taxa);
        return CreditResponseDto.of(cr, valorAprovado, taxa);
    }

    /**
     * Pedido do portal já precificado: o score/faixa vieram do Score {@code GET /me/analysis} e a taxa
     * da simulação, então não consulta o Score de novo. APROVADO gera o contrato na taxa informada.
     */
    @Transactional
    public CreditResponseDto solicitarPrecificado(PricedDecision d) {
        CreditRequest cr = new CreditRequest();
        cr.setCustomerId(d.customerId());
        cr.setValorSolicitado(d.valor());
        cr.setPrazoMeses(d.prazoMeses());
        cr.setCreatedBySub(d.createdBySub());
        cr.setCreatedByNome(d.createdByNome());
        return decidir(cr, d);
    }

    /**
     * Pedido do portal feito por quem só tem {@code pedir_credito}: fica AGUARDANDO_CONTRATACAO, sem decisão,
     * sem contrato e sem {@code credit.decided} (nem {@code credit_decisions_total}) até ser confirmado.
     */
    @Transactional
    public CreditResponseDto registrarAguardando(Long customerId, BigDecimal valor, int prazoMeses,
                                                 String createdBySub, String createdByNome) {
        CreditRequest cr = new CreditRequest();
        cr.setCustomerId(customerId);
        cr.setValorSolicitado(valor);
        cr.setPrazoMeses(prazoMeses);
        cr.setStatus(CreditStatus.AGUARDANDO_CONTRATACAO);
        cr.setMotivo("Aguardando contratação por quem tem a permissão contratar_credito");
        cr.setCreatedBySub(createdBySub);
        cr.setCreatedByNome(createdByNome);
        creditRequestRepository.save(cr);
        return CreditResponseDto.of(cr, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Confirma um pedido AGUARDANDO_CONTRATACAO (já travado pelo chamador): aplica a decisão no mesmo pedido,
     * gera o contrato se APROVADO e publica {@code credit.decided}. A autoria original é mantida.
     */
    @Transactional
    public CreditResponseDto confirmarPrecificado(CreditRequest aguardando, PricedDecision d) {
        if (aguardando.getStatus() != CreditStatus.AGUARDANDO_CONTRATACAO) {
            throw new IllegalStateException("Pedido " + aguardando.getId() + " não está AGUARDANDO_CONTRATACAO");
        }
        aguardando.setValorSolicitado(d.valor());
        aguardando.setPrazoMeses(d.prazoMeses());
        return decidir(aguardando, d);
    }

    private CreditResponseDto decidir(CreditRequest cr, PricedDecision d) {
        cr.setScoreSnapshot(d.score());
        cr.setStatus(d.status());
        cr.setMotivo(d.motivo());
        creditRequestRepository.save(cr);

        BigDecimal valorAprovado = BigDecimal.ZERO;
        BigDecimal taxa = BigDecimal.ZERO;
        if (d.status() == CreditStatus.APROVADO) {
            valorAprovado = d.valor();
            taxa = d.taxaJuros();
            contractRepository.save(new Contract(cr.getId(), taxa, valorAprovado));
        }
        publishDecision(cr, d.faixaRisco(), taxa);
        return CreditResponseDto.of(cr, valorAprovado, taxa);
    }

    /**
     * Decisão já tomada pelo portal; {@code taxaJuros} é a fração mensal (ex.: 0.0249). {@code createdBy*} é a
     * auditoria de quem pediu (opcional).
     */
    public record PricedDecision(Long customerId, BigDecimal valor, int prazoMeses, Double score,
                                 String faixaRisco, CreditStatus status, String motivo, BigDecimal taxaJuros,
                                 String createdBySub, String createdByNome) {

        public PricedDecision(Long customerId, BigDecimal valor, int prazoMeses, Double score, String faixaRisco,
                              CreditStatus status, String motivo, BigDecimal taxaJuros) {
            this(customerId, valor, prazoMeses, score, faixaRisco, status, motivo, taxaJuros, null, null);
        }
    }

    public CreditResponseDto consultar(Long id) {
        CreditRequest cr = creditRequestRepository.findById(id)
                .orElseThrow(() -> new CreditRequestNotFoundException("Crédito " + id + " não encontrado"));
        Optional<Contract> contract = contractRepository.findByCreditRequestId(id);
        BigDecimal valorAprovado = contract.map(Contract::getValorAprovado).orElse(BigDecimal.ZERO);
        BigDecimal taxa = contract.map(Contract::getTaxaJuros).orElse(BigDecimal.ZERO);
        return CreditResponseDto.of(cr, valorAprovado, taxa);
    }

    /** Pedidos de crédito mais recentes primeiro; filtra pelo cliente quando informado. */
    public List<CreditResponseDto> listar(Long customerId) {
        List<CreditRequest> requests = customerId == null
                ? creditRequestRepository.findAll(Sort.by(Sort.Direction.DESC, "id"))
                : creditRequestRepository.findByCustomerIdOrderByIdDesc(customerId);
        Map<Long, Contract> contracts = contractRepository
                .findByCreditRequestIdIn(requests.stream().map(CreditRequest::getId).toList()).stream()
                .collect(Collectors.toMap(Contract::getCreditRequestId, Function.identity()));
        return requests.stream().map(cr -> {
            Contract contract = contracts.get(cr.getId());
            return contract == null
                    ? CreditResponseDto.of(cr, BigDecimal.ZERO, BigDecimal.ZERO)
                    : CreditResponseDto.of(cr, contract.getValorAprovado(), contract.getTaxaJuros());
        }).toList();
    }

    /** Publicado após o commit (ver DomainEventListener). */
    private void publishDecision(CreditRequest cr, String faixaRisco, BigDecimal taxa) {
        events.publishEvent(new CreditDecidedEvent(cr.getId(), cr.getCustomerId(), cr.getValorSolicitado(),
                cr.getPrazoMeses(), cr.getScoreSnapshot(), faixaRisco, cr.getStatus(), cr.getMotivo(), taxa));
    }

    private DecisionOutcome decide(String faixaRisco, BigDecimal valor) {
        return switch (faixaRisco) {
            case "BAIXO" -> new DecisionOutcome(
                    CreditStatus.APROVADO,
                    "Aprovação automática — risco baixo",
                    new BigDecimal("0.0299").setScale(4, RoundingMode.HALF_UP));
            case "MEDIO" -> new DecisionOutcome(
                    CreditStatus.REVISAO,
                    "Requer revisão manual — risco médio",
                    BigDecimal.ZERO);
            default -> new DecisionOutcome(
                    CreditStatus.NEGADO,
                    "Negado — risco " + faixaRisco,
                    BigDecimal.ZERO);
        };
    }

    private record DecisionOutcome(CreditStatus status, String motivo, BigDecimal taxa) {}
}
