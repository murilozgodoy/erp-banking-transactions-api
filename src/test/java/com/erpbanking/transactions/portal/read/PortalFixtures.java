package com.erpbanking.transactions.portal.read;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.CreditRequest;
import com.erpbanking.transactions.domain.CreditStatus;
import com.erpbanking.transactions.domain.InventoryItem;
import com.erpbanking.transactions.domain.Payable;
import com.erpbanking.transactions.domain.PayableCategory;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.domain.Payment;
import com.erpbanking.transactions.domain.PaymentMethod;
import com.erpbanking.transactions.domain.PaymentStatus;
import com.erpbanking.transactions.domain.PaymentType;
import com.erpbanking.transactions.domain.Receivable;
import com.erpbanking.transactions.domain.ReceivableStatus;
import com.erpbanking.transactions.domain.Sale;
import com.erpbanking.transactions.domain.Transaction;
import com.erpbanking.transactions.domain.TransactionType;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.CreditRequestRepository;
import com.erpbanking.transactions.repository.InventoryItemRepository;
import com.erpbanking.transactions.repository.PayableRepository;
import com.erpbanking.transactions.repository.PaymentRepository;
import com.erpbanking.transactions.repository.ReceivableRepository;
import com.erpbanking.transactions.repository.SaleRepository;
import com.erpbanking.transactions.repository.TransactionRepository;

/** Monta no H2 o cenário padrão dos testes do portal, relativo a uma data "hoje". */
@Component
class PortalFixtures {

    @Autowired AccountRepository accounts;
    @Autowired TransactionRepository transactions;
    @Autowired SaleRepository sales;
    @Autowired ReceivableRepository receivables;
    @Autowired PayableRepository payables;
    @Autowired InventoryItemRepository inventory;
    @Autowired PaymentRepository payments;
    @Autowired CreditRequestRepository creditRequests;

    /** Ids gerados no cenário padrão. */
    record Ids(Long account, Long atrasadaAntiga, Long atrasadaStatus, Long venceHoje, Long itemBaixo,
               Long itemParado, Long creditRevisao, Long debito) {}

    static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    Account account(Long customerId, String saldo) {
        return accounts.save(new Account(customerId, bd(saldo)));
    }

    Transaction tx(Long accountId, TransactionType tipo, String valor, Instant ts) {
        Transaction t = new Transaction(accountId, tipo, bd(valor));
        t.setTimestamp(ts);
        return transactions.save(t);
    }

    Sale sale(Long c, LocalDate data, String valor, PaymentMethod forma, String canal, String sku) {
        Sale s = new Sale();
        s.setCustomerId(c);
        s.setData(data);
        s.setValor(bd(valor));
        s.setFormaPagamento(forma);
        s.setCanal(canal);
        s.setParcelas(1);
        s.setProdutoSku(sku);
        return sales.save(s);
    }

    Receivable receivable(Long c, LocalDate venc, String bruto, String liquido, ReceivableStatus status) {
        Receivable r = new Receivable();
        r.setCustomerId(c);
        r.setVencimento(venc);
        r.setValorBruto(bd(bruto));
        r.setTaxaAdquirente(bd(bruto).subtract(bd(liquido)));
        r.setValorLiquido(bd(liquido));
        r.setAdquirente("Cielo");
        r.setStatus(status);
        return receivables.save(r);
    }

    Payable payable(Long c, String fornecedor, LocalDate venc, String valor, PayableStatus status) {
        Payable p = new Payable();
        p.setCustomerId(c);
        p.setFornecedor(fornecedor);
        p.setCategoria(PayableCategory.FORNECEDOR);
        p.setVencimento(venc);
        p.setValor(bd(valor));
        p.setStatus(status);
        p.setCodigoBarras("34191790010104351004791020150008291070026000");
        if (status == PayableStatus.PAGO) {
            p.setPagoEm(Instant.parse("2026-11-01T12:00:00Z"));
        }
        return payables.save(p);
    }

    InventoryItem item(Long c, String sku, String nome, int qtd, int minimo, String custo, String preco, int giro,
                       LocalDate ultimaVenda) {
        InventoryItem i = new InventoryItem();
        i.setCustomerId(c);
        i.setSku(sku);
        i.setNome(nome);
        i.setQuantidade(qtd);
        i.setMinimo(minimo);
        i.setCustoUnitario(bd(custo));
        i.setPrecoVenda(bd(preco));
        i.setGiroDias(giro);
        i.setUltimaVenda(ultimaVenda);
        return inventory.save(i);
    }

    CreditRequest credit(Long c, String valor, CreditStatus status) {
        CreditRequest r = new CreditRequest();
        r.setCustomerId(c);
        r.setValorSolicitado(bd(valor));
        r.setPrazoMeses(12);
        r.setStatus(status);
        r.setCriadoEm(Instant.parse("2026-10-30T15:00:00Z"));
        return creditRequests.save(r);
    }

    /**
     * Cenário padrão (hoje = {@code h}): saldo 1000; vendas de 400 nos últimos 30 dias (100 PIX + 300 cartão) e
     * 200 nos 30 anteriores; recebíveis previstos de 290 (em 5 dias) e 48 (em 100 dias); contas atrasadas de 1000
     * e 200, uma de 150 vencendo hoje, 400 agendada em 5 dias, 999 em 40 dias e uma paga; estoque com um item
     * abaixo do mínimo e um parado; uma proposta em revisão.
     */
    Ids cenario(Long c, LocalDate h) {
        Account a = account(c, "1000.00");
        tx(a.getId(), TransactionType.CREDIT, "500.00", h.minusDays(33).atStartOfDay(PortalCalculations.ZONE).toInstant());
        Transaction debito = tx(a.getId(), TransactionType.DEBIT, "100.00",
                h.minusDays(14).atTime(10, 0).atZone(PortalCalculations.ZONE).toInstant());
        tx(a.getId(), TransactionType.CREDIT, "600.00", h.minusDays(1).atTime(9, 0).atZone(PortalCalculations.ZONE).toInstant());
        Payment pg = new Payment();
        pg.setCustomerId(c);
        pg.setTipo(PaymentType.PIX);
        pg.setDestinatario("Fornecedor Alfa");
        pg.setValor(bd("100.00"));
        pg.setDescricao("Insumos");
        pg.setStatus(PaymentStatus.EFETIVADO);
        pg.setAutenticacao("AUT-1");
        pg.setTransactionId(debito.getId());
        payments.save(pg);

        sale(c, h, "100.00", PaymentMethod.PIX, "Loja física", "SKU-A");
        sale(c, h.minusDays(1), "300.00", PaymentMethod.CARTAO_CREDITO, "E-commerce", "SKU-B");
        sale(c, h.minusDays(40), "200.00", PaymentMethod.PIX, "Loja física", "SKU-A");
        sale(c, h.minusDays(70), "999.00", PaymentMethod.BOLETO, "Loja física", null);

        receivable(c, h.plusDays(5), "300.00", "290.00", ReceivableStatus.PREVISTO);
        receivable(c, h.minusDays(2), "100.00", "97.00", ReceivableStatus.RECEBIDO);
        receivable(c, h.plusDays(100), "50.00", "48.00", ReceivableStatus.PREVISTO);

        Payable antiga = payable(c, "Distribuidora Beta", h.minusDays(10), "1000.00", PayableStatus.ABERTO);
        Payable atrasada = payable(c, "Prefeitura", h.minusDays(3), "200.00", PayableStatus.ATRASADO);
        Payable hoje = payable(c, "Energia", h, "150.00", PayableStatus.ABERTO);
        payable(c, "Folha", h.plusDays(5), "400.00", PayableStatus.AGENDADO);
        payable(c, "Aluguel", h.minusDays(1), "500.00", PayableStatus.PAGO);
        payable(c, "Contador", h.plusDays(40), "999.00", PayableStatus.ABERTO);

        InventoryItem baixo = item(c, "SKU-A", "Camiseta", 10, 20, "5.00", "10.00", 30, h);
        InventoryItem parado = item(c, "SKU-B", "Jaqueta", 100, 10, "2.00", "4.00", 120, h.minusDays(95));

        CreditRequest revisao = credit(c, "5000.00", CreditStatus.REVISAO);
        credit(c, "1000.00", CreditStatus.APROVADO);
        return new Ids(a.getId(), antiga.getId(), atrasada.getId(), hoje.getId(), baixo.getId(), parado.getId(),
                revisao.getId(), debito.getId());
    }
}
