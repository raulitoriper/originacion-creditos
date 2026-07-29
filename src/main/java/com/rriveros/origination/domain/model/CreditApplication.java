package com.rriveros.origination.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Raiz de agregado: una solicitud de credito y las transiciones que puede sufrir.
 *
 * <p>Cero dependencias de Spring, de JPA y de Camunda. Esta clase no sabe que existe un motor de
 * procesos: los job workers traducen pasos del BPMN a estas operaciones, nunca al reves. Toda
 * transicion invalida falla aca y no en el adaptador.
 */
public class CreditApplication {

    private static final Set<ApplicationStatus> REJECTABLE =
            EnumSet.of(ApplicationStatus.SUBMITTED, ApplicationStatus.SCREENED, ApplicationStatus.PENDING_REVIEW);

    private final String id;
    private final String applicantDocument;
    private final String applicantName;
    private final BigDecimal monthlyIncome;
    private final BigDecimal requestedAmount;
    private final int termMonths;

    private ApplicationStatus status;
    private Integer bureauScore;
    private Boolean hasActiveDefaults;
    private RiskDecision riskDecision;
    private String reservationId;
    private String disbursementId;
    private String resolutionReason;
    private Long processInstanceKey;

    /** Constructor de rehidratacion. Lo usa el adaptador de persistencia, nadie mas. */
    public CreditApplication(String id, String applicantDocument, String applicantName,
                             BigDecimal monthlyIncome, BigDecimal requestedAmount, int termMonths,
                             ApplicationStatus status, Integer bureauScore, Boolean hasActiveDefaults,
                             RiskDecision riskDecision, String reservationId, String disbursementId,
                             String resolutionReason, Long processInstanceKey) {
        this.id = Objects.requireNonNull(id, "id");
        this.applicantDocument = Objects.requireNonNull(applicantDocument, "applicantDocument");
        this.applicantName = Objects.requireNonNull(applicantName, "applicantName");
        this.monthlyIncome = Objects.requireNonNull(monthlyIncome, "monthlyIncome");
        this.requestedAmount = Objects.requireNonNull(requestedAmount, "requestedAmount");
        this.termMonths = termMonths;
        this.status = Objects.requireNonNull(status, "status");
        this.bureauScore = bureauScore;
        this.hasActiveDefaults = hasActiveDefaults;
        this.riskDecision = riskDecision;
        this.reservationId = reservationId;
        this.disbursementId = disbursementId;
        this.resolutionReason = resolutionReason;
        this.processInstanceKey = processInstanceKey;
    }

    /** Fabrica de creacion: valida las invariantes de negocio de una solicitud nueva. */
    public static CreditApplication submit(String applicantDocument, String applicantName,
                                           BigDecimal monthlyIncome, BigDecimal requestedAmount,
                                           int termMonths) {
        if (monthlyIncome == null || monthlyIncome.signum() <= 0) {
            throw new IllegalArgumentException("El ingreso mensual debe ser mayor a cero");
        }
        if (requestedAmount == null || requestedAmount.signum() <= 0) {
            throw new IllegalArgumentException("El monto solicitado debe ser mayor a cero");
        }
        if (termMonths < 3 || termMonths > 120) {
            throw new IllegalArgumentException("El plazo debe estar entre 3 y 120 meses: " + termMonths);
        }
        return new CreditApplication(UUID.randomUUID().toString(), applicantDocument, applicantName,
                monthlyIncome, requestedAmount, termMonths, ApplicationStatus.SUBMITTED,
                null, null, null, null, null, null, null);
    }

    // ---------- reglas derivadas ----------

    /** Cuota nominal sin interes. Alcanza para alimentar la politica de riesgo del DMN. */
    public BigDecimal monthlyInstallment() {
        return requestedAmount.divide(BigDecimal.valueOf(termMonths), 2, RoundingMode.HALF_UP);
    }

    /** Relacion cuota/ingreso: la variable que mas pesa en la tabla DMN. */
    public BigDecimal installmentToIncomeRatio() {
        return monthlyInstallment().divide(monthlyIncome, 4, RoundingMode.HALF_UP);
    }

    // ---------- transiciones ----------

    public void attachProcessInstance(long key) {
        this.processInstanceKey = key;
    }

    public void recordScreening(BureauReport report) {
        requireStatus("registrar el informe de bureau", ApplicationStatus.SUBMITTED);
        this.bureauScore = report.score();
        this.hasActiveDefaults = report.hasActiveDefaults();
        this.status = ApplicationStatus.SCREENED;
    }

    /**
     * Anota la decision que devolvio el DMN. No es una transicion de estado: la solicitud sigue
     * donde estaba y quien avanza el estado es la operacion que corresponda (reservar o rechazar).
     */
    public void recordRiskDecision(RiskDecision decision) {
        requireStatus("registrar la decision de riesgo",
                ApplicationStatus.SCREENED, ApplicationStatus.PENDING_REVIEW);
        this.riskDecision = Objects.requireNonNull(decision, "decision");
    }

    public void escalateReview() {
        requireStatus("escalar la revision", ApplicationStatus.SCREENED, ApplicationStatus.PENDING_REVIEW);
        this.status = ApplicationStatus.PENDING_REVIEW;
    }

    public void reserveFunds(String reservationId) {
        requireStatus("reservar fondos", ApplicationStatus.SCREENED, ApplicationStatus.PENDING_REVIEW);
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        this.status = ApplicationStatus.FUNDS_RESERVED;
    }

    public void markDisbursed(String disbursementId) {
        requireStatus("desembolsar", ApplicationStatus.FUNDS_RESERVED);
        this.disbursementId = Objects.requireNonNull(disbursementId, "disbursementId");
        this.status = ApplicationStatus.DISBURSED;
    }

    /**
     * Compensacion de {@link #reserveFunds(String)}. Es idempotente a proposito: Zeebe puede
     * reintentar el job de compensacion y el resultado tiene que ser el mismo.
     */
    public void releaseFunds(String reason) {
        if (status == ApplicationStatus.REVERTED) {
            return;
        }
        requireStatus("liberar fondos", ApplicationStatus.FUNDS_RESERVED, ApplicationStatus.DISBURSED);
        this.reservationId = null;
        this.disbursementId = null;
        this.resolutionReason = reason;
        this.status = ApplicationStatus.REVERTED;
    }

    public void reject(String reason) {
        if (status == ApplicationStatus.REJECTED) {
            return;
        }
        if (!REJECTABLE.contains(status)) {
            throw new InvalidApplicationStateException(id, "rechazar", status);
        }
        this.resolutionReason = reason;
        this.status = ApplicationStatus.REJECTED;
    }

    private void requireStatus(String action, ApplicationStatus... allowed) {
        for (ApplicationStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new InvalidApplicationStateException(id, action, status);
    }

    // ---------- accesores ----------

    public String id() { return id; }
    public String applicantDocument() { return applicantDocument; }
    public String applicantName() { return applicantName; }
    public BigDecimal monthlyIncome() { return monthlyIncome; }
    public BigDecimal requestedAmount() { return requestedAmount; }
    public int termMonths() { return termMonths; }
    public ApplicationStatus status() { return status; }
    public Integer bureauScore() { return bureauScore; }
    public Boolean hasActiveDefaults() { return hasActiveDefaults; }
    public RiskDecision riskDecision() { return riskDecision; }
    public String reservationId() { return reservationId; }
    public String disbursementId() { return disbursementId; }
    public String resolutionReason() { return resolutionReason; }
    public Long processInstanceKey() { return processInstanceKey; }
}
