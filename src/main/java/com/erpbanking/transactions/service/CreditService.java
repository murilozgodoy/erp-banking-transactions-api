package com.erpbanking.transactions.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erpbanking.transactions.domain.Contract;
import com.erpbanking.transactions.domain.CreditRequest;
import com.erpbanking.transactions.domain.CreditStatus;
import com.erpbanking.transactions.dto.CreditRequestDto;
import com.erpbanking.transactions.dto.CreditResponseDto;
import com.erpbanking.transactions.dto.ScoreResponse;
import com.erpbanking.transactions.exception.ApiExceptions.CreditRequestNotFoundException;
import com.erpbanking.transactions.repository.ContractRepository;
import com.erpbanking.transactions.repository.CreditRequestRepository;
import com.erpbanking.transactions.service.client.CreditScoreClient;

@Service
public class CreditService {

    private final CreditRequestRepository creditRequestRepository;
    private final ContractRepository contractRepository;
    private final CreditScoreClient scoreClient;

    public CreditService(CreditRequestRepository creditRequestRepository,
                         ContractRepository contractRepository,
                         CreditScoreClient scoreClient) {
        this.creditRequestRepository = creditRequestRepository;
        this.contractRepository = contractRepository;
        this.scoreClient = scoreClient;
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
        return CreditResponseDto.of(cr, valorAprovado, taxa);
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
