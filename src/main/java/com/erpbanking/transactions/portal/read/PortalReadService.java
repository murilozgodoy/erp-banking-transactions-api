package com.erpbanking.transactions.portal.read;

import static com.erpbanking.transactions.portal.read.PortalCalculations.emAberto;
import static com.erpbanking.transactions.portal.read.PortalCalculations.media;
import static com.erpbanking.transactions.portal.read.PortalCalculations.money;
import static com.erpbanking.transactions.portal.read.PortalCalculations.pct;
import static com.erpbanking.transactions.portal.read.PortalCalculations.statusEfetivo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.InventoryItem;
import com.erpbanking.transactions.domain.Payable;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.domain.Payment;
import com.erpbanking.transactions.domain.PaymentStatus;
import com.erpbanking.transactions.domain.Receivable;
import com.erpbanking.transactions.domain.ReceivableStatus;
import com.erpbanking.transactions.domain.Sale;
import com.erpbanking.transactions.domain.Transaction;
import com.erpbanking.transactions.domain.TransactionType;
import com.erpbanking.transactions.portal.read.MeReadDtos.Conta;
import com.erpbanking.transactions.portal.read.MeReadDtos.FluxoCaixaDia;
import com.erpbanking.transactions.portal.read.MeReadDtos.Inventory;
import com.erpbanking.transactions.portal.read.MeReadDtos.InventoryRow;
import com.erpbanking.transactions.portal.read.MeReadDtos.Overview;
import com.erpbanking.transactions.portal.read.MeReadDtos.PayableItem;
import com.erpbanking.transactions.portal.read.MeReadDtos.Payables;
import com.erpbanking.transactions.portal.read.MeReadDtos.PorCanal;
import com.erpbanking.transactions.portal.read.MeReadDtos.PorForma;
import com.erpbanking.transactions.portal.read.MeReadDtos.ReceivableItem;
import com.erpbanking.transactions.portal.read.MeReadDtos.Receivables;
import com.erpbanking.transactions.portal.read.MeReadDtos.Sales;
import com.erpbanking.transactions.portal.read.MeReadDtos.Semana;
import com.erpbanking.transactions.portal.read.MeReadDtos.SeriePonto;
import com.erpbanking.transactions.portal.read.MeReadDtos.StatementEntry;
import com.erpbanking.transactions.portal.read.MeReadDtos.TopProduto;
import com.erpbanking.transactions.portal.read.PortalCalculations.GroupBy;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.InventoryItemRepository;
import com.erpbanking.transactions.repository.PayableRepository;
import com.erpbanking.transactions.repository.PaymentRepository;
import com.erpbanking.transactions.repository.ReceivableRepository;
import com.erpbanking.transactions.repository.SaleRepository;
import com.erpbanking.transactions.repository.TransactionRepository;

/** Leituras do portal do cliente (rotas GET /me): dashboard, vendas, recebíveis, contas, extrato e estoque. */
@Service
@Transactional(readOnly = true)
public class PortalReadService {

    static final int FLUXO_DIAS = 60;
    static final int GIRO_PARADO_DIAS = 90;
    static final int JANELA_PADRAO_DIAS = 30;
    /** Maior período aceito em {@code GET /me/sales} (from..to, inclusive). */
    static final int JANELA_MAX_VENDAS_DIAS = 400;

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final SaleRepository sales;
    private final ReceivableRepository receivables;
    private final PayableRepository payables;
    private final InventoryItemRepository inventory;
    private final PaymentRepository payments;
    private final PendenciasService pendencias;
    private final Clock clock;

    @Autowired
    public PortalReadService(AccountRepository accounts, TransactionRepository transactions, SaleRepository sales,
                             ReceivableRepository receivables, PayableRepository payables,
                             InventoryItemRepository inventory, PaymentRepository payments,
                             PendenciasService pendencias) {
        this(accounts, transactions, sales, receivables, payables, inventory, payments, pendencias,
                Clock.system(PortalCalculations.ZONE));
    }

    PortalReadService(AccountRepository accounts, TransactionRepository transactions, SaleRepository sales,
                      ReceivableRepository receivables, PayableRepository payables,
                      InventoryItemRepository inventory, PaymentRepository payments,
                      PendenciasService pendencias, Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.sales = sales;
        this.receivables = receivables;
        this.payables = payables;
        this.inventory = inventory;
        this.payments = payments;
        this.pendencias = pendencias;
        this.clock = clock;
    }

    private LocalDate hoje() {
        return LocalDate.now(clock.withZone(PortalCalculations.ZONE));
    }

    /** Conta mais recente do cliente (a conta corrente PJ aberta pelo seeder). */
    Optional<Account> conta(Long customerId) {
        return accounts.findByCustomerIdOrderByIdDesc(customerId).stream().findFirst();
    }

    // ---------------------------------------------------------------- overview

    public Overview overview(Long customerId) {
        LocalDate hoje = hoje();
        Optional<Account> account = conta(customerId);
        BigDecimal saldo = money(account.map(Account::getSaldo).orElse(BigDecimal.ZERO));
        Conta conta = account.map(a -> new Conta(a.getId(), PortalCalculations.AGENCIA,
                PortalCalculations.numeroConta(a.getId()))).orElse(null);

        List<Sale> ultimos60 = sales.findByCustomerIdAndDataBetweenOrderByDataAsc(customerId,
                hoje.minusDays(59), hoje);
        LocalDate inicio30 = hoje.minusDays(29);
        List<Sale> atual = ultimos60.stream().filter(s -> !s.getData().isBefore(inicio30)).toList();
        BigDecimal receita30 = soma(atual, Sale::getValor);
        BigDecimal receitaAnterior = soma(ultimos60.stream().filter(s -> s.getData().isBefore(inicio30)).toList(),
                Sale::getValor);
        List<Receivable> todosRecebiveis = receivables.findByCustomerIdOrderByVencimentoAsc(customerId);
        List<Receivable> previstos = todosRecebiveis.stream()
                .filter(r -> r.getStatus() == ReceivableStatus.PREVISTO).toList();
        BigDecimal recebiveis90 = soma(previstos.stream()
                .filter(r -> !r.getVencimento().isBefore(hoje) && !r.getVencimento().isAfter(hoje.plusDays(90)))
                .toList(), Receivable::getValorLiquido);

        List<Payable> todasContas = payables.findByCustomerIdOrderByVencimentoAsc(customerId);
        List<Payable> abertos = todasContas.stream().filter(p -> emAberto(p.getStatus())).toList();
        BigDecimal aPagar30 = soma(abertos.stream().filter(p -> !p.getVencimento().isAfter(hoje.plusDays(30)))
                .toList(), Payable::getValor);
        BigDecimal atrasados = soma(abertos.stream()
                .filter(p -> statusEfetivo(p, hoje) == PayableStatus.ATRASADO).toList(), Payable::getValor);

        BigDecimal estoque = money(inventory.findByCustomerIdOrderBySkuAsc(customerId).stream()
                .map(PortalReadService::valorEstoque).reduce(BigDecimal.ZERO, BigDecimal::add));

        return new Overview(saldo, conta, receita30, PortalCalculations.variacaoPct(receita30, receitaAnterior),
                media(receita30, atual.size()), recebiveis90, aPagar30, atrasados, estoque,
                pendencias.pendencias(customerId).size(),
                fluxoCaixa(customerId, hoje, saldo, atual, todosRecebiveis, todasContas));
    }

    /**
     * Fluxo de caixa de {@value #FLUXO_DIAS} dias a partir das vendas dos últimos 30 dias, de todos os recebíveis e
     * de todas as contas (as pagas alimentam as despesas recorrentes). Contas com pagamento agendado saem na data
     * do agendamento. O modelo está documentado em {@link CashFlowProjection}.
     */
    private List<FluxoCaixaDia> fluxoCaixa(Long customerId, LocalDate hoje, BigDecimal saldo, List<Sale> vendas30,
                                           List<Receivable> recebiveis, List<Payable> contas) {
        Map<Long, LocalDate> agendamentos = new HashMap<>();
        for (Payment p : payments.findByCustomerIdOrderByIdDesc(customerId)) {
            if (p.getStatus() == PaymentStatus.AGENDADO && p.getPayableId() != null && p.getAgendadoPara() != null) {
                agendamentos.putIfAbsent(p.getPayableId(), p.getAgendadoPara());
            }
        }
        return CashFlowProjection.projetar(hoje, FLUXO_DIAS, saldo,
                vendas30.stream().map(s -> new CashFlowProjection.Venda(s.getData(), s.getFormaPagamento(),
                        s.getParcelas() == null ? 1 : s.getParcelas(), s.getValor())).toList(),
                recebiveis.stream().map(r -> new CashFlowProjection.Recebivel(r.getVencimento(), r.getValorBruto(),
                        r.getValorLiquido(), r.getStatus())).toList(),
                contas.stream().map(p -> new CashFlowProjection.Conta(p.getVencimento(), agendamentos.get(p.getId()),
                        p.getCategoria(), p.getStatus(), p.getFornecedor(), p.getValor())).toList());
    }

    // ---------------------------------------------------------------- sales

    public Sales sales(Long customerId, LocalDate from, LocalDate to, String groupByRaw) {
        GroupBy groupBy = GroupBy.parse(groupByRaw);
        LocalDate ate = to != null ? to : hoje();
        LocalDate de = from != null ? from : ate.minusDays(JANELA_PADRAO_DIAS - 1L);
        validarPeriodo(de, ate);
        if (de.plusDays(JANELA_MAX_VENDAS_DIAS).isBefore(ate.plusDays(1))) {
            throw new IllegalArgumentException("Período máximo de " + JANELA_MAX_VENDAS_DIAS + " dias");
        }
        List<Sale> lista = sales.findByCustomerIdAndDataBetweenOrderByDataAsc(customerId, de, ate);
        BigDecimal total = soma(lista, Sale::getValor);

        Map<LocalDate, List<Sale>> porPeriodo = lista.stream()
                .collect(Collectors.groupingBy(s -> groupBy.inicio(s.getData())));
        List<SeriePonto> serie = new ArrayList<>();
        for (LocalDate p = groupBy.inicio(de); !p.isAfter(ate); p = groupBy.proximo(p)) {
            List<Sale> doPeriodo = porPeriodo.getOrDefault(p, List.of());
            serie.add(new SeriePonto(groupBy.rotulo(p), soma(doPeriodo, Sale::getValor), doPeriodo.size()));
        }

        List<PorForma> formas = agrupar(lista, s -> s.getFormaPagamento().name()).entrySet().stream()
                .map(e -> new PorForma(e.getKey(), e.getValue(), pct(e.getValue(), total))).toList();
        List<PorCanal> canais = agrupar(lista, Sale::getCanal).entrySet().stream()
                .map(e -> new PorCanal(e.getKey(), e.getValue(), pct(e.getValue(), total))).toList();

        Map<String, String> nomes = inventory.findByCustomerIdOrderBySkuAsc(customerId).stream()
                .collect(Collectors.toMap(InventoryItem::getSku, InventoryItem::getNome, (a, b) -> a));
        Map<String, List<Sale>> porSku = lista.stream().filter(s -> s.getProdutoSku() != null)
                .collect(Collectors.groupingBy(Sale::getProdutoSku));
        List<TopProduto> top = porSku.entrySet().stream()
                .map(e -> new TopProduto(e.getKey(), nomes.getOrDefault(e.getKey(), e.getKey()),
                        soma(e.getValue(), Sale::getValor), e.getValue().size()))
                .sorted(Comparator.comparing(TopProduto::valor).reversed().thenComparing(TopProduto::sku))
                .limit(5).toList();

        return new Sales(serie, formas, canais, top, total, media(total, lista.size()));
    }

    /** Soma por chave, do maior para o menor valor. */
    private static Map<String, BigDecimal> agrupar(List<Sale> lista, Function<Sale, String> chave) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (Sale s : lista) {
            m.merge(chave.apply(s), s.getValor(), BigDecimal::add);
        }
        Map<String, BigDecimal> ordenado = new LinkedHashMap<>();
        m.entrySet().stream()
                .sorted((a, b) -> {
                    int c = b.getValue().compareTo(a.getValue());
                    return c != 0 ? c : a.getKey().compareTo(b.getKey());
                })
                .forEach(e -> ordenado.put(e.getKey(), money(e.getValue())));
        return ordenado;
    }

    // ---------------------------------------------------------------- receivables

    public Receivables receivables(Long customerId, String statusRaw, LocalDate from, LocalDate to) {
        ReceivableStatus status = parseEnum(ReceivableStatus.class, statusRaw, "status");
        validarPeriodo(from, to);
        List<Receivable> lista = receivables.findByCustomerIdOrderByVencimentoAsc(customerId).stream()
                .filter(r -> status == null || r.getStatus() == status)
                .filter(r -> from == null || !r.getVencimento().isBefore(from))
                .filter(r -> to == null || !r.getVencimento().isAfter(to))
                .toList();
        List<ReceivableItem> itens = lista.stream().map(r -> new ReceivableItem(r.getId(), r.getVencimento(),
                money(r.getValorBruto()), money(r.getValorLiquido()), r.getAdquirente(), r.getStatus().name())).toList();
        BigDecimal previsto = soma(lista.stream().filter(r -> r.getStatus() == ReceivableStatus.PREVISTO).toList(),
                Receivable::getValorLiquido);
        Map<LocalDate, BigDecimal> semanas = new TreeMap<>();
        for (Receivable r : lista) {
            semanas.merge(PortalCalculations.segunda(r.getVencimento()), r.getValorLiquido(), BigDecimal::add);
        }
        List<Semana> porSemana = semanas.entrySet().stream()
                .map(e -> new Semana(e.getKey(), money(e.getValue()))).toList();
        return new Receivables(itens, previsto, porSemana);
    }

    // ---------------------------------------------------------------- payables

    public Payables payables(Long customerId, String statusRaw) {
        PayableStatus filtro = parseEnum(PayableStatus.class, statusRaw, "status");
        LocalDate hoje = hoje();
        List<Payable> todos = payables.findByCustomerIdOrderByVencimentoAsc(customerId);
        List<PayableItem> itens = todos.stream()
                .filter(p -> filtro == null || statusEfetivo(p, hoje) == filtro)
                .map(p -> new PayableItem(p.getId(), p.getFornecedor(), p.getCategoria().name(), p.getVencimento(),
                        money(p.getValor()), statusEfetivo(p, hoje).name(), p.getCodigoBarras(), p.getPagoEm()))
                .toList();
        BigDecimal aberto = soma(todos.stream().filter(p -> {
            PayableStatus s = statusEfetivo(p, hoje);
            return s == PayableStatus.ABERTO || s == PayableStatus.AGENDADO;
        }).toList(), Payable::getValor);
        BigDecimal atrasado = soma(todos.stream()
                .filter(p -> statusEfetivo(p, hoje) == PayableStatus.ATRASADO).toList(), Payable::getValor);
        BigDecimal proximos7 = soma(todos.stream()
                .filter(p -> emAberto(p.getStatus()) && !p.getVencimento().isBefore(hoje)
                        && !p.getVencimento().isAfter(hoje.plusDays(7)))
                .toList(), Payable::getValor);
        return new Payables(itens, aberto, atrasado, proximos7);
    }

    // ---------------------------------------------------------------- statement

    /**
     * Extrato da conta mais recente do cliente, do lançamento mais recente para o mais antigo. O
     * {@code saldo_apos} é reconstruído de trás para frente a partir do saldo atual da conta; o período
     * ({@code from}/{@code to}, por data em São Paulo) só filtra as linhas devolvidas. Padrão: últimos 30 dias.
     */
    public List<StatementEntry> statement(Long customerId, LocalDate from, LocalDate to) {
        LocalDate ate = to != null ? to : hoje();
        LocalDate de = from != null ? from : ate.minusDays(JANELA_PADRAO_DIAS - 1L);
        validarPeriodo(de, ate);
        Optional<Account> account = conta(customerId);
        if (account.isEmpty()) {
            return List.of();
        }
        Map<Long, Payment> pagamentos = payments.findByCustomerIdOrderByIdDesc(customerId).stream()
                .filter(p -> p.getTransactionId() != null)
                .collect(Collectors.toMap(Payment::getTransactionId, Function.identity(), (a, b) -> a));
        List<StatementEntry> out = new ArrayList<>();
        BigDecimal saldo = account.get().getSaldo();
        for (Transaction t : transactions.findByAccountIdOrderByTimestampDescIdDesc(account.get().getId())) {
            LocalDate dia = t.getTimestamp().atZone(PortalCalculations.ZONE).toLocalDate();
            if (!dia.isBefore(de) && !dia.isAfter(ate)) {
                out.add(new StatementEntry(t.getId(), t.getTimestamp(), t.getTipo().name(), money(t.getValor()),
                        money(saldo), descricao(t, pagamentos.get(t.getId()))));
            }
            saldo = t.getTipo() == TransactionType.CREDIT ? saldo.subtract(t.getValor()) : saldo.add(t.getValor());
        }
        return out;
    }

    static String descricao(Transaction t, Payment p) {
        if (p != null) {
            String base = p.getTipo().name() + " para " + p.getDestinatario();
            return p.getDescricao() == null || p.getDescricao().isBlank() ? base : base + " · " + p.getDescricao();
        }
        return t.getTipo() == TransactionType.CREDIT ? "Crédito em conta" : "Débito em conta";
    }

    // ---------------------------------------------------------------- inventory

    public Inventory inventory(Long customerId) {
        LocalDate hoje = hoje();
        List<InventoryItem> itens = inventory.findByCustomerIdOrderBySkuAsc(customerId);
        // Unidades vendidas por SKU nos últimos 30 dias, estimadas por valor da venda / preço de venda.
        Map<String, List<Sale>> vendasPorSku = sales.findByCustomerIdAndDataBetweenOrderByDataAsc(customerId,
                        hoje.minusDays(JANELA_PADRAO_DIAS - 1L), hoje).stream()
                .filter(s -> s.getProdutoSku() != null).collect(Collectors.groupingBy(Sale::getProdutoSku));

        List<InventoryRow> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal parado = BigDecimal.ZERO;
        int abaixo = 0;
        long somaCobertura = 0;
        for (InventoryItem i : itens) {
            BigDecimal valor = valorEstoque(i);
            boolean ehParado = i.getGiroDias() > GIRO_PARADO_DIAS;
            boolean ehAbaixo = i.getQuantidade() < i.getMinimo();
            int cobertura = coberturaDias(i, vendasPorSku.getOrDefault(i.getSku(), List.of()));
            total = total.add(valor);
            if (ehParado) {
                parado = parado.add(valor);
            }
            if (ehAbaixo) {
                abaixo++;
            }
            somaCobertura += cobertura;
            rows.add(row(i, cobertura));
        }
        BigDecimal coberturaMedia = itens.isEmpty() ? BigDecimal.ZERO.setScale(1)
                : BigDecimal.valueOf(somaCobertura).divide(BigDecimal.valueOf(itens.size()), 1, RoundingMode.HALF_UP);
        return new Inventory(rows, money(total), money(parado), abaixo, coberturaMedia);
    }

    /**
     * Um item no mesmo formato de {@code GET /me/inventory} {@code itens[]}, recalculado agora (usado na resposta
     * de {@code POST /me/inventory/{id}/movements}).
     */
    public InventoryRow inventoryRow(Long customerId, InventoryItem item) {
        LocalDate hoje = hoje();
        List<Sale> vendasSku = sales.findByCustomerIdAndDataBetweenOrderByDataAsc(customerId,
                        hoje.minusDays(JANELA_PADRAO_DIAS - 1L), hoje).stream()
                .filter(s -> item.getSku().equals(s.getProdutoSku())).toList();
        return row(item, coberturaDias(item, vendasSku));
    }

    private static InventoryRow row(InventoryItem i, int cobertura) {
        return new InventoryRow(i.getId(), i.getSku(), i.getNome(), i.getQuantidade(), i.getMinimo(),
                money(i.getCustoUnitario()), money(i.getPrecoVenda()), i.getGiroDias(), i.getUltimaVenda(),
                cobertura, i.getQuantidade() < i.getMinimo(), i.getGiroDias() > GIRO_PARADO_DIAS);
    }

    /**
     * Cobertura em dias = quantidade / (unidades vendidas nos últimos 30 dias / 30), com unidades estimadas por
     * valor / preço de venda. Sem vendas recentes (ou sem preço), usa o {@code giro_dias} cadastrado.
     */
    static int coberturaDias(InventoryItem i, List<Sale> vendas30d) {
        if (vendas30d.isEmpty() || i.getPrecoVenda() == null || i.getPrecoVenda().signum() <= 0) {
            return i.getGiroDias();
        }
        BigDecimal unidades = vendas30d.stream().map(Sale::getValor).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(i.getPrecoVenda(), 4, RoundingMode.HALF_UP);
        if (unidades.signum() <= 0) {
            return i.getGiroDias();
        }
        return BigDecimal.valueOf(i.getQuantidade()).multiply(BigDecimal.valueOf(JANELA_PADRAO_DIAS))
                .divide(unidades, 0, RoundingMode.HALF_UP).intValue();
    }

    static BigDecimal valorEstoque(InventoryItem i) {
        return i.getCustoUnitario().multiply(BigDecimal.valueOf(i.getQuantidade()));
    }

    // ---------------------------------------------------------------- util

    private static <T> BigDecimal soma(List<T> lista, Function<T, BigDecimal> valor) {
        return money(lista.stream().map(valor).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    static void validarPeriodo(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from deve ser anterior ou igual a to");
        }
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String campo) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(campo + " inválido: " + raw);
        }
    }
}
