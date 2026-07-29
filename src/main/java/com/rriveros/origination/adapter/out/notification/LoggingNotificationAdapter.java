package com.rriveros.origination.adapter.out.notification;

import com.rriveros.origination.domain.model.CreditApplication;
import com.rriveros.origination.domain.port.out.NotificationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Sustituto del proveedor de mail/SMS. Loguea y listo. */
@Component
public class LoggingNotificationAdapter implements NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationAdapter.class);

    @Override
    public void notifyApproval(CreditApplication application) {
        log.info("[APROBACION] {} - credito de {} desembolsado (disbursementId={})",
                application.applicantName(), application.requestedAmount(), application.disbursementId());
    }

    @Override
    public void notifyRejection(CreditApplication application) {
        log.info("[RECHAZO] {} - motivo: {}", application.applicantName(), application.resolutionReason());
    }

    @Override
    public void notifySupervisor(CreditApplication application) {
        log.warn("[SLA VENCIDO] solicitud {} sin resolver, score={}. Escalada al supervisor.",
                application.id(), application.bureauScore());
    }
}
