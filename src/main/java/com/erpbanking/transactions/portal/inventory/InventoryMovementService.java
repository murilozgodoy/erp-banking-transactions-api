package com.erpbanking.transactions.portal.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erpbanking.transactions.domain.InventoryItem;
import com.erpbanking.transactions.domain.InventoryMovement;
import com.erpbanking.transactions.domain.InventoryMovementType;
import com.erpbanking.transactions.portal.CurrentCustomer;
import com.erpbanking.transactions.portal.PortalExceptions.EstoqueInsuficienteException;
import com.erpbanking.transactions.portal.PortalExceptions.FieldValidationException;
import com.erpbanking.transactions.portal.PortalExceptions.NotFoundException;
import com.erpbanking.transactions.portal.PortalMetrics;
import com.erpbanking.transactions.portal.inventory.InventoryDtos.MovementCreated;
import com.erpbanking.transactions.portal.inventory.InventoryDtos.MovementRequest;
import com.erpbanking.transactions.portal.inventory.InventoryDtos.MovementView;
import com.erpbanking.transactions.portal.read.PortalReadService;
import com.erpbanking.transactions.repository.InventoryItemRepository;
import com.erpbanking.transactions.repository.InventoryMovementRepository;

/**
 * Movimentação de estoque pelo portal: ENTRADA soma (e, com {@code custo_unitario}, recalcula o custo médio
 * ponderado), SAIDA subtrai (nunca abaixo de zero) e AJUSTE define a quantidade final. O item é travado
 * ({@code SELECT ... FOR UPDATE}) antes de ler a quantidade, então movimentações concorrentes são serializadas.
 * Os indicadores de {@code GET /me/inventory}, {@code /me/pendencias} e {@code /me/overview} são calculados na hora a
 * partir do item, então refletem a movimentação imediatamente.
 */
@Service
public class InventoryMovementService {

    public static final String ITEM_NAO_ENCONTRADO = "ITEM_NAO_ENCONTRADO";
    static final String MSG_NAO_ENCONTRADO = "Item de estoque não encontrado";

    private final InventoryItemRepository items;
    private final InventoryMovementRepository movements;
    private final PortalReadService reads;
    private final PortalMetrics metrics;
    private final Clock clock;

    @Autowired
    public InventoryMovementService(InventoryItemRepository items, InventoryMovementRepository movements,
                                    PortalReadService reads, PortalMetrics metrics) {
        this(items, movements, reads, metrics, Clock.systemUTC());
    }

    InventoryMovementService(InventoryItemRepository items, InventoryMovementRepository movements,
                             PortalReadService reads, PortalMetrics metrics, Clock clock) {
        this.items = items;
        this.movements = movements;
        this.reads = reads;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public MovementCreated move(CurrentCustomer customer, Long itemId, MovementRequest req) {
        InventoryMovementType tipo = req.tipo();
        int quantidade = validar(req);
        InventoryItem item = items.findByIdAndCustomerIdForUpdate(itemId, customer.customerId())
                .orElseThrow(InventoryMovementService::naoEncontrado);

        int anterior = item.getQuantidade();
        long finalLong = switch (tipo) {
            case ENTRADA -> (long) anterior + quantidade;
            case SAIDA -> (long) anterior - quantidade;
            case AJUSTE -> quantidade;
        };
        if (finalLong < 0) {
            throw new EstoqueInsuficienteException(anterior);
        }
        if (finalLong > Integer.MAX_VALUE) {
            throw new FieldValidationException("quantidade", "estoque resultante excede o máximo suportado");
        }
        int quantidadeFinal = (int) finalLong;
        BigDecimal custo = tipo == InventoryMovementType.ENTRADA ? req.custoUnitario() : null;
        if (custo != null) {
            item.setCustoUnitario(custoMedio(anterior, item.getCustoUnitario(), quantidade, custo));
        }
        item.setQuantidade(quantidadeFinal);
        items.save(item);

        InventoryMovement m = new InventoryMovement();
        m.setCustomerId(customer.customerId());
        m.setItemId(item.getId());
        m.setTipo(tipo);
        m.setQuantidade(quantidade);
        m.setQuantidadeAnterior(anterior);
        m.setQuantidadeFinal(quantidadeFinal);
        m.setCustoUnitario(custo == null ? null : custo.setScale(2, RoundingMode.UNNECESSARY));
        m.setMotivo(blankToNull(req.motivo()));
        m.setCreatedBySub(customer.sub());
        m.setCreatedByNome(customer.nome());
        m.setCreatedAt(Instant.now(clock));
        InventoryMovement saved = movements.save(m);
        metrics.inventoryMovement(tipo);
        return new MovementCreated(MovementView.of(saved), reads.inventoryRow(customer.customerId(), item));
    }

    @Transactional(readOnly = true)
    public List<MovementView> history(Long customerId, Long itemId) {
        items.findByIdAndCustomerId(itemId, customerId).orElseThrow(InventoryMovementService::naoEncontrado);
        return movements.findTop100ByCustomerIdAndItemIdOrderByCreatedAtDescIdDesc(customerId, itemId).stream()
                .map(MovementView::of).toList();
    }

    /**
     * Custo médio ponderado: (qtdAnterior·custoAnterior + q·custoNovo) / (qtdAnterior + q), HALF_EVEN, 2 casas.
     * {@code q} é sempre &gt; 0 numa ENTRADA, então o divisor nunca é zero.
     */
    static BigDecimal custoMedio(int qtdAnterior, BigDecimal custoAnterior, int q, BigDecimal custoNovo) {
        BigDecimal base = custoAnterior == null ? BigDecimal.ZERO : custoAnterior;
        BigDecimal total = base.multiply(BigDecimal.valueOf(qtdAnterior))
                .add(custoNovo.multiply(BigDecimal.valueOf(q)));
        return total.divide(BigDecimal.valueOf((long) qtdAnterior + q), 2, RoundingMode.HALF_EVEN);
    }

    /** Regras que dependem do tipo (o formato já foi validado pelo Bean Validation). */
    private static int validar(MovementRequest req) {
        int quantidade = req.quantidade().intValueExact();
        if (req.tipo() != InventoryMovementType.AJUSTE && quantidade <= 0) {
            throw new FieldValidationException("quantidade", "deve ser maior que 0");
        }
        if (req.custoUnitario() != null && req.tipo() != InventoryMovementType.ENTRADA) {
            throw new FieldValidationException("custo_unitario", "só é aceito em ENTRADA");
        }
        return quantidade;
    }

    private static NotFoundException naoEncontrado() {
        return new NotFoundException(ITEM_NAO_ENCONTRADO, MSG_NAO_ENCONTRADO);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
