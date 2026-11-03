package com.erpbanking.transactions.portal.read;

import static com.erpbanking.transactions.portal.read.PortalFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.portal.read.MeReadDtos.FluxoCaixaDia;
import com.erpbanking.transactions.portal.read.MeReadDtos.Pendencia;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PortalReadServiceTest {

    static final LocalDate HOJE = LocalDate.of(2026, 11, 3);
    static final Clock CLOCK = Clock.fixed(HOJE.atTime(12, 0).atZone(PortalCalculations.ZONE).toInstant(),
            PortalCalculations.ZONE);
    static final Long C = 7101L;

    @Autowired PortalFixtures f;

    PendenciasService pendencias;
    PortalReadService service;
    PortalFixtures.Ids ids;

    @BeforeEach
    void setUp() {
        pendencias = new PendenciasService(f.accounts, f.payables, f.inventory, f.creditRequests, CLOCK);
        service = new PortalReadService(f.accounts, f.transactions, f.sales, f.receivables, f.payables, f.inventory,
                f.payments, pendencias, CLOCK);
        ids = f.cenario(C, HOJE);
    }

    @Test
    void overviewCalculaKpisEFluxo() {
        var o = service.overview(C);
        assertThat(o.saldo()).isEqualByComparingTo("1000");
        assertThat(o.conta().id()).isEqualTo(ids.account());
        assertThat(o.conta().agencia()).isEqualTo("0001");
        assertThat(o.conta().numero()).isEqualTo(PortalCalculations.numeroConta(ids.account()));
        assertThat(o.receita30d()).isEqualByComparingTo("400");
        assertThat(o.receita30dVariacaoPct()).isEqualByComparingTo("100.0");
        assertThat(o.ticketMedio30d()).isEqualByComparingTo("200");
        assertThat(o.recebiveis90d()).isEqualByComparingTo("290");
        assertThat(o.aPagar30d()).isEqualByComparingTo("1750");
        assertThat(o.atrasadosTotal()).isEqualByComparingTo("1200");
        assertThat(o.estoqueValor()).isEqualByComparingTo("250");
        assertThat(o.pendenciasCount()).isEqualTo(6);

        List<FluxoCaixaDia> fluxo = o.fluxoCaixa();
        assertThat(fluxo).hasSize(60);
        assertThat(fluxo.get(0).data()).isEqualTo(HOJE);
        // Vendas estimadas por dia da semana (janela 05/10..03/11: 5 terças e 5 segundas): PIX de 100 na terça
        // → 20 em toda terça (D+0); crédito de 300 na segunda → 60 por segunda, × 0,9667 (mediana líquido/bruto
        // dos recebíveis) em D+30.
        assertThat(fluxo.get(0).entradas()).isEqualByComparingTo("20");
        assertThat(fluxo.get(0).saidas()).isEqualByComparingTo("1350");
        assertThat(fluxo.get(0).saldoProjetado()).isEqualByComparingTo("-330");
        assertThat(fluxo.get(5).entradas()).isEqualByComparingTo("290");
        assertThat(fluxo.get(5).saidas()).isEqualByComparingTo("400");
        assertThat(fluxo.get(7).entradas()).isEqualByComparingTo("20");
        assertThat(fluxo.get(34).entradas()).isZero();
        assertThat(fluxo.get(36).entradas()).isEqualByComparingTo("58");
        assertThat(fluxo.get(40).saidas()).isEqualByComparingTo("999");
        assertThat(fluxo.get(59).data()).isEqualTo(HOJE.plusDays(59));
    }

    @Test
    void fluxoUsaDataDoAgendamentoEDespesaRecorrente() {
        var folha = f.payables.findByCustomerIdOrderByVencimentoAsc(C).stream()
                .filter(p -> p.getFornecedor().equals("Folha")).findFirst().orElseThrow();
        var agendamento = new com.erpbanking.transactions.domain.Payment();
        agendamento.setCustomerId(C);
        agendamento.setTipo(com.erpbanking.transactions.domain.PaymentType.BOLETO);
        agendamento.setDestinatario("Folha");
        agendamento.setValor(bd("400.00"));
        agendamento.setPayableId(folha.getId());
        agendamento.setAgendadoPara(HOJE.plusDays(7));
        agendamento.setStatus(com.erpbanking.transactions.domain.PaymentStatus.AGENDADO);
        agendamento.setAutenticacao("AUT-2");
        f.payments.save(agendamento);
        var aluguel = f.payable(C, "Imobiliária", HOJE.minusDays(20), "800.00", PayableStatus.PAGO);
        aluguel.setCategoria(com.erpbanking.transactions.domain.PayableCategory.ALUGUEL);
        f.payables.save(aluguel);

        var fluxo = service.overview(C).fluxoCaixa();
        assertThat(fluxo.get(5).saidas()).isZero();
        assertThat(fluxo.get(7).saidas()).isEqualByComparingTo("400");
        // Aluguel pago em 14/10 se repete em 14/11 e 14/12 (nenhuma conta de aluguel lançada nesses meses).
        assertThat(fluxo.get(11).saidas()).isEqualByComparingTo("800");
        assertThat(fluxo.get(41).saidas()).isEqualByComparingTo("800");
    }

    @Test
    void overviewSemContaNemDados() {
        var o = service.overview(7999L);
        assertThat(o.saldo()).isEqualByComparingTo("0");
        assertThat(o.conta()).isNull();
        assertThat(o.receita30dVariacaoPct()).isNull();
        assertThat(o.ticketMedio30d()).isEqualByComparingTo("0");
        assertThat(o.pendenciasCount()).isZero();
        assertThat(o.fluxoCaixa()).hasSize(60).allSatisfy(d -> assertThat(d.saldoProjetado()).isEqualByComparingTo("0"));
    }

    @Test
    void salesPorSemanaEMes() {
        var s = service.sales(C, null, null, "week");
        assertThat(s.serie()).extracting(MeReadDtos.SeriePonto::periodo)
                .containsExactly("2026-10-05", "2026-10-12", "2026-10-19", "2026-10-26", "2026-11-02");
        assertThat(s.serie().get(4).valor()).isEqualByComparingTo("400");
        assertThat(s.serie().get(4).quantidade()).isEqualTo(2);
        assertThat(s.serie().get(0).valor()).isEqualByComparingTo("0");
        assertThat(s.total()).isEqualByComparingTo("400");
        assertThat(s.ticketMedio()).isEqualByComparingTo("200");
        assertThat(s.porFormaPagamento()).extracting(MeReadDtos.PorForma::forma).containsExactly("CARTAO_CREDITO", "PIX");
        assertThat(s.porFormaPagamento().get(0).pct()).isEqualByComparingTo("75.0");
        assertThat(s.porCanal()).extracting(MeReadDtos.PorCanal::canal).containsExactly("E-commerce", "Loja física");
        assertThat(s.topProdutos()).extracting(MeReadDtos.TopProduto::nome).containsExactly("Jaqueta", "Camiseta");

        var m = service.sales(C, HOJE.minusDays(80), HOJE, "month");
        assertThat(m.serie()).extracting(MeReadDtos.SeriePonto::periodo).containsExactly("2026-08", "2026-09", "2026-10", "2026-11");
        assertThat(m.total()).isEqualByComparingTo("1599");
        assertThat(m.topProdutos()).hasSize(2);

        var d = service.sales(C, HOJE.minusDays(1), HOJE, null);
        assertThat(d.serie()).extracting(MeReadDtos.SeriePonto::periodo).containsExactly("2026-11-02", "2026-11-03");
    }

    @Test
    void salesTopProdutoSemCadastroUsaSku() {
        f.sale(C, HOJE, "50.00", com.erpbanking.transactions.domain.PaymentMethod.DINHEIRO, "Loja física", "SKU-X");
        var s = service.sales(C, HOJE, HOJE, "day");
        assertThat(s.topProdutos()).extracting(MeReadDtos.TopProduto::nome).contains("SKU-X");
    }

    @Test
    void salesParametrosInvalidos() {
        assertThatThrownBy(() -> service.sales(C, null, null, "year")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.sales(C, HOJE, HOJE.minusDays(1), "day"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receivablesFiltraEAgrupaPorSemana() {
        var r = service.receivables(C, "previsto", null, null);
        assertThat(r.itens()).hasSize(2);
        assertThat(r.totalPrevisto()).isEqualByComparingTo("338");
        assertThat(r.porSemana()).extracting(MeReadDtos.Semana::semana)
                .containsExactly(LocalDate.of(2026, 11, 2), LocalDate.of(2027, 2, 8));

        var todos = service.receivables(C, null, HOJE.minusDays(5), HOJE.plusDays(10));
        assertThat(todos.itens()).extracting(MeReadDtos.ReceivableItem::status).containsExactly("RECEBIDO", "PREVISTO");
        assertThat(todos.totalPrevisto()).isEqualByComparingTo("290");
        assertThatThrownBy(() -> service.receivables(C, "XPTO", null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payablesMostraAbertoVencidoComoAtrasado() {
        var p = service.payables(C, null);
        assertThat(p.itens()).hasSize(6);
        assertThat(p.itens().get(0).id()).isEqualTo(ids.atrasadaAntiga());
        assertThat(p.itens().get(0).status()).isEqualTo("ATRASADO");
        assertThat(p.totalAberto()).isEqualByComparingTo("1549");
        assertThat(p.totalAtrasado()).isEqualByComparingTo("1200");
        assertThat(p.proximos7d()).isEqualByComparingTo("550");

        var atrasadas = service.payables(C, "ATRASADO");
        assertThat(atrasadas.itens()).extracting(MeReadDtos.PayableItem::id)
                .containsExactly(ids.atrasadaAntiga(), ids.atrasadaStatus());
        assertThat(service.payables(C, "PAGO").itens().get(0).pagoEm()).isNotNull();
        assertThat(service.payables(C, "aberto").itens()).hasSize(2);
    }

    @Test
    void statementReconstroiSaldoAposCadaLancamento() {
        var s = service.statement(C, null, null);
        assertThat(s).hasSize(2);
        assertThat(s.get(0).tipo()).isEqualTo("CREDIT");
        assertThat(s.get(0).saldoApos()).isEqualByComparingTo("1000");
        assertThat(s.get(0).descricao()).isEqualTo("Crédito em conta");
        assertThat(s.get(1).id()).isEqualTo(ids.debito());
        assertThat(s.get(1).saldoApos()).isEqualByComparingTo("400");
        assertThat(s.get(1).descricao()).isEqualTo("PIX para Fornecedor Alfa · Insumos");

        var tudo = service.statement(C, HOJE.minusDays(60), HOJE);
        assertThat(tudo).hasSize(3);
        assertThat(tudo.get(2).saldoApos()).isEqualByComparingTo("500");
        assertThat(service.statement(7999L, null, null)).isEmpty();
    }

    @Test
    void inventoryCalculaCoberturaEParado() {
        var i = service.inventory(C);
        assertThat(i.itens()).hasSize(2);
        assertThat(i.itens().get(0).coberturaDias()).isEqualTo(30);
        assertThat(i.itens().get(0).abaixoMinimo()).isTrue();
        assertThat(i.itens().get(1).coberturaDias()).isEqualTo(40);
        assertThat(i.itens().get(1).parado()).isTrue();
        assertThat(i.valorTotal()).isEqualByComparingTo("250");
        assertThat(i.valorParado()).isEqualByComparingTo("200");
        assertThat(i.abaixoMinimo()).isEqualTo(1);
        assertThat(i.coberturaMediaDias()).isEqualByComparingTo("35.0");
        assertThat(service.inventory(7999L).coberturaMediaDias()).isEqualByComparingTo("0");
    }

    @Test
    void coberturaSemVendaOuPrecoUsaGiro() {
        var item = new com.erpbanking.transactions.domain.InventoryItem();
        item.setQuantidade(10);
        item.setGiroDias(45);
        item.setPrecoVenda(bd("0"));
        assertThat(PortalReadService.coberturaDias(item, List.of())).isEqualTo(45);
        var venda = new com.erpbanking.transactions.domain.Sale();
        venda.setValor(bd("10"));
        assertThat(PortalReadService.coberturaDias(item, List.of(venda))).isEqualTo(45);
        item.setPrecoVenda(bd("5"));
        venda.setValor(bd("0"));
        assertThat(PortalReadService.coberturaDias(item, List.of(venda))).isEqualTo(45);
    }

    @Test
    void pendenciasOrdenadasComIdsEstaveis() {
        List<Pendencia> p = pendencias.pendencias(C);
        assertThat(p).extracting(Pendencia::id).containsExactly(
                "payable-" + ids.atrasadaAntiga(), "payable-" + ids.atrasadaStatus(), "payable-" + ids.venceHoje(),
                "credit-request-" + ids.creditRevisao(), "inventory-low-" + ids.itemBaixo(),
                "inventory-idle-" + ids.itemParado());
        assertThat(p).extracting(Pendencia::tipo).containsExactly("CONTA_ATRASADA", "CONTA_ATRASADA", "VENCE_HOJE",
                "PROPOSTA_CREDITO", "ESTOQUE_BAIXO", "ESTOQUE_PARADO");

        Pendencia antiga = p.get(0);
        assertThat(antiga.titulo()).isEqualTo("Conta atrasada: Distribuidora Beta");
        assertThat(antiga.descricao()).contains("24/10/2026", "há 10 dias", "R$ 23,33", "R$ 1.023,33");
        assertThat(antiga.acao().rota()).isEqualTo("/app/pagamentos?conta=" + ids.atrasadaAntiga());
        assertThat(p.get(4).acao().rota()).startsWith("/app/estoque?sku=").endsWith("&movimentar=1");
        assertThat(p.get(4).acao().label()).isEqualTo("Repor estoque");
        assertThat(p.get(5).acao().rota()).startsWith("/app/estoque?sku=").doesNotContain("movimentar");
        assertThat(antiga.acao().credito().produto()).isEqualTo("CAPITAL_DE_GIRO");
        assertThat(antiga.acao().credito().valor()).isEqualByComparingTo("23.33");
        assertThat(p.get(1).acao().credito()).isNull();
        assertThat(p.get(3).acao().rota()).isEqualTo("/app/credito");
        assertThat(p.get(3).data()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(p.get(4).acao().credito().produto()).isEqualTo("FINANCIAMENTO_ESTOQUE");
        assertThat(p.get(4).valor()).isEqualByComparingTo("50");
        assertThat(p.get(5).valor()).isEqualByComparingTo("200");
        assertThat(p.get(5).data()).isEqualTo(HOJE.minusDays(95));
    }

    @Test
    void pendenciaSaldoInsuficienteEVenceHojeComCredito() {
        Long c = 7102L;
        f.account(c, "100.00");
        f.payable(c, "Energia", HOJE, "150.00", PayableStatus.ABERTO);
        f.payable(c, "Folha", HOJE.plusDays(3), "350.00", PayableStatus.AGENDADO);
        f.payable(c, "Contador", HOJE.minusDays(1), "10.00", PayableStatus.ABERTO);
        f.item(c, "SKU-Z", "Sem venda", 5, 1, "1.00", "2.00", 10, null);
        List<Pendencia> p = pendencias.pendencias(c);
        assertThat(p).extracting(Pendencia::tipo).containsExactly("CONTA_ATRASADA", "SALDO_INSUFICIENTE", "VENCE_HOJE");
        assertThat(p.get(0).descricao()).contains("há 1 dia)");
        Pendencia saldo = p.get(1);
        assertThat(saldo.id()).isEqualTo("saldo-insuficiente");
        assertThat(saldo.valor()).isEqualByComparingTo("400");
        assertThat(saldo.acao().credito().valor()).isEqualByComparingTo("400");
        assertThat(p.get(2).acao().credito().valor()).isEqualByComparingTo("50");
    }
}
