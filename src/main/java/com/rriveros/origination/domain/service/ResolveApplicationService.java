package com.rriveros.origination.domain.service;

import com.rriveros.origination.domain.model.ApplicationStatus;
import com.rriveros.origination.domain.model.CreditApplication;
import com.rriveros.origination.domain.model.RiskDecision;
import com.rriveros.origination.domain.port.in.ResolveApplicationUseCase;
import com.rriveros.origination.domain.port.out.CreditApplicationRepository;
import com.rriveros.origination.domain.port.out.NotificationGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResolveApplicationService implements ResolveApplicationUseCase {

    private final CreditApplicationRepository repository;
    private final NotificationGateway notifications;

    public ResolveApplicationService(CreditApplicationRepository repository,
                                     NotificationGateway notifications) {
        this.repository = repository;
        this.notifications = notifications;
    }

    @Override
    @Transactional
    public void notifyApproval(String applicationId) {
        notifications.notifyApproval(repository.getById(applicationId));
    }

    @Override
    @Transactional
    public void rejectAndNotify(String applicationId, RiskDecision decision, String reason) {
        CreditApplication application = repository.getById(applicationId);

        if (application.status() == ApplicationStatus.REJECTED) {
            return;
        }

        if (application.riskDecision() == null && decision != null) {
            application.recordRiskDecision(decision);
        }
        application.reject(reason);
        repository.save(application);

        notifications.notifyRejection(application);
    }

    @Override
    @Transactional
    public void escalateReview(String applicationId) {
        CreditApplication application = repository.getById(applicationId);
        application.escalateReview();
        repository.save(application);

        notifications.notifySupervisor(application);
    }
}
