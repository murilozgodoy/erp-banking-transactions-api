package com.erpbanking.transactions.portal.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.erpbanking.transactions.domain.InventoryMovementType;
import com.erpbanking.transactions.portal.PortalMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class InventoryMovementServiceTest {

    @Test
    void custoMedioPonderadoHalfEvenDuasCasas() {
        assertThat(InventoryMovementService.custoMedio(10, new BigDecimal("5.00"), 30, new BigDecimal("7.00")))
                .isEqualByComparingTo("6.50");
        // 1,005 → 1,00 (HALF_EVEN); 1,015 → 1,02
        assertThat(InventoryMovementService.custoMedio(3, new BigDecimal("1.00"), 3, new BigDecimal("1.01")))
                .isEqualTo(new BigDecimal("1.00"));
        assertThat(InventoryMovementService.custoMedio(1, new BigDecimal("1.00"), 1, new BigDecimal("1.03")))
                .isEqualTo(new BigDecimal("1.02"));
        // Sem estoque anterior, vale o custo novo.
        assertThat(InventoryMovementService.custoMedio(0, null, 4, new BigDecimal("2.50")))
                .isEqualTo(new BigDecimal("2.50"));
    }

    @Test
    void contadorDeMovimentacoesPreRegistradoComZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PortalMetrics metrics = new PortalMetrics(registry);
        for (InventoryMovementType tipo : InventoryMovementType.values()) {
            assertThat(registry.get(PortalMetrics.INVENTORY_MOVEMENTS).tag("tipo", tipo.name()).counter().count())
                    .isZero();
        }
        metrics.inventoryMovement(InventoryMovementType.SAIDA);
        assertThat(registry.get(PortalMetrics.INVENTORY_MOVEMENTS).tag("tipo", "SAIDA").counter().count())
                .isEqualTo(1.0);
    }
}
