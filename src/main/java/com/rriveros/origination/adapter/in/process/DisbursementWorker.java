package com.rriveros.origination.adapter.in.process;

import com.rriveros.origination.domain.port.in.FundApplicationUseCase;
import com.rriveros.origination.domain.port.out.LedgerGateway;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import io.camunda.zeebe.spring.common.exception.ZeebeBpmnError;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Adaptador de entrada para {@code Activity_DisburseLoan}.
 *
 * <p>Aca esta la distincion que mas se equivoca en Camunda: <strong>fallo tecnico</strong> vs
 * <strong>error de negocio</strong>.
 *
 * <ul>
 *   <li>Si el core bancario no responde (timeout, 503), la excepcion sube tal cual. Zeebe reintenta
 *       segun {@code retries} y, si se agotan, crea un incidente en Operate. Es lo que queremos.
 *   <li>Si el core <em>rechaza</em> el desembolso, reintentar es inutil: el resultado va a ser el
 *       mismo. Eso se modela como {@link ZeebeBpmnError}, que el boundary de error captura y desde
 *       ahi se dispara la compensacion.
 * </ul>
 *
 * <p>El {@code errorCode} tiene que coincidir letra por letra con el {@code errorCode} del
 * {@code bpmn:error} del modelo. Si no coincide, Zeebe levanta un incidente en vez de tomar el
 * camino de compensacion.
 */
@Component
public class DisbursementWorker {

    static final String DISBURSEMENT_FAILED = "DISBURSEMENT_FAILED";

    private final FundApplicationUseCase fundApplication;

    public DisbursementWorker(FundApplicationUseCase fundApplication) {
        this.fundApplication = fundApplication;
    }

    @JobWorker(type = "disburse-loan", fetchVariables = {"applicationId"})
    public Map<String, Object> disburseLoan(@Variable String applicationId) {
        try {
            return Map.of("disbursementId", fundApplication.disburse(applicationId));
        } catch (LedgerGateway.DisbursementRejectedException rejected) {
            throw new ZeebeBpmnError(
                    DISBURSEMENT_FAILED,
                    rejected.getMessage(),
                    Map.of("failureReason", rejected.getMessage()));
        }
    }
}
