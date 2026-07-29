package com.rriveros.origination.domain.port.in;

import com.rriveros.origination.domain.model.RiskDecision;

/**
 * Puerto de entrada: el tramo de fondeo de la saga.
 *
 * <p>Las tres operaciones viven juntas porque forman una unidad: {@code reserveFunds} y
 * {@code disburse} avanzan, {@code releaseFunds} revierte. Separarlas en tres puertos escondería
 * que son la misma transaccion de negocio.
 */
public interface FundApplicationUseCase {

    /** @return el id de la reserva creada en el ledger */
    String reserveFunds(String applicationId, RiskDecision decision);

    /** @return el id del desembolso */
    String disburse(String applicationId);

    /** Compensacion de {@link #reserveFunds}. Idempotente. */
    void releaseFunds(String applicationId, String reason);
}
