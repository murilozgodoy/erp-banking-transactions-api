package com.erpbanking.transactions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;

import com.erpbanking.transactions.domain.Contract;
import com.erpbanking.transactions.domain.CreditRequest;
import com.erpbanking.transactions.domain.CreditStatus;
import com.erpbanking.transactions.dto.CreditRequestDto;
import com.erpbanking.transactions.dto.ScoreResponse;
import com.erpbanking.transactions.events.CreditDecidedEvent;
import com.erpbanking.transactions.exception.ApiExceptions.CreditRequestNotFoundException;
import com.erpbanking.transactions.repository.ContractRepository;
import com.erpbanking.transactions.repository.CreditRequestRepository;
import com.erpbanking.transactions.service.client.CreditScoreClient;

@ExtendWith(MockitoExtension.class)
class CreditServiceTest {

    @Mock CreditRequestRepository crRepo;
    @Mock ContractRepository contractRepo;
    @Mock CreditScoreClient scoreClient;
    @Mock ApplicationEventPublisher events;
    @InjectMocks CreditService service;

    @Test
    void deniedWhenNoScore() {
        when(scoreClient.getLatestScore(1L)).thenReturn(Optional.empty());
        when(crRepo.save(any(CreditRequest.class))).thenAnswer(inv -> {
            CreditRequest cr = inv.getArgument(0);
            cr.setId(1L);
            return cr;
        });

        var resp = service.solicitar(new CreditRequestDto(1L, new BigDecimal("5000"), 12));

        assertThat(resp.status()).isEqualTo(CreditStatus.NEGADO);
        assertThat(resp.motivo()).contains("sem score");
        verify(contractRepo, never()).save(any());
        verify(events).publishEvent(new CreditDecidedEvent(1L, 1L, new BigDecimal("5000"), 12, null, null,
                CreditStatus.NEGADO, "Cliente sem score de crédito registrado", BigDecimal.ZERO));
    }

    @Test
    void approvedForLowRisk() {
        when(scoreClient.getLatestScore(1L))
                .thenReturn(Optional.of(new ScoreResponse(1L, 820.0, "BAIXO")));
        when(crRepo.save(any(CreditRequest.class))).thenAnswer(inv -> {
            CreditRequest cr = inv.getArgument(0);
            cr.setId(2L);
            return cr;
        });

        var resp = service.solicitar(new CreditRequestDto(1L, new BigDecimal("10000"), 24));

        assertThat(resp.status()).isEqualTo(CreditStatus.APROVADO);
        assertThat(resp.valorAprovado()).isEqualByComparingTo("10000");
        assertThat(resp.taxaJuros()).isEqualByComparingTo("0.0299");
        verify(contractRepo, times(1)).save(any(Contract.class));
        verify(events).publishEvent(new CreditDecidedEvent(2L, 1L, new BigDecimal("10000"), 24, 820.0, "BAIXO",
                CreditStatus.APROVADO, "Aprovação automática — risco baixo", new BigDecimal("0.0299")));
    }

    @Test
    void reviewForMediumRisk() {
        when(scoreClient.getLatestScore(1L))
                .thenReturn(Optional.of(new ScoreResponse(1L, 620.0, "MEDIO")));
        when(crRepo.save(any(CreditRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        var resp = service.solicitar(new CreditRequestDto(1L, new BigDecimal("3000"), 6));

        assertThat(resp.status()).isEqualTo(CreditStatus.REVISAO);
        verify(contractRepo, never()).save(any());
    }

    @Test
    void deniedForHighRisk() {
        when(scoreClient.getLatestScore(1L))
                .thenReturn(Optional.of(new ScoreResponse(1L, 400.0, "ALTO")));
        when(crRepo.save(any(CreditRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        var resp = service.solicitar(new CreditRequestDto(1L, new BigDecimal("5000"), 12));

        assertThat(resp.status()).isEqualTo(CreditStatus.NEGADO);
        assertThat(resp.motivo()).contains("ALTO");
    }

    @Test
    void consultarReturnsWithContractDetailsWhenPresent() {
        CreditRequest cr = new CreditRequest();
        cr.setId(1L);
        cr.setCustomerId(1L);
        cr.setValorSolicitado(new BigDecimal("5000"));
        cr.setPrazoMeses(12);
        cr.setStatus(CreditStatus.APROVADO);

        Contract c = new Contract(1L, new BigDecimal("0.0299"), new BigDecimal("5000"));

        when(crRepo.findById(1L)).thenReturn(Optional.of(cr));
        when(contractRepo.findByCreditRequestId(1L)).thenReturn(Optional.of(c));

        var resp = service.consultar(1L);
        assertThat(resp.valorAprovado()).isEqualByComparingTo("5000");
    }

    @Test
    void consultarReturnsZeroValuesWhenNoContract() {
        CreditRequest cr = new CreditRequest();
        cr.setId(2L);
        cr.setStatus(CreditStatus.NEGADO);

        when(crRepo.findById(2L)).thenReturn(Optional.of(cr));
        when(contractRepo.findByCreditRequestId(eq(2L))).thenReturn(Optional.empty());

        var resp = service.consultar(2L);
        assertThat(resp.valorAprovado()).isEqualByComparingTo("0");
    }

    @Test
    void consultarThrowsWhenNotFound() {
        when(crRepo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.consultar(99L))
                .isInstanceOf(CreditRequestNotFoundException.class);
    }

    private static CreditRequest request(long id, long customerId, CreditStatus status) {
        CreditRequest cr = new CreditRequest();
        cr.setId(id);
        cr.setCustomerId(customerId);
        cr.setValorSolicitado(new BigDecimal("1000"));
        cr.setPrazoMeses(12);
        cr.setStatus(status);
        return cr;
    }

    @Test
    void listarJoinsContractsOfApprovedRequests() {
        CreditRequest approved = request(2L, 5L, CreditStatus.APROVADO);
        CreditRequest denied = request(1L, 5L, CreditStatus.NEGADO);
        when(crRepo.findByCustomerIdOrderByIdDesc(5L)).thenReturn(List.of(approved, denied));
        when(contractRepo.findByCreditRequestIdIn(List.of(2L, 1L)))
                .thenReturn(List.of(new Contract(2L, new BigDecimal("0.0299"), new BigDecimal("1000"))));

        var result = service.listar(5L);

        assertThat(result).extracting(r -> r.id()).containsExactly(2L, 1L);
        assertThat(result.get(0).valorAprovado()).isEqualByComparingTo("1000");
        assertThat(result.get(0).taxaJuros()).isEqualByComparingTo("0.0299");
        assertThat(result.get(1).valorAprovado()).isEqualByComparingTo("0");
    }

    @Test
    void listarWithoutCustomerReturnsAllNewestFirst() {
        when(crRepo.findAll(Sort.by(Sort.Direction.DESC, "id"))).thenReturn(List.of(request(3L, 1L, CreditStatus.REVISAO)));
        when(contractRepo.findByCreditRequestIdIn(List.of(3L))).thenReturn(List.of());

        assertThat(service.listar(null)).extracting(r -> r.status()).containsExactly(CreditStatus.REVISAO);
    }

    @Test
    void aguardandoContratacaoNaoDecideNemPublica() {
        var resp = service.registrarAguardando(5L, new BigDecimal("1000"), 6, "auth0|f", "Fábio");

        assertThat(resp.status()).isEqualTo(CreditStatus.AGUARDANDO_CONTRATACAO);
        assertThat(resp.createdByNome()).isEqualTo("Fábio");
        verify(crRepo).save(any(CreditRequest.class));
        verify(contractRepo, never()).save(any());
        verify(events, never()).publishEvent(any());
        assertThat(CreditStatus.AGUARDANDO_CONTRATACAO.decisao()).isFalse();
        assertThat(CreditStatus.APROVADO.decisao()).isTrue();
    }

    @Test
    void confirmarSoAceitaPedidoAguardando() {
        CreditRequest decidido = request(9L, 5L, CreditStatus.REVISAO);
        var d = new CreditService.PricedDecision(5L, new BigDecimal("1000"), 6, 700.0, "MEDIO",
                CreditStatus.APROVADO, "ok", new BigDecimal("0.0249"));

        assertThatThrownBy(() -> service.confirmarPrecificado(decidido, d)).isInstanceOf(IllegalStateException.class);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void confirmarDecideNoMesmoPedidoEMantemOAutor() {
        CreditRequest aguardando = request(9L, 5L, CreditStatus.AGUARDANDO_CONTRATACAO);
        aguardando.setCreatedByNome("Fábio");
        var d = new CreditService.PricedDecision(5L, new BigDecimal("1000"), 6, 700.0, "MEDIO",
                CreditStatus.NEGADO, "não", new BigDecimal("0.0249"), "auth0|dono", "Dona");

        var resp = service.confirmarPrecificado(aguardando, d);

        assertThat(resp.id()).isEqualTo(9L);
        assertThat(resp.status()).isEqualTo(CreditStatus.NEGADO);
        assertThat(resp.createdByNome()).isEqualTo("Fábio");
        verify(contractRepo, never()).save(any());
        verify(events).publishEvent(any(CreditDecidedEvent.class));
    }
}
