package com.erpbanking.transactions.portal.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SeedProfileTest {

    @Test
    void emptyPayloadUsesDefaults() {
        SeedProfile p = SeedProfile.from(1, null);

        assertThat(p.customerId()).isEqualTo(1);
        assertThat(p.segmento()).isEqualTo("outro");
        assertThat(p.faturamentoMensal()).isEqualByComparingTo("100000");
        assertThat(p.pctCartaoCredito()).isEqualTo(50);
        assertThat(p.parcelamentoMedio()).isEqualTo(2);
        assertThat(p.margemBrutaPct()).isEqualTo(40);
        assertThat(p.folhaMensal()).isEqualByComparingTo("18000");
        assertThat(p.parcelaDividasMensal()).isEqualByComparingTo("0");
        assertThat(p.possuiEstoque()).isFalse();
        assertThat(p.valorEstoque()).isNull();
        assertThat(p.ticketMedio()).isNull();
        assertThat(p.sazonalidade()).isEqualTo("media");
        assertThat(p.canais()).isEmpty();
    }

    @Test
    void parsesNumbersAsNumberOrString() {
        SeedProfile p = SeedProfile.from(2, Map.of("segmento", " Varejo ", "respostas", Map.of(
                "faturamento_mensal", "R$ 1.234.567,89", "pct_cartao_credito", "70%", "parcelamento_medio", 4.5,
                "margem_bruta_pct", "35.5", "folha_mensal", 20000L, "parcela_dividas_mensal", "3.000",
                "possui_estoque", "Sim", "valor_estoque", new BigDecimal("50000"), "ticket_medio", "89,90",
                "sazonalidade", "ALTA")));

        assertThat(p.segmento()).isEqualTo("varejo");
        assertThat(p.faturamentoMensal()).isEqualByComparingTo("1234567.89");
        assertThat(p.faturamentoMensal().scale()).isEqualTo(2);
        assertThat(p.pctCartaoCredito()).isEqualTo(70);
        assertThat(p.parcelamentoMedio()).isEqualTo(4.5);
        assertThat(p.margemBrutaPct()).isEqualTo(35.5);
        assertThat(p.folhaMensal()).isEqualByComparingTo("20000");
        assertThat(p.parcelaDividasMensal()).isEqualByComparingTo("3000");
        assertThat(p.possuiEstoque()).isTrue();
        assertThat(p.valorEstoque()).isEqualByComparingTo("50000");
        assertThat(p.ticketMedio()).isEqualByComparingTo("89.90");
        assertThat(p.sazonalidade()).isEqualTo("alta");
    }

    @Test
    void invalidValuesFallBackAndAreClamped() {
        SeedProfile p = SeedProfile.from(3, Map.of("segmento", "pet-shop", "respostas", Map.of(
                "faturamento_mensal", "abc", "pct_cartao_credito", 150, "parcelamento_medio", 40,
                "margem_bruta_pct", -3, "folha_mensal", -1, "valor_estoque", 0, "ticket_medio", "-5",
                "possui_estoque", "talvez", "sazonalidade", 3, "canais", Arrays.asList("Loja", " ", 7, null))));

        assertThat(p.segmento()).isEqualTo("outro");
        assertThat(p.faturamentoMensal()).isEqualByComparingTo("100000");
        assertThat(p.pctCartaoCredito()).isEqualTo(100);
        assertThat(p.parcelamentoMedio()).isEqualTo(12);
        assertThat(p.margemBrutaPct()).isEqualTo(5);
        assertThat(p.folhaMensal()).isEqualByComparingTo("18000");
        assertThat(p.valorEstoque()).isNull();
        assertThat(p.ticketMedio()).isNull();
        assertThat(p.possuiEstoque()).isFalse();
        assertThat(p.sazonalidade()).isEqualTo("media");
        assertThat(p.canais()).containsExactly("Loja");
    }

    @Test
    void segmentDefaults() {
        assertThat(SeedProfile.from(4, Map.of("segmento", "industria")).possuiEstoque()).isTrue();
        assertThat(SeedProfile.from(4, Map.of("segmento", "industria")).pctCartaoCredito()).isEqualTo(10);
        assertThat(SeedProfile.from(4, Map.of("segmento", 7)).segmento()).isEqualTo("outro");
        assertThat(SeedProfile.from(4, Map.of("respostas", "x")).faturamentoMensal()).isEqualByComparingTo("100000");
        for (String seg : List.of("varejo", "alimentacao", "servicos", "saude", "tecnologia", "industria", "agro",
                "construcao", "outro")) {
            assertThat(SeedProfile.defaultParcelamento(seg)).isBetween(1.0, 3.0);
            assertThat(SeedProfile.defaultMargem(seg)).isBetween(30.0, 70.0);
            assertThat(SeedProfile.defaultPctCartao(seg)).isBetween(5.0, 50.0);
        }
    }

    @Test
    void conversionHelpers() {
        assertThat(SeedProfile.decimal(Double.NaN)).isNull();
        assertThat(SeedProfile.decimal("  ")).isNull();
        assertThat(SeedProfile.decimal("1,2,3")).isNull();
        assertThat(SeedProfile.decimal(Map.of())).isNull();
        assertThat(SeedProfile.decimal(1.0E7)).isEqualByComparingTo("10000000");
        assertThat(SeedProfile.bool(1)).isTrue();
        assertThat(SeedProfile.bool(0)).isFalse();
        assertThat(SeedProfile.bool("não")).isFalse();
        assertThat(SeedProfile.bool(List.of())).isNull();
        assertThat(SeedProfile.sazonalidade("Média")).isEqualTo("media");
        assertThat(SeedProfile.sazonalidade("baixa")).isEqualTo("baixa");
        assertThat(SeedProfile.strings("x")).isEmpty();
    }
}
