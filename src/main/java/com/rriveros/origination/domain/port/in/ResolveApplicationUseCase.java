package com.rriveros.origination.domain.port.in;

import com.rriveros.origination.domain.model.RiskDecision;

/** Puerto de entrada: cierre de la solicitud y avisos. */
public interface ResolveApplicationUseCase {

    void notifyApproval(String applicationId);

    void rejectAndNotify(String applicationId, RiskDecision decision, String reason);

    /** Disparado por el boundary timer de SLA: el analista no resolvio en plazo. */
    void escalateReview(String applicationId);
}
