package com.erpbanking.transactions.portal.read;

import static com.erpbanking.transactions.portal.read.PortalCalculations.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.erpbanking.transactions.domain.PayableCategory;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.domain.PaymentMethod;
import com.erpbanking.transactions.domain.ReceivableStatus;
import com.erpbanking.transactions.portal.read.MeReadDtos.FluxoCaixaDia;

/**
 * Fluxo de caixa projetado (cálculo puro, sem banco). Começa do saldo atual e, para cada dia de hoje (dia 0) a
 * hoje + {@code dias} − 1, soma entradas e saídas:
 *
 * <p><b>Entradas</b>
 * <ol>
 *   <li>Recebíveis {@code PREVISTO} (valor líquido) no vencimento; os vencidos e ainda previstos caem no dia 0.
 *       Eles já cobrem as vendas passadas, inclusive as parcelas futuras do crédito.</li>
 *   <li>Vendas futuras estimadas para cada dia da janela: média das vendas dos últimos
 *       {@value #JANELA_VENDAS_DIAS} dias (hoje incluído) por forma de pagamento, parcelas e dia da semana (soma
 *       do dia da semana / quantas vezes ele aparece na janela). Cada venda estimada vira entrada quando o dinheiro
 *       entra de fato:
 *       <ul>
 *         <li>PIX, dinheiro e boleto: no próprio dia (D+0);</li>
 *         <li>cartão de débito: D+{@value #PRAZO_DEBITO_DIAS}, líquido da taxa;</li>
 *         <li>cartão de crédito em {@code n} parcelas: {@code n} entradas iguais em D+30, D+60, ..., líquidas da
 *             taxa (à vista = 1 parcela em D+30);</li>
 *       </ul>
 *       A taxa é a razão líquido/bruto observada nos recebíveis do cliente (mediana, para um boleto grande de
 *       tarifa fixa não distorcer); sem histórico, {@link #LIQUIDO_PADRAO}. Como toda venda estimada entra depois
 *       de hoje (débito e crédito) ou no dia dela, não há dupla contagem com os recebíveis já gravados.</li>
 * </ol>
 *
 * <p><b>Saídas</b>
 * <ol>
 *   <li>Contas em aberto ({@code ABERTO}, {@code ATRASADO}, {@code AGENDADO}) na data de saída (data do
 *       agendamento, se houver, senão o vencimento); as vencidas caem no dia 0.</li>
 *   <li>Despesas recorrentes estimadas: cada conta {@code PAGO} de {@link #RECORRENTES} dos últimos
 *       {@value #JANELA_RECORRENTES_DIAS} dias (a mais recente por categoria e fornecedor) se repete com o mesmo
 *       valor no mesmo dia do mês nos meses seguintes dentro da janela. O mês é pulado se já tiver conta em aberto
 *       da categoria (as contas futuras já lançadas mandam) ou conta do mesmo fornecedor e categoria (já paga ou
 *       lançada no ciclo).</li>
 * </ol>
 *
 * <p>É o mesmo modelo do mock do front ({@code fluxoCaixa} em {@code src/portal/mocks/db.ts}), que usa débito
 * D+1 e crédito à vista D+30; aqui as parcelas também entram (D+30·k) e as despesas recorrentes sem conta lançada
 * também saem. {@code entradas} e {@code saidas} são totais do dia com 2 casas.
 */
public final class CashFlowProjection {

    /** Janela de vendas usada para estimar as vendas futuras (hoje − 29 .. hoje). */
    public static final int JANELA_VENDAS_DIAS = 30;
    /** Prazo de liquidação do cartão de débito. */
    public static final int PRAZO_DEBITO_DIAS = 1;
    /** Prazo de cada parcela do cartão de crédito (parcela k entra em D + 30·k). */
    public static final int PRAZO_PARCELA_CREDITO_DIAS = 30;
    /** Histórico de contas pagas usado para estimar as despesas recorrentes. */
    public static final int JANELA_RECORRENTES_DIAS = 60;
    /** Categorias cujas contas pagas se repetem todo mês. */
    public static final Set<PayableCategory> RECORRENTES = EnumSet.of(PayableCategory.FOLHA,
            PayableCategory.ALUGUEL, PayableCategory.IMPOSTO, PayableCategory.EMPRESTIMO);
    /** Razão líquido/bruto do cartão quando o cliente não tem recebíveis (taxa de 3%). */
    public static final BigDecimal LIQUIDO_PADRAO = new BigDecimal("0.97");

    private static final int ESCALA = 10;

    /** Venda do histórico (ou soma de vendas do mesmo dia, forma e parcelas). */
    public record Venda(LocalDate data, PaymentMethod forma, int parcelas, BigDecimal valor) {}

    public record Recebivel(LocalDate vencimento, BigDecimal valorBruto, BigDecimal valorLiquido,
                            ReceivableStatus status) {}

    /** Conta a pagar; {@code agendadoPara} é a data de um pagamento agendado para ela (ou null). */
    public record Conta(LocalDate vencimento, LocalDate agendadoPara, PayableCategory categoria,
                        PayableStatus status, String fornecedor, BigDecimal valor) {}

    private CashFlowProjection() {}

    public static List<FluxoCaixaDia> projetar(LocalDate hoje, int dias, BigDecimal saldoInicial,
                                               List<Venda> vendas, List<Recebivel> recebiveis, List<Conta> contas) {
        Dias entradas = new Dias(hoje, dias);
        Dias saidas = new Dias(hoje, dias);

        for (Recebivel r : recebiveis) {
            if (r.status() == ReceivableStatus.PREVISTO) {
                entradas.somarNoDia(r.vencimento(), r.valorLiquido());
            }
        }
        vendasEstimadas(hoje, entradas, vendas, liquidoSobreBruto(recebiveis));

        for (Conta c : contas) {
            if (c.status() != PayableStatus.PAGO) {
                saidas.somarNoDia(c.agendadoPara() != null ? c.agendadoPara() : c.vencimento(), c.valor());
            }
        }
        recorrentes(hoje, saidas, contas);

        List<FluxoCaixaDia> out = new ArrayList<>(dias);
        BigDecimal saldo = money(saldoInicial);
        for (int i = 0; i < dias; i++) {
            BigDecimal e = money(entradas.valores[i]);
            BigDecimal s = money(saidas.valores[i]);
            saldo = saldo.add(e).subtract(s);
            out.add(new FluxoCaixaDia(hoje.plusDays(i), e, s, saldo));
        }
        return out;
    }

    /** Média por dia da semana × forma × parcelas, convertida em entradas nos dias em que o dinheiro cai. */
    static void vendasEstimadas(LocalDate hoje, Dias entradas, List<Venda> vendas, BigDecimal liquido) {
        LocalDate inicio = hoje.minusDays(JANELA_VENDAS_DIAS - 1L);
        Map<DayOfWeek, Integer> ocorrencias = new EnumMap<>(DayOfWeek.class);
        for (LocalDate d = inicio; !d.isAfter(hoje); d = d.plusDays(1)) {
            ocorrencias.merge(d.getDayOfWeek(), 1, Integer::sum);
        }
        Map<DayOfWeek, Map<Tipo, BigDecimal>> somas = new EnumMap<>(DayOfWeek.class);
        for (Venda v : vendas) {
            if (!v.data().isBefore(inicio) && !v.data().isAfter(hoje)) {
                somas.computeIfAbsent(v.data().getDayOfWeek(), k -> new HashMap<>())
                        .merge(new Tipo(v.forma(), Math.max(1, v.parcelas())), v.valor(), BigDecimal::add);
            }
        }
        for (int i = 0; i < entradas.valores.length; i++) {
            DayOfWeek dow = hoje.plusDays(i).getDayOfWeek();
            BigDecimal n = BigDecimal.valueOf(ocorrencias.get(dow));
            for (var e : somas.getOrDefault(dow, Map.of()).entrySet()) {
                BigDecimal media = e.getValue().divide(n, ESCALA, RoundingMode.HALF_UP);
                Tipo t = e.getKey();
                switch (t.forma()) {
                    case CARTAO_DEBITO -> entradas.somar(i + PRAZO_DEBITO_DIAS, media.multiply(liquido));
                    case CARTAO_CREDITO -> {
                        BigDecimal parcela = media.multiply(liquido)
                                .divide(BigDecimal.valueOf(t.parcelas()), ESCALA, RoundingMode.HALF_UP);
                        for (int k = 1; k <= t.parcelas(); k++) {
                            entradas.somar(i + PRAZO_PARCELA_CREDITO_DIAS * k, parcela);
                        }
                    }
                    default -> entradas.somar(i, media);
                }
            }
        }
    }

    /** Mediana de líquido/bruto dos recebíveis com bruto positivo; sem recebíveis, {@link #LIQUIDO_PADRAO}. */
    static BigDecimal liquidoSobreBruto(List<Recebivel> recebiveis) {
        List<BigDecimal> razoes = recebiveis.stream()
                .filter(r -> r.valorBruto() != null && r.valorBruto().signum() > 0 && r.valorLiquido() != null)
                .map(r -> r.valorLiquido().divide(r.valorBruto(), ESCALA, RoundingMode.HALF_UP))
                .sorted().toList();
        if (razoes.isEmpty()) {
            return LIQUIDO_PADRAO;
        }
        int meio = razoes.size() / 2;
        return razoes.size() % 2 == 1 ? razoes.get(meio)
                : razoes.get(meio - 1).add(razoes.get(meio)).divide(BigDecimal.valueOf(2), ESCALA, RoundingMode.HALF_UP);
    }

    /** Repete as contas pagas recorrentes nos meses da janela que ainda não têm a conta lançada. */
    static void recorrentes(LocalDate hoje, Dias saidas, List<Conta> contas) {
        LocalDate fim = hoje.plusDays(saidas.valores.length - 1L);
        Map<String, Conta> modelos = new HashMap<>();
        for (Conta c : contas) {
            if (c.status() == PayableStatus.PAGO && RECORRENTES.contains(c.categoria())
                    && !c.vencimento().isAfter(hoje)
                    && !c.vencimento().isBefore(hoje.minusDays(JANELA_RECORRENTES_DIAS))) {
                modelos.merge(c.categoria() + "|" + c.fornecedor(), c,
                        (a, b) -> b.vencimento().isAfter(a.vencimento()) ? b : a);
            }
        }
        for (Conta m : modelos.values()) {
            int dia = m.vencimento().getDayOfMonth();
            for (YearMonth mes = YearMonth.from(m.vencimento()).plusMonths(1); !mes.atDay(1).isAfter(fim);
                 mes = mes.plusMonths(1)) {
                LocalDate alvo = mes.atDay(Math.min(dia, mes.lengthOfMonth()));
                if (!alvo.isBefore(hoje) && !alvo.isAfter(fim) && !mesCoberto(contas, m, mes)) {
                    saidas.somar((int) ChronoUnit.DAYS.between(hoje, alvo), m.valor());
                }
            }
        }
    }

    private static boolean mesCoberto(List<Conta> contas, Conta modelo, YearMonth mes) {
        return contas.stream().anyMatch(c -> c.categoria() == modelo.categoria()
                && YearMonth.from(c.vencimento()).equals(mes)
                && (c.status() != PayableStatus.PAGO || Objects.equals(c.fornecedor(), modelo.fornecedor())));
    }

    private record Tipo(PaymentMethod forma, int parcelas) {}

    /** Valores por dia da janela; fora da janela é ignorado. */
    static final class Dias {
        final LocalDate hoje;
        final BigDecimal[] valores;

        Dias(LocalDate hoje, int dias) {
            this.hoje = hoje;
            this.valores = new BigDecimal[dias];
            Arrays.fill(valores, BigDecimal.ZERO);
        }

        /** Soma na data; datas passadas caem no dia 0. */
        void somarNoDia(LocalDate data, BigDecimal valor) {
            somar((int) Math.max(0, ChronoUnit.DAYS.between(hoje, data)), valor);
        }

        void somar(int i, BigDecimal valor) {
            if (i < valores.length && valor != null) {
                valores[i] = valores[i].add(valor);
            }
        }
    }
}
