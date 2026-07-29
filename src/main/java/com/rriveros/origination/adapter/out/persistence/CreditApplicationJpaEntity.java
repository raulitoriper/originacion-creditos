package com.rriveros.origination.adapter.out.persistence;

import com.rriveros.origination.domain.model.ApplicationStatus;
import com.rriveros.origination.domain.model.CreditApplication;
import com.rriveros.origination.domain.model.RiskDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Modelo de persistencia, separado del modelo de dominio a proposito.
 *
 * <p>Si el agregado lleva anotaciones de JPA, el dominio termina moldeado por lo que le conviene a
 * Hibernate: constructor vacio, setters publicos, invariantes imposibles de garantizar. El precio de
 * mantenerlos separados es este mapeo. Vale la pena.
 */
@Entity
@Table(name = "credit_application")
class CreditApplicationJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "applicant_document", nullable = false, length = 32)
    private String applicantDocument;

    @Column(name = "applicant_name", nullable = false, length = 160)
    private String applicantName;

    @Column(name = "monthly_income", nullable = false, precision = 15, scale = 2)
    private BigDecimal monthlyIncome;

    @Column(name = "requested_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal requestedAmount;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ApplicationStatus status;

    @Column(name = "bureau_score")
    private Integer bureauScore;

    @Column(name = "has_active_defaults")
    private Boolean hasActiveDefaults;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_decision", length = 32)
    private RiskDecision riskDecision;

    @Column(name = "reservation_id", length = 64)
    private String reservationId;

    @Column(name = "disbursement_id", length = 64)
    private String disbursementId;

    @Column(name = "resolution_reason")
    private String resolutionReason;

    @Column(name = "process_instance_key")
    private Long processInstanceKey;

    protected CreditApplicationJpaEntity() {
        // requerido por JPA
    }

    static CreditApplicationJpaEntity fromDomain(CreditApplication application) {
        CreditApplicationJpaEntity entity = new CreditApplicationJpaEntity();
        entity.id = application.id();
        entity.applicantDocument = application.applicantDocument();
        entity.applicantName = application.applicantName();
        entity.monthlyIncome = application.monthlyIncome();
        entity.requestedAmount = application.requestedAmount();
        entity.termMonths = application.termMonths();
        entity.status = application.status();
        entity.bureauScore = application.bureauScore();
        entity.hasActiveDefaults = application.hasActiveDefaults();
        entity.riskDecision = application.riskDecision();
        entity.reservationId = application.reservationId();
        entity.disbursementId = application.disbursementId();
        entity.resolutionReason = application.resolutionReason();
        entity.processInstanceKey = application.processInstanceKey();
        return entity;
    }

    CreditApplication toDomain() {
        return new CreditApplication(id, applicantDocument, applicantName, monthlyIncome,
                requestedAmount, termMonths, status, bureauScore, hasActiveDefaults, riskDecision,
                reservationId, disbursementId, resolutionReason, processInstanceKey);
    }
}
