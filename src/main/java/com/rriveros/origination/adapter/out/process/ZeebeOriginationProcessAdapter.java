package com.rriveros.origination.adapter.out.process;

import com.rriveros.origination.domain.model.CreditApplication;
import com.rriveros.origination.domain.port.out.OriginationProcessGateway;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Unico punto del proyecto que conoce la API de Camunda 8 para <em>escribir</em>.
 *
 * <p>Las variables que se mandan al arrancar son las minimas que el modelo necesita para decidir.
 * El motor de procesos no es una base de datos: si mandas el agregado entero como variables, el
 * estado se te duplica y se te desincroniza. {@code applicationId} y listo, el resto se lee de la
 * base cuando hace falta.
 */
@Component
public class ZeebeOriginationProcessAdapter implements OriginationProcessGateway {

    static final String PROCESS_ID = "credit-origination";
    static final String SIGNATURE_MESSAGE = "SignatureCompleted";

    private final ZeebeClient zeebeClient;

    public ZeebeOriginationProcessAdapter(ZeebeClient zeebeClient) {
        this.zeebeClient = zeebeClient;
    }

    @Override
    public long startOrigination(CreditApplication application) {
        ProcessInstanceEvent event = zeebeClient.newCreateInstanceCommand()
                .bpmnProcessId(PROCESS_ID)
                .latestVersion()
                .variables(Map.of(
                        "applicationId", application.id(),
                        "requestedAmount", application.requestedAmount(),
                        "termMonths", application.termMonths()))
                .send()
                .join();

        return event.getProcessInstanceKey();
    }

    /**
     * El {@code correlationKey} tiene que dar exactamente igual que el
     * {@code zeebe:subscription correlationKey="=applicationId"} del mensaje en el BPMN. Si no
     * coincide, el mensaje se publica sin error y nunca correlaciona: el proceso se queda esperando
     * para siempre y no hay incidente en Operate que te avise. Es el bug mas silencioso de Camunda.
     */
    @Override
    public void signalSignatureCompleted(String applicationId) {
        zeebeClient.newPublishMessageCommand()
                .messageName(SIGNATURE_MESSAGE)
                .correlationKey(applicationId)
                .timeToLive(Duration.ofDays(7))
                .send()
                .join();
    }
}
