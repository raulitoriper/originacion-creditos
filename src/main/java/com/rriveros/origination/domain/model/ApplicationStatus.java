package com.rriveros.origination.domain.model;

/**
 * Ciclo de vida de una solicitud de credito.
 *
 * <p>Este enum es la unica fuente de verdad sobre el estado del negocio. El motor de procesos
 * sabe en que <em>paso</em> esta la instancia; el dominio sabe en que <em>estado</em> esta la
 * solicitud. No son lo mismo y no deben mezclarse.
 */
public enum ApplicationStatus {

    /** Recibida, todavia sin datos de bureau. */
    SUBMITTED,

    /** Bureau consultado y politica de riesgo evaluada. */
    SCREENED,

    /** Escalada a un supervisor por incumplimiento del SLA de revision. */
    PENDING_REVIEW,

    /** Fondos comprometidos en el ledger, pendiente de desembolso. */
    FUNDS_RESERVED,

    /** Desembolsada al solicitante. */
    DISBURSED,

    /** Rechazada por politica de riesgo o por decision del analista. */
    REJECTED,

    /** Los fondos reservados fueron liberados por compensacion. */
    REVERTED
}
