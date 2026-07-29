package com.rriveros.origination.domain.model;

/**
 * Resultado de la tabla de decision DMN {@code credit_scoring}.
 *
 * <p>Los nombres coinciden exactamente con las salidas del DMN y con las condiciones FEEL del
 * gateway {@code Gateway_RiskDecision}. Si cambia uno, cambian los tres.
 */
public enum RiskDecision {
    APPROVED,
    MANUAL_REVIEW,
    REJECTED
}
