package com.erpbanking.transactions.portal.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.erpbanking.transactions.domain.InventoryItem;
import com.erpbanking.transactions.domain.Payable;
import com.erpbanking.transactions.domain.PayableCategory;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.domain.PaymentMethod;
import com.erpbanking.transactions.domain.Receivable;
import com.erpbanking.transactions.domain.ReceivableStatus;
import com.erpbanking.transactions.domain.Sale;
import com.erpbanking.transactions.domain.Transaction;
import com.erpbanking.transactions.domain.TransactionType;
import com.erpbanking.transactions.portal.seed.SeedData.ReceivableDraft;

class PortalDataGeneratorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final PortalDataGenerator generator = new PortalDataGenerator();

    private static SeedProfile profile(long id, String segmento, Map<String, Object> respostas) {
        Map<String, Object> event = new HashMap<>();
        event.put("customer_id", id);
        event.put("segmento", segmento);
        event.put("respostas", respostas);
        return SeedProfile.from(id, event);
    }

    private static SeedProfile varejo(long id) {
        return profile(id, "varejo", Map.of("faturamento_mensal", 180000, "pct_cartao_credito", 60,
                "parcelamento_medio", 3, "margem_bruta_pct", 45, "folha_mensal", "32.000,00",
                "parcela_dividas_mensal", 4500, "possui_estoque", true, "valor_estoque", 250000,
                "sazonalidade", "Média"));
    }

    private static String fingerprint(SeedData d) {
        StringBuilder sb = new StringBuilder();
        d.sales().forEach(s -> sb.append(s.getData()).append(s.getValor()).append(s.getFormaPagamento())
                .append(s.getParcelas()).append(s.getCanal()).append(s.getProdutoSku()).append('|'));
        d.receivables().forEach(r -> sb.append(r.saleIndex()).append(r.receivable().getVencimento())
                .append(r.receivable().getValorLiquido()).append(r.receivable().getAdquirente()).append('|'));
        d.payables().forEach(p -> sb.append(p.getFornecedor()).append(p.getVencimento()).append(p.getValor())
                .append(p.getStatus()).append(p.getCodigoBarras()).append(p.getPagoEm()).append('|'));
        d.inventory().forEach(i -> sb.append(i.getSku()).append(i.getNome()).append(i.getQuantidade())
                .append(i.getCustoUnitario()).append(i.getGiroDias()).append(i.getUltimaVenda()).append('|'));
        d.transactions().forEach(t -> sb.append(t.getTipo()).append(t.getValor()).append(t.getTimestamp()).append('|'));
        return sb.append(d.saldo()).toString();
    }

    @Test
    void sameSeedProducesSameData() {
        SeedData a = generator.generate(varejo(42), TODAY, ZONE);
        SeedData b = generator.generate(varejo(42), TODAY, ZONE);
        SeedData c = generator.generate(varejo(43), TODAY, ZONE);

        assertThat(fingerprint(a)).isEqualTo(fingerprint(b));
        assertThat(fingerprint(a)).isNotEqualTo(fingerprint(c));
    }

    @Test
    void salesCover120DaysAndScaleWithMonthlyRevenue() {
        SeedData d = generator.generate(varejo(42), TODAY, ZONE);

        assertThat(d.sales()).allSatisfy(s -> {
            assertThat(s.getCustomerId()).isEqualTo(42L);
            assertThat(s.getData()).isBetween(TODAY.minusDays(120), TODAY.minusDays(1));
            assertThat(s.getValor().scale()).isEqualTo(2);
            assertThat(s.getValor()).isPositive();
            assertThat(s.getParcelas()).isBetween(1, 12);
        });
        BigDecimal total = d.sales().stream().map(Sale::getValor).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total.doubleValue() / 4).isCloseTo(180000, org.assertj.core.data.Percentage.withPercentage(2));
        BigDecimal last30 = d.sales().stream().filter(s -> !s.getData().isBefore(TODAY.minusDays(30)))
                .map(Sale::getValor).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(last30.doubleValue()).isCloseTo(180000, org.assertj.core.data.Percentage.withPercentage(20));
    }

    @Test
    void paymentMixFollowsCreditCardShareAndInstallments() {
        SeedData d = generator.generate(varejo(7), TODAY, ZONE);

        Map<PaymentMethod, Long> count = d.sales().stream()
                .collect(Collectors.groupingBy(Sale::getFormaPagamento, Collectors.counting()));
        double credit = count.getOrDefault(PaymentMethod.CARTAO_CREDITO, 0L) / (double) d.sales().size();
        assertThat(credit).isBetween(0.5, 0.7);
        assertThat(count).containsKeys(PaymentMethod.CARTAO_DEBITO, PaymentMethod.PIX, PaymentMethod.DINHEIRO);
        double avgParcelas = d.sales().stream().filter(s -> s.getFormaPagamento() == PaymentMethod.CARTAO_CREDITO)
                .mapToInt(Sale::getParcelas).average().orElse(0);
        assertThat(avgParcelas).isBetween(2.5, 3.5);
        assertThat(d.sales()).filteredOn(s -> s.getFormaPagamento() != PaymentMethod.CARTAO_CREDITO)
                .allSatisfy(s -> assertThat(s.getParcelas()).isEqualTo(1));
        Set<String> canais = d.sales().stream().map(Sale::getCanal).collect(Collectors.toSet());
        assertThat(SegmentCatalog.canais("varejo")).containsAll(canais);
        Set<String> skus = d.inventory().stream().map(InventoryItem::getSku).collect(Collectors.toSet());
        assertThat(d.sales()).allSatisfy(s -> assertThat(skus).contains(s.getProdutoSku()));
    }

    @Test
    void weekendsWeighMoreForFoodService() {
        SeedProfile p = profile(9, "alimentacao", Map.of("faturamento_mensal", 90000, "sazonalidade", "Alta"));
        SeedData d = generator.generate(p, TODAY, ZONE);

        Map<Boolean, Double> avg = d.sales().stream().collect(Collectors.partitioningBy(
                s -> s.getData().getDayOfWeek().getValue() >= 6,
                Collectors.summingDouble(s -> s.getValor().doubleValue())));
        // 2 dias de fim de semana vs 5 dias úteis: por dia o fim de semana vende mais.
        assertThat(avg.get(true) / 2).isGreaterThan(avg.get(false) / 5);
    }

    @Test
    void receivablesFollowCardRules() {
        SeedData d = generator.generate(varejo(42), TODAY, ZONE);

        assertThat(d.receivables()).isNotEmpty();
        for (ReceivableDraft draft : d.receivables()) {
            Sale sale = d.sales().get(draft.saleIndex());
            Receivable r = draft.receivable();
            assertThat(r.getVencimento()).isBetween(TODAY.minusDays(30), TODAY.plusDays(90));
            assertThat(r.getValorLiquido()).isEqualByComparingTo(r.getValorBruto().subtract(r.getTaxaAdquirente()));
            assertThat(r.getStatus()).isEqualTo(r.getVencimento().isBefore(TODAY)
                    ? ReceivableStatus.RECEBIDO : ReceivableStatus.PREVISTO);
            assertThat(SegmentCatalog.ADQUIRENTES).contains(r.getAdquirente());
            double rate = r.getTaxaAdquirente().doubleValue() / r.getValorBruto().doubleValue();
            if (sale.getFormaPagamento() == PaymentMethod.CARTAO_DEBITO) {
                assertThat(r.getVencimento()).isEqualTo(sale.getData().plusDays(1));
                assertThat(rate).isBetween(0.0, 0.0125);
            } else {
                assertThat(sale.getFormaPagamento()).isEqualTo(PaymentMethod.CARTAO_CREDITO);
                long days = r.getVencimento().toEpochDay() - sale.getData().toEpochDay();
                assertThat(days % 30).isZero();
                assertThat(days / 30).isBetween(1L, (long) sale.getParcelas());
                assertThat(rate).isBetween(0.015, 0.0405);
            }
        }
        assertThat(d.receivables()).anyMatch(r -> r.receivable().getStatus() == ReceivableStatus.PREVISTO)
                .anyMatch(r -> r.receivable().getStatus() == ReceivableStatus.RECEBIDO);
        // Parcelas de uma venda somam o valor da venda quando todas estão na janela.
        Map<Integer, List<ReceivableDraft>> bySale = d.receivables().stream()
                .collect(Collectors.groupingBy(ReceivableDraft::saleIndex));
        bySale.forEach((idx, list) -> {
            Sale s = d.sales().get(idx);
            if (list.size() == s.getParcelas()) {
                BigDecimal sum = list.stream().map(x -> x.receivable().getValorBruto())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(sum).isEqualByComparingTo(s.getValor());
            }
        });
    }

    @Test
    void payablesHaveOverdueRecentPaidAndUpcoming() {
        SeedData d = generator.generate(varejo(42), TODAY, ZONE);

        List<Payable> atrasadas = d.payables().stream().filter(p -> p.getStatus() == PayableStatus.ATRASADO).toList();
        assertThat(atrasadas).hasSizeBetween(2, 3)
                .allSatisfy(p -> assertThat(p.getVencimento()).isBetween(TODAY.minusDays(6), TODAY.minusDays(1)));
        assertThat(d.payables()).filteredOn(p -> p.getStatus() == PayableStatus.PAGO).isNotEmpty()
                .allSatisfy(p -> {
                    assertThat(p.getVencimento()).isBefore(TODAY);
                    assertThat(p.getPagoEm()).isNotNull();
                });
        assertThat(d.payables()).filteredOn(p -> p.getStatus() == PayableStatus.ABERTO).isNotEmpty()
                .allSatisfy(p -> assertThat(p.getVencimento()).isBetween(TODAY, TODAY.plusDays(60)));
        assertThat(d.payables()).extracting(Payable::getCategoria).contains(PayableCategory.values());
        assertThat(d.payables()).allSatisfy(p -> {
            assertThat(p.getValor().scale()).isEqualTo(2);
            if (p.getCategoria() == PayableCategory.FOLHA) {
                assertThat(p.getCodigoBarras()).isNull();
            } else {
                assertThat(p.getCodigoBarras()).hasSize(47).containsOnlyDigits();
            }
        });
    }

    @Test
    void noLoanWithoutDebtInstallmentAndNoInventoryWhenNotStocked() {
        SeedProfile p = profile(5, "tecnologia", Map.of("faturamento_mensal", "R$ 60.000", "possui_estoque", "nao"));
        SeedData d = generator.generate(p, TODAY, ZONE);

        assertThat(d.payables()).noneMatch(x -> x.getCategoria() == PayableCategory.EMPRESTIMO);
        assertThat(d.inventory()).isEmpty();
        assertThat(d.sales()).allSatisfy(s -> assertThat(s.getProdutoSku()).isNull());
        long boletoOuPix = d.sales().stream().filter(s -> s.getFormaPagamento() == PaymentMethod.BOLETO
                || s.getFormaPagamento() == PaymentMethod.PIX).count();
        assertThat(boletoOuPix).isGreaterThan(d.sales().size() / 2);
    }

    @Test
    void inventoryIsCoherentWithStockValue() {
        SeedData d = generator.generate(varejo(42), TODAY, ZONE);

        assertThat(d.inventory()).hasSizeBetween(15, 40);
        assertThat(d.inventory()).filteredOn(i -> i.getQuantidade() < i.getMinimo()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(d.inventory()).filteredOn(i -> i.getGiroDias() > 120).hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(i -> assertThat(i.getUltimaVenda()).isBefore(TODAY.minusDays(120)));
        assertThat(d.inventory()).allSatisfy(i -> {
            assertThat(i.getSku()).startsWith("VAR-");
            assertThat(i.getPrecoVenda()).isGreaterThan(i.getCustoUnitario());
            assertThat(i.getUltimaVenda()).isNotNull();
        });
        double custo = d.inventory().stream()
                .mapToDouble(i -> i.getQuantidade() * i.getCustoUnitario().doubleValue()).sum();
        assertThat(custo).isCloseTo(250000, org.assertj.core.data.Percentage.withPercentage(25));
        assertThat(d.inventory()).extracting(InventoryItem::getSku).doesNotHaveDuplicates();
    }

    @Test
    void statementAddsUpToFinalBalance() {
        SeedData d = generator.generate(varejo(42), TODAY, ZONE);

        BigDecimal net = d.transactions().stream()
                .map(t -> t.getTipo() == TransactionType.CREDIT ? t.getValor() : t.getValor().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo(d.saldo());
        assertThat(d.saldo().scale()).isEqualTo(2);
        assertThat(d.saldo()).isPositive();
        assertThat(d.transactions()).allSatisfy(t -> {
            assertThat(t.getValor()).isPositive();
            assertThat(t.getTimestamp()).isAfterOrEqualTo(TODAY.minusDays(30).atStartOfDay(ZONE).toInstant())
                    .isBefore(TODAY.atStartOfDay(ZONE).toInstant());
        });
        assertThat(d.transactions()).extracting(Transaction::getTipo)
                .contains(TransactionType.CREDIT, TransactionType.DEBIT);
        // Running balance never goes negative (opening first, then chronological).
        BigDecimal running = BigDecimal.ZERO;
        Transaction prev = null;
        for (Transaction t : d.transactions()) {
            if (prev != null) {
                assertThat(t.getTimestamp()).isAfterOrEqualTo(prev.getTimestamp());
            }
            prev = t;
            running = running.add(t.getTipo() == TransactionType.CREDIT ? t.getValor() : t.getValor().negate());
        }
        assertThat(running).isEqualByComparingTo(d.saldo());
    }

    @ParameterizedTest
    @ValueSource(strings = {"varejo", "alimentacao", "servicos", "saude", "tecnologia", "industria", "agro",
            "construcao", "outro"})
    void everySegmentGeneratesConsistentData(String segmento) {
        Map<String, Object> respostas = Map.of("possui_estoque", true, "sazonalidade", segmento.length() % 2 == 0
                ? "Baixa" : "Alta", "parcela_dividas_mensal", 1000);
        SeedData d = generator.generate(profile(100 + segmento.length(), segmento, respostas), TODAY, ZONE);

        assertThat(d.sales()).isNotEmpty();
        assertThat(d.inventory()).hasSizeBetween(15, 40)
                .allSatisfy(i -> assertThat(i.getSku()).startsWith(SegmentCatalog.skuPrefix(segmento) + "-"));
        assertThat(d.payables()).anyMatch(p -> p.getCategoria() == PayableCategory.EMPRESTIMO);
        assertThat(d.saldo()).isPositive();
        assertThat(d.sales().stream().map(Sale::getValor).reduce(BigDecimal.ZERO, BigDecimal::add).doubleValue())
                .isCloseTo(400000, org.assertj.core.data.Percentage.withPercentage(2));
    }

    @Test
    void customTicketAndChannelsAreUsed() {
        SeedProfile p = profile(11, "alimentacao", Map.of("ticket_medio", 1000, "canais", List.of("iFood", "Salão")));
        SeedData d = generator.generate(p, TODAY, ZONE);

        assertThat(d.sales()).extracting(Sale::getCanal).containsOnly("iFood", "Salão");
        double avg = d.sales().stream().mapToDouble(s -> s.getValor().doubleValue()).average().orElse(0);
        assertThat(avg).isBetween(500.0, 2000.0);
    }

    @Test
    void openingBalanceHasAFloorWhenInflowsExceedTarget() {
        // Folha zerada e margem alta: entradas superam muito as saídas, a abertura cai no piso de 5%.
        SeedProfile p = profile(13, "tecnologia", Map.of("folha_mensal", 0, "margem_bruta_pct", 90,
                "pct_cartao_credito", 0));
        SeedData d = generator.generate(p, TODAY, ZONE);

        assertThat(d.transactions().get(0).getValor()).isEqualByComparingTo("5000.00");
        assertThat(d.payables()).noneMatch(x -> x.getCategoria() == PayableCategory.FOLHA);
        assertThat(d.sales()).noneMatch(s -> s.getFormaPagamento() == PaymentMethod.CARTAO_CREDITO);
    }

    @Test
    void helpers() {
        Random r = new Random(1);
        assertThat(PortalDataGenerator.parcelas(1, r)).isEqualTo(1);
        for (int i = 0; i < 100; i++) {
            assertThat(PortalDataGenerator.parcelas(12, r)).isBetween(1, 12);
        }
        assertThat(PortalDataGenerator.linhaDigitavel(r)).hasSize(47).containsOnlyDigits();
        double[] week = PortalDataGenerator.normalizedWeek(new double[]{1, 1, 1, 1, 1, 2, 0}, 1.3);
        double sum = 0;
        for (double w : week) {
            sum += w;
            assertThat(w).isNotNegative();
        }
        assertThat(sum).isCloseTo(7.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(PortalDataGenerator.amplitude("baixa")).isLessThan(PortalDataGenerator.amplitude("alta"));
    }
}
