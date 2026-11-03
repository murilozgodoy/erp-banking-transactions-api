package com.erpbanking.transactions.portal.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.erpbanking.transactions.domain.Payment;
import com.erpbanking.transactions.domain.PaymentStatus;
import com.erpbanking.transactions.domain.PaymentType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Contratos JSON (snake_case) de {@code /me/payments}. */
public final class PaymentDtos {

    private PaymentDtos() {}

    /** Corpo de {@code POST /me/payments}. */
    public record PaymentRequest(
            @NotNull PaymentType tipo,
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal valor,
            @NotBlank @Size(max = 255) String destinatario,
            @Size(max = 255) String descricao,
            @JsonProperty("payable_id") Long payableId,
            @JsonProperty("agendado_para") LocalDate agendadoPara,
            @JsonProperty("codigo_barras") @Size(max = 64) String codigoBarras) {}

    /** Conta de origem mostrada no comprovante. */
    public record ContaOrigem(Long id, String agencia, String numero) {}

    /** Comprovante do pagamento. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Comprovante(
            PaymentType tipo,
            BigDecimal valor,
            String destinatario,
            String descricao,
            @JsonProperty("data_hora") Instant dataHora,
            @JsonProperty("agendado_para") LocalDate agendadoPara,
            String autenticacao,
            @JsonProperty("conta_origem") ContaOrigem contaOrigem,
            @JsonProperty("codigo_barras") String codigoBarras) {}

    /** Resposta 201 de {@code POST /me/payments}. */
    public record PaymentCreated(
            Long id,
            PaymentStatus status,
            String autenticacao,
            @JsonProperty("saldo_apos") BigDecimal saldoApos,
            Comprovante comprovante) {}

    /** Item do histórico ({@code GET /me/payments}). */
    public record PaymentView(
            Long id,
            PaymentType tipo,
            BigDecimal valor,
            String destinatario,
            String descricao,
            @JsonProperty("payable_id") Long payableId,
            @JsonProperty("agendado_para") LocalDate agendadoPara,
            @JsonProperty("codigo_barras") String codigoBarras,
            PaymentStatus status,
            String autenticacao,
            @JsonProperty("criado_em") Instant criadoEm,
            @JsonProperty("created_by_nome") String createdByNome) {

        public static PaymentView of(Payment p) {
            return new PaymentView(p.getId(), p.getTipo(), p.getValor(), p.getDestinatario(), p.getDescricao(),
                    p.getPayableId(), p.getAgendadoPara(), p.getCodigoBarras(), p.getStatus(),
                    p.getAutenticacao(), p.getCriadoEm(), p.getCreatedByNome());
        }
    }
}
