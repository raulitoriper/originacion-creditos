package com.rriveros.origination.domain.service;

import com.rriveros.origination.domain.model.ApplicationStatus;
import com.rriveros.origination.domain.model.BureauReport;
import com.rriveros.origination.domain.model.CreditApplication;
import com.rriveros.origination.domain.port.in.ScreenApplicantUseCase;
import com.rriveros.origination.domain.port.out.CreditApplicationRepository;
import com.rriveros.origination.domain.port.out.CreditBureauGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScreenApplicantService implements ScreenApplicantUseCase {

    private final CreditApplicationRepository repository;
    private final CreditBureauGateway bureau;

    public ScreenApplicantService(CreditApplicationRepository repository, CreditBureauGateway bureau) {
        this.repository = repository;
        this.bureau = bureau;
    }

    /**
     * Idempotente por diseno. Zeebe garantiza <em>at least once</em>: si el job worker completa el
     * job y muere antes de que el broker registre el ack, el mismo job vuelve. Si esta solicitud ya
     * fue evaluada devolvemos el resultado guardado en vez de reventar contra la invariante del
     * agregado.
     */
    @Override
    @Transactional
    public ScreeningResult screen(String applicationId) {
        CreditApplication application = repository.getById(applicationId);

        if (application.status() != ApplicationStatus.SUBMITTED) {
            return resultOf(application);
        }

        BureauReport report = bureau.fetchReport(application.applicantDocument());
        application.recordScreening(report);
        repository.save(application);

        return resultOf(application);
    }

    private ScreeningResult resultOf(CreditApplication application) {
        return new ScreeningResult(
                application.bureauScore(),
                Boolean.TRUE.equals(application.hasActiveDefaults()),
                application.installmentToIncomeRatio());
    }
}
