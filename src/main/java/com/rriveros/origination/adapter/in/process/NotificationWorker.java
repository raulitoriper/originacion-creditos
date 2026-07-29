package com.rriveros.origination.adapter.in.process;

import com.rriveros.origination.domain.model.RiskDecision;
import com.rriveros.origination.domain.port.in.ResolveApplicationUseCase;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import org.springframework.stereotype.Component;

/**
 * Adaptador de entrada compartido por {@code Activity_NotifyApproval} y
 * {@code Activity_NotifyRejection}.
 *
 * <p>Las dos actividades usan el mismo {@code taskDefinition type}. Lo que las diferencia es un
 * {@code zeebe:ioMapping} que inyecta la variable local {@code template}. Un job type por variante
 * de mensaje se convierte en una lista interminable de workers casi identicos.
 */
@Component
public class NotificationWorker {

    private static final String APPROVAL = "APPROVAL";
    private static final String REJECTION = "REJECTION";

    private final ResolveApplicationUseCase resolveApplication;

    public NotificationWorker(ResolveApplicationUseCase resolveApplication) {
        this.resolveApplication = resolveApplication;
    }

    @JobWorker(type = "notify-applicant", fetchVariables = {"applicationId", "template", "riskDecision"})
    public void notifyApplicant(@Variable String applicationId,
                               @Variable String template,
                               @Variable String riskDecision) {
        switch (template) {
            case APPROVAL -> resolveApplication.notifyApproval(applicationId);
            case REJECTION -> {
                RiskDecision decision = riskDecision != null ? RiskDecision.valueOf(riskDecision) : null;
                resolveApplication.rejectAndNotify(applicationId, decision, reasonFor(decision));
            }
            default -> throw new IllegalArgumentException("Plantilla de notificacion desconocida: " + template);
        }
    }

    private String reasonFor(RiskDecision decision) {
        return decision == RiskDecision.REJECTED
                ? "Rechazo automatico por politica de riesgo"
                : "Rechazado por el analista de riesgo";
    }
}
