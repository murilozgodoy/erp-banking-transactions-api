package com.erpbanking.transactions.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Pagamento (PIX/boleto/TED) feito pelo portal; o efetivado gera uma transaction DEBIT. */
@Entity
@Table(name = "payments", indexes = @Index(name = "idx_payments_customer", columnList = "customer_id"))
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false)
    private PaymentType tipo;

    @Column(name = "destinatario", nullable = false)
    private String destinatario;

    @Column(name = "valor", nullable = false, precision = 19, scale = 2)
    private BigDecimal valor;

    @Column(name = "descricao")
    private String descricao;

    @Column(name = "payable_id")
    private Long payableId;

    @Column(name = "agendado_para")
    private LocalDate agendadoPara;

    @Column(name = "codigo_barras")
    private String codigoBarras;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    @Column(name = "autenticacao", nullable = false)
    private String autenticacao;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm = Instant.now();

    /** Auditoria: {@code sub} e nome do membro que fez o pagamento (nulos nos pagamentos antigos). */
    @Column(name = "created_by_sub")
    private String createdBySub;

    @Column(name = "created_by_nome")
    private String createdByNome;

    /** Quem aprovou o pagamento PENDENTE_APROVACAO (nulos nos que não passaram por aprovação). */
    @Column(name = "approved_by_sub")
    private String approvedBySub;

    @Column(name = "approved_by_nome")
    private String approvedByNome;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** Quem rejeitou o pagamento PENDENTE_APROVACAO, quando e por quê (motivo opcional). */
    @Column(name = "rejected_by_sub")
    private String rejectedBySub;

    @Column(name = "rejected_by_nome")
    private String rejectedByNome;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "motivo_rejeicao")
    private String motivoRejeicao;

    public Payment() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public PaymentType getTipo() { return tipo; }
    public void setTipo(PaymentType tipo) { this.tipo = tipo; }
    public String getDestinatario() { return destinatario; }
    public void setDestinatario(String destinatario) { this.destinatario = destinatario; }
    public BigDecimal getValor() { return valor; }
    public void setValor(BigDecimal valor) { this.valor = valor; }
    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }
    public Long getPayableId() { return payableId; }
    public void setPayableId(Long payableId) { this.payableId = payableId; }
    public LocalDate getAgendadoPara() { return agendadoPara; }
    public void setAgendadoPara(LocalDate agendadoPara) { this.agendadoPara = agendadoPara; }
    public String getCodigoBarras() { return codigoBarras; }
    public void setCodigoBarras(String codigoBarras) { this.codigoBarras = codigoBarras; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public String getAutenticacao() { return autenticacao; }
    public void setAutenticacao(String autenticacao) { this.autenticacao = autenticacao; }
    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }
    public Instant getCriadoEm() { return criadoEm; }
    public void setCriadoEm(Instant criadoEm) { this.criadoEm = criadoEm; }
    public String getCreatedBySub() { return createdBySub; }
    public void setCreatedBySub(String createdBySub) { this.createdBySub = createdBySub; }
    public String getCreatedByNome() { return createdByNome; }
    public void setCreatedByNome(String createdByNome) { this.createdByNome = createdByNome; }
    public String getApprovedBySub() { return approvedBySub; }
    public void setApprovedBySub(String approvedBySub) { this.approvedBySub = approvedBySub; }
    public String getApprovedByNome() { return approvedByNome; }
    public void setApprovedByNome(String approvedByNome) { this.approvedByNome = approvedByNome; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public String getRejectedBySub() { return rejectedBySub; }
    public void setRejectedBySub(String rejectedBySub) { this.rejectedBySub = rejectedBySub; }
    public String getRejectedByNome() { return rejectedByNome; }
    public void setRejectedByNome(String rejectedByNome) { this.rejectedByNome = rejectedByNome; }
    public Instant getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(Instant rejectedAt) { this.rejectedAt = rejectedAt; }
    public String getMotivoRejeicao() { return motivoRejeicao; }
    public void setMotivoRejeicao(String motivoRejeicao) { this.motivoRejeicao = motivoRejeicao; }
}
