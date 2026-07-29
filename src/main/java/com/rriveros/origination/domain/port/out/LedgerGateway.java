package com.rriveros.origination.domain.port.out;

import java.math.BigDecimal;

/**
 * Puerto de salida: sistema contable / core bancario.
 *
 * <p>Cada operacion tiene su inversa explicita porque el proceso las usa como pareja
 * accion/compensacion. Un puerto que solo sabe avanzar no sirve para una saga.
 */
public interface LedgerGateway {

    /** @return identificador de la reserva, necesario para poder liberarla despues */
    String reserveFunds(String applicationId, BigDecimal amount);

    /** Inversa de {@link #reserveFunds}. Debe ser idempotente. */
    void releaseFunds(String reservationId);

    /**
     * @return identificador del desembolso
     * @throws DisbursementRejectedException si el core rechaza la operacion
     */
    String disburse(String applicationId, BigDecimal amount);

    /** El core rechazo el desembolso. Se traduce a un BPMN error, no a un reintento. */
    class DisbursementRejectedException extends RuntimeException {
        public DisbursementRejectedException(String message) {
            super(message);
        }
    }
}
