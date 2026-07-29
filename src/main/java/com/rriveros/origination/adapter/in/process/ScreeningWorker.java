package com.rriveros.origination.adapter.in.process;

import com.rriveros.origination.domain.port.in.ScreenApplicantUseCase;
import com.rriveros.origination.domain.port.in.ScreenApplicantUseCase.ScreeningResult;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import org.springframework.stereotype.Component;

/**
 * Adaptador de entrada para {@code Activity_QueryBureau}.
 *
 * <p>El worker no tiene logica: traduce un job de Zeebe a una llamada al caso de uso y devuelve el
 * resultado como variables de proceso. El record que retorna se serializa componente por
 * componente, y esos nombres son justo los que consumen las {@code inputExpression} del DMN.
 */
@Component
public class ScreeningWorker {

    private final ScreenApplicantUseCase screenApplicant;

    public ScreeningWorker(ScreenApplicantUseCase screenApplicant) {
        this.screenApplicant = screenApplicant;
    }

    @JobWorker(type = "query-credit-bureau", fetchVariables = {"applicationId"})
    public ScreeningResult queryCreditBureau(@Variable String applicationId) {
        return screenApplicant.screen(applicationId);
    }
}
