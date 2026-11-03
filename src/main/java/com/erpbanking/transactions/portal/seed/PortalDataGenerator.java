package com.erpbanking.transactions.portal.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
import com.erpbanking.transactions.portal.seed.SegmentCatalog.OtherMix;
import com.erpbanking.transactions.portal.seed.SegmentCatalog.Product;

/**
 * Gera a massa do portal de forma pura e determinística: mesmo perfil + mesma data = mesmos dados
 * ({@code java.util.Random} com semente = {@code customer_id}). Sem Spring e sem banco.
 *
 * <ul>
 *   <li>Vendas: {@value #SALES_DAYS} dias até ontem; total ≈ {@code faturamento_mensal × 4}, com sazonalidade semanal
 *       por segmento (amplitude conforme {@code sazonalidade}) e leve tendência de alta.</li>
 *   <li>Recebíveis: cartão de crédito = uma parcela a cada 30 dias; débito = D+1. Mantém os vencimentos entre
 *       hoje − {@value #RECEIVABLES_PAST_DAYS} e hoje + {@value #RECEIVABLES_FUTURE_DAYS}; RECEBIDO se venceu antes
 *       de hoje, senão PREVISTO.</li>
 *   <li>Contas a pagar: de hoje − {@value #PAYABLES_PAST_DAYS} (PAGO) a hoje + {@value #PAYABLES_FUTURE_DAYS}
 *       (ABERTO), mais 2 ou 3 ATRASADO vencidas nos últimos dias.</li>
 *   <li>Estoque (se {@code possui_estoque}): 15 a 40 SKUs, alguns abaixo do mínimo e alguns parados (giro &gt; 120).</li>
 *   <li>Conta: saldo final de 15% a 40% do faturamento; extrato dos últimos 30 dias (saldo de abertura,
 *       recebimentos de PIX/boleto/dinheiro, repasses das adquirentes e pagamentos das contas PAGO).</li>
 * </ul>
 */
public class PortalDataGenerator {

    static final int SALES_DAYS = 120;
    static final int RECEIVABLES_PAST_DAYS = 30;
    static final int RECEIVABLES_FUTURE_DAYS = 90;
    static final int PAYABLES_PAST_DAYS = 30;
    static final int PAYABLES_FUTURE_DAYS = 60;
    static final int STATEMENT_DAYS = 30;
    static final int MAX_SALES_PER_DAY = 25;

    public SeedData generate(SeedProfile p, LocalDate today, ZoneId zone) {
        Random r = new Random(p.customerId());
        List<InventoryItem> inventory = p.possuiEstoque() ? inventory(p, r, today) : List.of();
        List<Sale> sales = sales(p, r, today, inventory);
        refreshLastSale(inventory, sales, today);
        List<ReceivableDraft> receivables = receivables(p, r, today, sales);
        List<Payable> payables = payables(p, r, today, zone);
        BigDecimal saldoAlvo = scale(p.faturamentoMensal().doubleValue() * (0.15 + r.nextDouble() * 0.25));
        List<Transaction> transactions = new ArrayList<>();
        BigDecimal saldo = statement(p, today, zone, sales, receivables, payables, saldoAlvo, transactions);
        return new SeedData(sales, receivables, payables, inventory, transactions, saldo);
    }

    // ---------------------------------------------------------------- vendas

    private List<Sale> sales(SeedProfile p, Random r, LocalDate today, List<InventoryItem> inventory) {
        String seg = p.segmento();
        double amp = amplitude(p.sazonalidade());
        double[] week = normalizedWeek(SegmentCatalog.weekday(seg), amp);
        double noise = switch (p.sazonalidade()) {
            case "baixa" -> 0.06;
            case "alta" -> 0.2;
            default -> 0.12;
        };
        double wave = switch (p.sazonalidade()) {
            case "baixa" -> 0.03;
            case "alta" -> 0.15;
            default -> 0.07;
        };
        double phase = r.nextDouble() * Math.PI * 2;

        LocalDate start = today.minusDays(SALES_DAYS);
        double[] dayWeight = new double[SALES_DAYS];
        double sumWeight = 0;
        for (int i = 0; i < SALES_DAYS; i++) {
            LocalDate d = start.plusDays(i);
            double trend = 0.92 + 0.13 * i / (SALES_DAYS - 1);
            double monthly = 1 + wave * Math.sin(phase + 2 * Math.PI * i / 30.0);
            double jitter = Math.max(0.5, 1 + r.nextGaussian() * noise);
            dayWeight[i] = week[d.getDayOfWeek().getValue() - 1] * trend * monthly * jitter;
            sumWeight += dayWeight[i];
        }
        double total = p.faturamentoMensal().doubleValue() * SALES_DAYS / 30.0;
        double ticket = p.ticketMedio() != null ? p.ticketMedio().doubleValue() : SegmentCatalog.ticket(seg);
        List<String> canais = p.canais().isEmpty() ? SegmentCatalog.canais(seg) : p.canais();
        List<InventoryItem> ativos = inventory.stream().filter(i -> i.getGiroDias() <= 120).toList();

        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < SALES_DAYS; i++) {
            double dayTotal = total * dayWeight[i] / sumWeight;
            if (dayTotal < 1) {
                continue;
            }
            int n = (int) Math.max(1, Math.min(MAX_SALES_PER_DAY,
                    Math.round(dayTotal / ticket * (0.85 + r.nextDouble() * 0.3))));
            double[] w = new double[n];
            double sw = 0;
            for (int k = 0; k < n; k++) {
                w[k] = 0.4 + r.nextDouble() * 1.2;
                sw += w[k];
            }
            for (int k = 0; k < n; k++) {
                Sale s = new Sale();
                s.setCustomerId(p.customerId());
                s.setData(start.plusDays(i));
                s.setValor(scale(Math.max(0.01, dayTotal * w[k] / sw)));
                s.setCanal(canais.get(r.nextInt(canais.size())));
                PaymentMethod forma = paymentMethod(p, r);
                s.setFormaPagamento(forma);
                s.setParcelas(forma == PaymentMethod.CARTAO_CREDITO ? parcelas(p.parcelamentoMedio(), r) : 1);
                s.setProdutoSku(ativos.isEmpty() ? null : ativos.get(r.nextInt(ativos.size())).getSku());
                sales.add(s);
            }
        }
        return sales;
    }

    static double amplitude(String sazonalidade) {
        return switch (sazonalidade) {
            case "baixa" -> 0.6;
            case "alta" -> 1.3;
            default -> 1.0;
        };
    }

    /** Fatores da semana com amplitude ajustada e média 1 (o total semanal não muda com a sazonalidade). */
    static double[] normalizedWeek(double[] base, double amp) {
        double[] out = new double[7];
        double sum = 0;
        for (int i = 0; i < 7; i++) {
            out[i] = Math.max(0.0, 1 + (base[i] - 1) * amp);
            sum += out[i];
        }
        for (int i = 0; i < 7; i++) {
            out[i] = out[i] * 7 / sum;
        }
        return out;
    }

    private static PaymentMethod paymentMethod(SeedProfile p, Random r) {
        double credito = p.pctCartaoCredito() / 100.0;
        double x = r.nextDouble();
        if (x < credito) {
            return PaymentMethod.CARTAO_CREDITO;
        }
        OtherMix mix = SegmentCatalog.mix(p.segmento());
        double rest = (x - credito) / Math.max(1e-9, 1 - credito);
        double sum = mix.debito() + mix.pix() + mix.boleto() + mix.dinheiro();
        double acc = mix.debito() / sum;
        if (rest < acc) {
            return PaymentMethod.CARTAO_DEBITO;
        }
        acc += mix.pix() / sum;
        if (rest < acc) {
            return PaymentMethod.PIX;
        }
        acc += mix.boleto() / sum;
        if (rest < acc) {
            return PaymentMethod.BOLETO;
        }
        return PaymentMethod.DINHEIRO;
    }

    /** Uniforme em [1, 2m − 1] (média ≈ m), limitado a 12x. */
    static int parcelas(double medio, Random r) {
        int max = (int) Math.min(12, Math.round(2 * medio - 1));
        return max <= 1 ? 1 : 1 + r.nextInt(max);
    }

    // ---------------------------------------------------------------- recebíveis

    private List<ReceivableDraft> receivables(SeedProfile p, Random r, LocalDate today, List<Sale> sales) {
        String principal = SegmentCatalog.ADQUIRENTES.get(r.nextInt(SegmentCatalog.ADQUIRENTES.size()));
        LocalDate from = today.minusDays(RECEIVABLES_PAST_DAYS);
        LocalDate to = today.plusDays(RECEIVABLES_FUTURE_DAYS);
        List<ReceivableDraft> out = new ArrayList<>();
        for (int idx = 0; idx < sales.size(); idx++) {
            Sale s = sales.get(idx);
            boolean credito = s.getFormaPagamento() == PaymentMethod.CARTAO_CREDITO;
            if (!credito && s.getFormaPagamento() != PaymentMethod.CARTAO_DEBITO) {
                continue;
            }
            String adquirente = r.nextDouble() < 0.6 ? principal
                    : SegmentCatalog.ADQUIRENTES.get(r.nextInt(SegmentCatalog.ADQUIRENTES.size()));
            int n = s.getParcelas();
            double taxa = credito
                    ? Math.min(0.04, 0.019 + 0.0025 * (n - 1) + r.nextDouble() * 0.004)
                    : 0.008 + r.nextDouble() * 0.004;
            BigDecimal parcela = s.getValor().divide(BigDecimal.valueOf(n), 2, RoundingMode.DOWN);
            for (int k = 1; k <= n; k++) {
                LocalDate venc = credito ? s.getData().plusDays(30L * k) : s.getData().plusDays(1);
                if (venc.isBefore(from) || venc.isAfter(to)) {
                    continue;
                }
                BigDecimal bruto = k < n ? parcela : s.getValor().subtract(parcela.multiply(BigDecimal.valueOf(n - 1)));
                BigDecimal fee = bruto.multiply(BigDecimal.valueOf(taxa)).setScale(2, RoundingMode.HALF_UP);
                Receivable rec = new Receivable();
                rec.setCustomerId(p.customerId());
                rec.setVencimento(venc);
                rec.setValorBruto(bruto);
                rec.setTaxaAdquirente(fee);
                rec.setValorLiquido(bruto.subtract(fee));
                rec.setAdquirente(adquirente);
                rec.setStatus(venc.isBefore(today) ? ReceivableStatus.RECEBIDO : ReceivableStatus.PREVISTO);
                out.add(new ReceivableDraft(idx, rec));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- contas a pagar

    private List<Payable> payables(SeedProfile p, Random r, LocalDate today, ZoneId zone) {
        double fat = p.faturamentoMensal().doubleValue();
        double folha = p.folhaMensal().doubleValue();
        double cmv = fat * (1 - p.margemBrutaPct() / 100.0);
        List<String> fornecedores = SegmentCatalog.fornecedores(p.segmento());
        String imobiliaria = pick(SegmentCatalog.IMOBILIARIAS, r);
        String banco = pick(SegmentCatalog.BANCOS_CREDORES, r);
        double aluguel = fat * (0.03 + r.nextDouble() * 0.03);
        double imposto = fat * (0.05 + r.nextDouble() * 0.03);

        LocalDate from = today.minusDays(PAYABLES_PAST_DAYS);
        LocalDate to = today.plusDays(PAYABLES_FUTURE_DAYS);
        List<Payable> out = new ArrayList<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            String ref = String.format("%02d/%d", m.getMonthValue(), m.getYear());
            if (folha > 0) {
                add(out, p, r, today, zone, from, to, m, 5, PayableCategory.FOLHA, "Folha de pagamento " + ref,
                        folha * 0.6, false);
                add(out, p, r, today, zone, from, to, m, 20, PayableCategory.FOLHA, "Adiantamento salarial " + ref,
                        folha * 0.4, false);
                add(out, p, r, today, zone, from, to, m, 7, PayableCategory.IMPOSTO, "Caixa - FGTS " + ref,
                        folha * 0.08, true);
            }
            add(out, p, r, today, zone, from, to, m, 20, PayableCategory.IMPOSTO,
                    "Receita Federal - DAS Simples Nacional " + ref, imposto * jitter(r, 0.1), true);
            add(out, p, r, today, zone, from, to, m, 10, PayableCategory.ALUGUEL, imobiliaria + " - Aluguel " + ref,
                    aluguel, true);
            for (String servico : SegmentCatalog.SERVICOS) {
                add(out, p, r, today, zone, from, to, m, 8 + r.nextInt(18), PayableCategory.SERVICOS, servico,
                        fat * (0.002 + r.nextDouble() * 0.006), true);
            }
            if (p.parcelaDividasMensal().signum() > 0) {
                add(out, p, r, today, zone, from, to, m, 15, PayableCategory.EMPRESTIMO,
                        banco + " - Parcela capital de giro", p.parcelaDividasMensal().doubleValue(), true);
            }
            int compras = 4 + r.nextInt(5);
            for (int i = 0; i < compras; i++) {
                add(out, p, r, today, zone, from, to, m, 1 + r.nextInt(28), PayableCategory.FORNECEDOR,
                        pick(fornecedores, r), cmv / compras * (0.6 + r.nextDouble() * 0.8), true);
            }
        }
        int atrasadas = 2 + r.nextInt(2);
        for (int i = 0; i < atrasadas; i++) {
            boolean fornecedor = i != 1;
            Payable pay = payable(p, r, fornecedor ? PayableCategory.FORNECEDOR : PayableCategory.SERVICOS,
                    fornecedor ? pick(fornecedores, r) : pick(SegmentCatalog.SERVICOS, r),
                    today.minusDays(1 + r.nextInt(6)),
                    fornecedor ? cmv * (0.04 + r.nextDouble() * 0.08) : fat * (0.003 + r.nextDouble() * 0.005), true);
            pay.setStatus(PayableStatus.ATRASADO);
            out.add(pay);
        }
        out.sort(Comparator.comparing(Payable::getVencimento));
        return out;
    }

    private void add(List<Payable> out, SeedProfile p, Random r, LocalDate today, ZoneId zone, LocalDate from,
                     LocalDate to, YearMonth m, int day, PayableCategory cat, String fornecedor, double valor,
                     boolean boleto) {
        LocalDate venc = m.atDay(Math.min(day, m.lengthOfMonth()));
        if (venc.isBefore(from) || venc.isAfter(to) || valor < 1) {
            return;
        }
        Payable pay = payable(p, r, cat, fornecedor, venc, valor, boleto);
        if (venc.isBefore(today)) {
            pay.setStatus(PayableStatus.PAGO);
            LocalTime hora = LocalTime.of(9 + r.nextInt(8), r.nextInt(60));
            pay.setPagoEm(venc.atTime(hora).atZone(zone).toInstant());
        } else {
            pay.setStatus(PayableStatus.ABERTO);
        }
        out.add(pay);
    }

    private static Payable payable(SeedProfile p, Random r, PayableCategory cat, String fornecedor, LocalDate venc,
                                   double valor, boolean boleto) {
        Payable pay = new Payable();
        pay.setCustomerId(p.customerId());
        pay.setCategoria(cat);
        pay.setFornecedor(fornecedor);
        pay.setVencimento(venc);
        pay.setValor(scale(valor));
        pay.setCodigoBarras(boleto ? linhaDigitavel(r) : null);
        return pay;
    }

    /** Linha digitável de boleto: 47 dígitos, começando pelo código do banco e a moeda (9). */
    static String linhaDigitavel(Random r) {
        StringBuilder sb = new StringBuilder(pick(SegmentCatalog.BANCOS_BOLETO, r)).append('9');
        while (sb.length() < 47) {
            sb.append(r.nextInt(10));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- estoque

    private List<InventoryItem> inventory(SeedProfile p, Random r, LocalDate today) {
        List<Product> catalogo = SegmentCatalog.produtos(p.segmento());
        String[] variantes = {"", " Premium", " Econômico"};
        int count = 15 + r.nextInt(26);
        double margem = p.margemBrutaPct() / 100.0;
        int abaixo = Math.max(2, count / 6);
        int parados = Math.max(2, count / 7);
        List<InventoryItem> out = new ArrayList<>();
        double custoTotal = 0;
        for (int i = 0; i < count; i++) {
            Product prod = catalogo.get(i % catalogo.size());
            String variante = variantes[Math.min(variantes.length - 1, i / catalogo.size())];
            double custo = prod.custoMin() + r.nextDouble() * (prod.custoMax() - prod.custoMin());
            if (variante.equals(" Premium")) {
                custo *= 1.35;
            } else if (!variante.isEmpty()) {
                custo *= 0.8;
            }
            int qtd = (int) Math.max(3, Math.round((1500 + r.nextDouble() * 10500) / custo));
            int minimo = (int) Math.max(1, Math.round(qtd * (0.25 + r.nextDouble() * 0.3)));
            InventoryItem it = new InventoryItem();
            it.setCustomerId(p.customerId());
            it.setSku(String.format("%s-%03d", SegmentCatalog.skuPrefix(p.segmento()), i + 1));
            it.setNome(prod.nome() + variante);
            it.setCustoUnitario(scale(custo));
            it.setPrecoVenda(scale(custo / (1 - margem)));
            it.setQuantidade(qtd);
            it.setMinimo(minimo);
            it.setGiroDias(5 + r.nextInt(70));
            out.add(it);
            custoTotal += qtd * custo;
        }
        // Os primeiros índices embaralhados viram "abaixo do mínimo" e os seguintes, "parados".
        List<Integer> ordem = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ordem.add(i);
        }
        Collections.shuffle(ordem, r);
        if (p.valorEstoque() != null) {
            double fator = p.valorEstoque().doubleValue() / custoTotal;
            for (InventoryItem it : out) {
                it.setQuantidade((int) Math.max(1, Math.round(it.getQuantidade() * fator)));
                it.setMinimo((int) Math.max(1, Math.round(it.getMinimo() * fator)));
            }
        }
        for (int k = 0; k < abaixo; k++) {
            InventoryItem it = out.get(ordem.get(k));
            it.setMinimo(Math.max(it.getMinimo(), 2));
            it.setQuantidade((int) Math.floor(it.getMinimo() * (0.1 + r.nextDouble() * 0.6)));
            it.setGiroDias(2 + r.nextInt(10));
        }
        for (int k = abaixo; k < abaixo + parados; k++) {
            InventoryItem it = out.get(ordem.get(k));
            it.setGiroDias(130 + r.nextInt(170));
            it.setUltimaVenda(today.minusDays(125 + r.nextInt(120)));
        }
        return out;
    }

    /** Última venda dos itens ativos = data da venda mais recente do SKU (ou poucos dias atrás). */
    private static void refreshLastSale(List<InventoryItem> inventory, List<Sale> sales, LocalDate today) {
        Map<String, LocalDate> last = new HashMap<>();
        for (Sale s : sales) {
            if (s.getProdutoSku() != null) {
                last.merge(s.getProdutoSku(), s.getData(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        for (InventoryItem it : inventory) {
            if (it.getUltimaVenda() == null) {
                it.setUltimaVenda(last.getOrDefault(it.getSku(), today.minusDays(3)));
            }
        }
    }

    // ---------------------------------------------------------------- conta e extrato

    private static BigDecimal statement(SeedProfile p, LocalDate today, ZoneId zone, List<Sale> sales,
                                        List<ReceivableDraft> receivables, List<Payable> payables,
                                        BigDecimal saldoAlvo, List<Transaction> out) {
        LocalDate from = today.minusDays(STATEMENT_DAYS);
        Map<LocalDate, BigDecimal> vendasDiretas = new HashMap<>();
        for (Sale s : sales) {
            PaymentMethod f = s.getFormaPagamento();
            if (!s.getData().isBefore(from) && f != PaymentMethod.CARTAO_CREDITO && f != PaymentMethod.CARTAO_DEBITO) {
                vendasDiretas.merge(s.getData(), s.getValor(), BigDecimal::add);
            }
        }
        Map<LocalDate, BigDecimal> repasses = new HashMap<>();
        for (ReceivableDraft d : receivables) {
            Receivable rec = d.receivable();
            if (rec.getStatus() == ReceivableStatus.RECEBIDO && !rec.getVencimento().isBefore(from)) {
                repasses.merge(rec.getVencimento(), rec.getValorLiquido(), BigDecimal::add);
            }
        }
        List<Transaction> movimentos = new ArrayList<>();
        BigDecimal net = BigDecimal.ZERO;
        for (LocalDate d = from; d.isBefore(today); d = d.plusDays(1)) {
            BigDecimal rep = repasses.get(d);
            if (rep != null && rep.signum() > 0) {
                movimentos.add(tx(TransactionType.CREDIT, rep, d.atTime(8, 30).atZone(zone).toInstant()));
                net = net.add(rep);
            }
            BigDecimal dir = vendasDiretas.get(d);
            if (dir != null && dir.signum() > 0) {
                movimentos.add(tx(TransactionType.CREDIT, dir, d.atTime(19, 0).atZone(zone).toInstant()));
                net = net.add(dir);
            }
        }
        for (Payable pay : payables) {
            if (pay.getStatus() == PayableStatus.PAGO) {
                movimentos.add(tx(TransactionType.DEBIT, pay.getValor(), pay.getPagoEm()));
                net = net.subtract(pay.getValor());
            }
        }
        BigDecimal minimoAbertura = scale(p.faturamentoMensal().doubleValue() * 0.05);
        BigDecimal abertura = saldoAlvo.subtract(net).max(minimoAbertura);
        out.add(tx(TransactionType.CREDIT, abertura, from.atTime(LocalTime.of(7, 0)).atZone(zone).toInstant()));
        movimentos.sort(Comparator.comparing(Transaction::getTimestamp));
        out.addAll(movimentos);
        return abertura.add(net);
    }

    private static Transaction tx(TransactionType tipo, BigDecimal valor, Instant when) {
        Transaction t = new Transaction(null, tipo, valor);
        t.setTimestamp(when);
        return t;
    }

    // ---------------------------------------------------------------- util

    private static <T> T pick(List<T> list, Random r) {
        return list.get(r.nextInt(list.size()));
    }

    private static double jitter(Random r, double pct) {
        return 1 - pct + r.nextDouble() * 2 * pct;
    }

    static BigDecimal scale(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
