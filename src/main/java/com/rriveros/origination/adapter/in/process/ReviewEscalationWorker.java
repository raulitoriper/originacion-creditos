package com.rriveros.origination.adapter.in.process;

import com.rriveros.origination.domain.port.in.ResolveApplicationUseCase;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import org.springframework.stereotype.Component;

/**
 * Adaptador de entrada para {@code Activity_EscalateReview}.
 *
 * <p>Lo dispara el boundary timer {@code Boundary_ReviewSla} con {@code cancelActivity="false"}:
 * no interrumpe. La tarea del analista sigue viva y en paralelo se avisa al supervisor. Ese SLA es
 * una linea de XML; en codigo a mano es un scheduler, una tabla de vencimientos y un bug.
 */
@Component
public class ReviewEscalationWorker {

    private final ResolveApplicationUseCase resolveApplication;

    public ReviewEscalationWorker(ResolveApplicationUseCase resolveApplication) {
        this.resolveApplication = resolveApplication;
    }

    @JobWorker(type = "escalate-review", fetchVariables = {"applicationId"})
    public void escalateReview(@Variable String applicationId) {
        resolveApplication.escalateReview(applicationId);
    }
}
