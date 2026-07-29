package com.rriveros.origination.domain.service;

import com.rriveros.origination.domain.model.CreditApplication;
import com.rriveros.origination.domain.model.RiskDecision;
import com.rriveros.origination.domain.port.in.FundApplicationUseCase;
import com.rriveros.origination.domain.port.out.CreditApplicationRepository;
import com.rriveros.origination.domain.port.out.LedgerGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FundApplicationService implements FundApplicationUseCase {

    private final CreditApplicationRepository repository;
    private final LedgerGateway ledger;

    public FundApplicationService(CreditApplicationRepository repository, LedgerGateway ledger) {
        this.repository = repository;
        this.ledger = ledger;
    }

    @Override
    @Transactional
    public String reserveFunds(String applicationId, RiskDecision decision) {
        CreditApplication application = repository.getById(applicationId);

        if (application.reservationId() != null) {
            return application.reservationId();
        }

        if (application.riskDecision() == null) {
            application.recordRiskDecision(decision);
        }

        String reservationId = ledger.reserveFunds(application.id(), application.requestedAmount());
        application.reserveFunds(reservationId);
        repository.save(application);

        return reservationId;
    }

    /**
     * Deja subir {@link LedgerGateway.DisbursementRejectedException} sin envolverla: el adaptador de
     * entrada es el que decide como se traduce al proceso (error de BPMN vs reintento tecnico). Un
     * servicio de dominio que sepa que existe {@code ZeebeBpmnError} ya dejo de ser dominio.
     */
    @Override
    @Transactional
    public String disburse(String applicationId) {
        CreditApplication application = repository.getById(applicationId);

        if (application.disbursementId() != null) {
            return application.disbursementId();
        }

        String disbursementId = ledger.disburse(application.id(), application.requestedAmount());
        application.markDisbursed(disbursementId);
        repository.save(application);

        return disbursementId;
    }

    @Override
    @Transactional
    public void releaseFunds(String applicationId, String reason) {
        CreditApplication application = repository.getById(applicationId);

        String reservationId = application.reservationId();
        if (reservationId != null) {
            ledger.releaseFunds(reservationId);
        }

        application.releaseFunds(reason);
        repository.save(application);
    }
}
