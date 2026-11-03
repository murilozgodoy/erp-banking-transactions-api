package com.erpbanking.transactions.portal.inventory;

import java.math.BigDecimal;
import java.time.Instant;

import com.erpbanking.transactions.domain.InventoryMovement;
import com.erpbanking.transactions.domain.InventoryMovementType;
import com.erpbanking.transactions.portal.read.MeReadDtos.InventoryRow;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Contrato de {@code /me/inventory/{id}/movements}. JSON em snake_case. */
public final class InventoryDtos {

    private InventoryDtos() {}

    public static final int QUANTIDADE_MAX = 1_000_000;

    /**
     * Corpo de {@code POST /me/inventory/{id}/movements}. {@code quantidade} chega como número decimal para recusar
     * {@code 1.5} (o Jackson truncaria num {@code int}) e valores enormes com 400 em vez de estouro; o limite inferior
     * depende do tipo (&gt; 0 em ENTRADA/SAIDA, &ge; 0 em AJUSTE) e é checado no serviço.
     */
    public record MovementRequest(
            @NotNull InventoryMovementType tipo,
            @NotNull
            @Digits(integer = 7, fraction = 0, message = "deve ser um número inteiro")
            @DecimalMin(value = "0", message = "deve ser maior ou igual a 0")
            @DecimalMax(value = "1000000", message = "deve ser menor ou igual a 1000000")
            BigDecimal quantidade,
            @Size(max = 200) String motivo,
            @JsonProperty("custo_unitario")
            @DecimalMin(value = "0", inclusive = false, message = "deve ser maior que 0")
            @Digits(integer = 17, fraction = 2, message = "deve ter no máximo 2 casas decimais")
            BigDecimal custoUnitario) {}

    /** Uma movimentação (item do histórico e {@code movimento} da resposta 201). */
    public record MovementView(
            Long id,
            @JsonProperty("item_id") Long itemId,
            InventoryMovementType tipo,
            int quantidade,
            @JsonProperty("quantidade_anterior") int quantidadeAnterior,
            @JsonProperty("quantidade_final") int quantidadeFinal,
            @JsonProperty("custo_unitario") BigDecimal custoUnitario,
            String motivo,
            @JsonProperty("created_by_nome") String createdByNome,
            @JsonProperty("created_at") Instant createdAt) {

        public static MovementView of(InventoryMovement m) {
            return new MovementView(m.getId(), m.getItemId(), m.getTipo(), m.getQuantidade(),
                    m.getQuantidadeAnterior(), m.getQuantidadeFinal(), m.getCustoUnitario(), m.getMotivo(),
                    m.getCreatedByNome(), m.getCreatedAt());
        }
    }

    /** Resposta 201: a movimentação e o item recalculado (mesmo formato de {@code GET /me/inventory} itens[]). */
    public record MovementCreated(MovementView movimento, InventoryRow item) {}
}
