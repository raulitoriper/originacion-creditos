package com.rriveros.origination.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Informe del bureau de credito para un documento de identidad.
 *
 * @param score            puntaje entre 0 y 1000
 * @param hasActiveDefaults si el solicitante tiene mora vigente
 * @param outstandingDebt  deuda total informada por el bureau
 */
public record BureauReport(int score, boolean hasActiveDefaults, BigDecimal outstandingDebt) {

    public BureauReport {
        if (score < 0 || score > 1000) {
            throw new IllegalArgumentException("El score de bureau debe estar entre 0 y 1000: " + score);
        }
        Objects.requireNonNull(outstandingDebt, "outstandingDebt");
        if (outstandingDebt.signum() < 0) {
            throw new IllegalArgumentException("La deuda informada no puede ser negativa");
        }
    }
}
