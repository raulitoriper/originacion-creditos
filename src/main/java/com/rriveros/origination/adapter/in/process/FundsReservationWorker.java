package com.rriveros.origination.adapter.in.process;

import com.rriveros.origination.domain.model.RiskDecision;
import com.rriveros.origination.domain.port.in.FundApplicationUseCase;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Adaptador de entrada para {@code Activity_ReserveFunds}.
 *
 * <p>Esta actividad tiene un boundary de compensacion colgado en el BPMN. Por eso devuelve el
 * {@code reservationId} como variable: es el dato que necesita el handler de compensacion para
 * poder deshacer lo hecho.
 */
@Component
public class FundsReservationWorker {

    private final FundApplicationUseCase fundApplication;

    public FundsReservationWorker(FundApplicationUseCase fundApplication) {
        this.fundApplication = fundApplication;
    }

    @JobWorker(type = "reserve-funds", fetchVariables = {"applicationId", "riskDecision"})
    public Map<String, Object> reserveFunds(@Variable String applicationId,
                                            @Variable String riskDecision) {
        String reservationId = fundApplication.reserveFunds(
                applicationId, RiskDecision.valueOf(riskDecision));
        return Map.of("reservationId", reservationId);
    }
}
