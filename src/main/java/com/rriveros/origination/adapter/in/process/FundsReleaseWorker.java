package com.rriveros.origination.adapter.in.process;

import com.rriveros.origination.domain.port.in.FundApplicationUseCase;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import org.springframework.stereotype.Component;

/**
 * Handler de compensacion para {@code Activity_ReleaseFunds}.
 *
 * <p>Esta actividad no esta conectada por ningun {@code sequenceFlow}: cuelga del boundary de
 * compensacion por una {@code bpmn:association} y se marca con {@code isForCompensation="true"}.
 * Zeebe la ejecuta solo cuando alguien lanza la compensacion, y solo si la actividad original
 * llego a completarse. Ese "solo si" es gratis en el motor y carisimo si lo escribis a mano.
 */
@Component
public class FundsReleaseWorker {

    private final FundApplicationUseCase fundApplication;

    public FundsReleaseWorker(FundApplicationUseCase fundApplication) {
        this.fundApplication = fundApplication;
    }

    @JobWorker(type = "release-funds", fetchVariables = {"applicationId", "failureReason"})
    public void releaseFunds(@Variable String applicationId, @Variable String failureReason) {
        fundApplication.releaseFunds(applicationId,
                failureReason != null ? failureReason : "Compensacion de la originacion");
    }
}
