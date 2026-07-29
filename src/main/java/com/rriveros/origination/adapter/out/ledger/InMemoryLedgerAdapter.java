package com.rriveros.origination.adapter.out.ledger;

import com.rriveros.origination.domain.port.out.LedgerGateway;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ledger en memoria que reemplaza al core bancario.
 *
 * <p>Rechaza los desembolsos por encima de {@code origination.ledger.disbursement-limit}. Es el
 * gatillo para ver la compensacion funcionando de punta a punta sin tener que romper nada a mano.
 */
@Component
public class InMemoryLedgerAdapter implements LedgerGateway {

    private static final Logger log = LoggerFactory.getLogger(InMemoryLedgerAdapter.class);

    private final Map<String, BigDecimal> reservations = new ConcurrentHashMap<>();
    private final BigDecimal disbursementLimit;

    public InMemoryLedgerAdapter(@Value("${origination.ledger.disbursement-limit}") BigDecimal disbursementLimit) {
        this.disbursementLimit = disbursementLimit;
    }

    @Override
    public String reserveFunds(String applicationId, BigDecimal amount) {
        String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        reservations.put(reservationId, amount);
        log.info("Fondos reservados: reservationId={} applicationId={} monto={}",
                reservationId, applicationId, amount);
        return reservationId;
    }

    @Override
    public void releaseFunds(String reservationId) {
        BigDecimal released = reservations.remove(reservationId);
        // Idempotente: si ya no esta, la compensacion ya corrio. No es un error.
        log.info("Fondos liberados: reservationId={} monto={}", reservationId, released);
    }

    @Override
    public String disburse(String applicationId, BigDecimal amount) {
        if (amount.compareTo(disbursementLimit) > 0) {
            throw new DisbursementRejectedException(
                    "El monto %s supera el limite de desembolso automatico %s"
                            .formatted(amount, disbursementLimit));
        }
        String disbursementId = "DIS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("Desembolso acreditado: disbursementId={} applicationId={} monto={}",
                disbursementId, applicationId, amount);
        return disbursementId;
    }
}
