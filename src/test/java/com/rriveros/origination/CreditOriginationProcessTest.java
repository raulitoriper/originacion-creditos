package com.rriveros.origination;

import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.rriveros.origination.domain.model.ApplicationStatus;
import com.rriveros.origination.domain.model.BureauReport;
import com.rriveros.origination.domain.model.CreditApplication;
import com.rriveros.origination.domain.port.in.FindCreditApplicationUseCase;
import com.rriveros.origination.domain.port.in.SignApplicationUseCase;
import com.rriveros.origination.domain.port.in.SubmitCreditApplicationUseCase;
import com.rriveros.origination.domain.port.in.SubmitCreditApplicationUseCase.SubmitCommand;
import com.rriveros.origination.domain.port.out.CreditBureauGateway;
import com.rriveros.origination.support.FlowNodeElementProbe;
import com.rriveros.origination.support.ProcessDiagnostics;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.zeebe.client.ZeebeClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests de proceso de punta a punta contra un Zeebe real levantado en Testcontainers.
 *
 * <p>Esto es lo que separa un portfolio con Camunda de un portfolio que "usa" Camunda. El BPMN es
 * codigo ejecutable: los gateways, los timers, la correlacion de mensajes y la compensacion son
 * logica de negocio y se testean como tal. Un test que solo verifica los job workers con Mockito no
 * prueba el proceso, prueba metodos.
 *
 * <p>Requiere Docker corriendo.
 */
@SpringBootTest
@CamundaSpringProcessTest
@ActiveProfiles("test")
class CreditOriginationProcessTest {

    private static final String BPMN_RESOURCE = "models/credit-origination.bpmn";
    private static final String DMN_RESOURCE = "models/credit-scoring.dmn";

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private CamundaProcessTestContext processTestContext;

    @Autowired
    private SubmitCreditApplicationUseCase submitApplication;

    @Autowired
    private SignApplicationUseCase signApplication;

    @Autowired
    private FindCreditApplicationUseCase findApplication;

    @MockitoBean
    private CreditBureauGateway bureau;

    // Instancias observadas por processInstanceKeyOf() en el test en curso. Alimenta el volcado
    // diagnostico de Fase A; no participa de ninguna asercion.
    private final List<ObservedInstance> observedInstances = new ArrayList<>();

    // Fase B (fix-process-test-failures), diagnostico ronda 2: este timeout de 20 s SE PROBO en CI
    // (run 30466947115, commit 466c6d5) y NO cambio nada -- mismas 2 fallas, mismos elementos
    // ausentes, 255 s de duracion total. Ademas se descarto por bytecode (javap contra los jars
    // reales de camunda-process-test-java y camunda-process-test-spring) que
    // CamundaProcessTestExecutionListener o CamundaAssert.reset() invoquen setAssertionTimeout o
    // Awaitility en algun punto: reset() solo hace DATA_SOURCE.remove() sobre un ThreadLocal. El
    // timeout de 20 s estuvo vigente durante todo ese run y los elementos igual no fueron
    // observables: queda establecido que esto NO es un problema de tiempo/espera. Se conserva este
    // @BeforeAll unicamente a la espera del arreglo real -- que el volcado de la ronda 2 via
    // CamundaDataSource (ver ProcessDiagnostics) debe permitir identificar -- y se revierte con
    // restoreAssertionTimeout() para no dejar mutado un default global de Awaitility entre clases
    // de test (surefire usa reuseForks=true por defecto, sin override en pom.xml).
    @BeforeAll
    static void configureAssertionTimeout() {
        CamundaAssert.setAssertionTimeout(Duration.ofSeconds(20));
    }

    // Revierte el timeout global de Awaitility que configureAssertionTimeout() establecio arriba.
    // CamundaProcessTestExecutionListener no lo resetea entre tests (verificado por javap), y sin
    // fork por clase el valor mutado sobreviviria a esta clase y contaminaria la siguiente que
    // corra en el mismo fork si no se restaura aca.
    @AfterAll
    static void restoreAssertionTimeout() {
        CamundaAssert.setAssertionTimeout(CamundaAssert.DEFAULT_ASSERTION_TIMEOUT);
    }

    @BeforeEach
    void deployModels() {
        zeebeClient.newDeployResourceCommand()
                .addResourceFromClasspath(BPMN_RESOURCE)
                .addResourceFromClasspath(DMN_RESOURCE)
                .send()
                .join();
    }

    // Fase A (fix-process-test-failures): volcado diagnostico de solo lectura, corre aun cuando
    // el metodo de test ya lanzo una AssertionError. No cambia ni un id ni una asercion existente.
    //
    // Fase B: findApplication.findById() se protege con try/catch, mismo patron que
    // ProcessDiagnostics.printError -- una falla aca no debe agregar una falla espuria dentro de
    // @AfterEach ni desplazar la AssertionError real del metodo de test.
    //
    // Diagnostico ronda 2: se pasa la direccion REST del broker (processTestContext, ya autowired)
    // para que ProcessDiagnostics pueda construir su propio CamundaDataSource de solo lectura.
    @AfterEach
    void dumpDiagnostics(TestInfo testInfo) {
        // Tambien protegido: getCamundaRestAddress() puede fallar o devolver null, y una NPE aca
        // escaparia del @AfterEach. Si no se resuelve, el volcado sigue con las secciones que no
        // dependen del broker.
        String camundaRestAddress = null;
        try {
            camundaRestAddress = processTestContext.getCamundaRestAddress().toString();
        } catch (Exception e) {
            ProcessDiagnostics.printError("DATASOURCE", e);
        }
        for (ObservedInstance observed : observedInstances) {
            CreditApplication aggregate = null;
            try {
                aggregate = findApplication.findById(observed.applicationId()).orElse(null);
            } catch (Exception e) {
                ProcessDiagnostics.printError("AGGREGATE", e);
            }
            ProcessDiagnostics.dump(testInfo, observed.processInstanceKey(), aggregate, camundaRestAddress);
        }
    }

    @Test
    @DisplayName("Score alto: aprueba por DMN, espera la firma y desembolsa")
    void aprueba_y_desembolsa_cuando_el_score_es_alto() {
        givenBureauReport(820, false);

        // cuota 30.000.000/24 = 1.250.000 sobre ingreso 12.000.000 -> ratio 0.1042
        // score 820 >= 750 y ratio <= 0.30 -> Rule_PrimeAutoApprove -> APPROVED
        String applicationId = submit("4501234", new BigDecimal("12000000"), new BigDecimal("30000000"), 24);
        long processInstanceKey = processInstanceKeyOf(
                applicationId,
                "Activity_QueryBureau",
                "Activity_ScoreApplicant",
                "Event_SignatureReceived",
                "Activity_ReserveFunds",
                "Activity_DisburseLoan",
                "Activity_NotifyApproval",
                "EndEvent_Disbursed");

        CamundaAssert.assertThat(byKey(processInstanceKey))
                .hasCompletedElements("Activity_QueryBureau", "Activity_ScoreApplicant")
                .hasVariable("riskDecision", "APPROVED");

        // El proceso esta detenido en el event-based gateway. Nada avanza hasta que llegue el mensaje.
        signApplication.sign(applicationId);

        CamundaAssert.assertThat(byKey(processInstanceKey))
                .isCompleted()
                .hasCompletedElements(
                        "Event_SignatureReceived",
                        "Activity_ReserveFunds",
                        "Activity_DisburseLoan",
                        "Activity_NotifyApproval");

        // Fase B (fix-process-test-failures): EndEvent_Disbursed es el 11er flow node instance
        // creado en esta corrida (10 lo preceden) y CamundaAssert.hasCompletedElements no puede
        // verlo -- causa raiz probada a nivel bytecode, ver Javadoc de FlowNodeElementProbe (limite
        // de pagina de 10 filas del broker, CamundaDataSource descarta el campo "total" y devuelve
        // solo esas 10 filas). Se verifica aparte con una busqueda REST acotada por flowNodeId, que
        // devuelve a lo sumo 1 fila y no sufre esa truncacion. Asercion aditiva: no reemplaza
        // hasCompletedElements para los ids que si caen dentro de las primeras 10 filas.
        assertTailElementCompleted(processInstanceKey, "EndEvent_Disbursed");

        CreditApplication application = reload(applicationId);
        assertThat(application.status()).isEqualTo(ApplicationStatus.DISBURSED);
        assertThat(application.disbursementId()).isNotNull();
    }

    @Test
    @DisplayName("Mora vigente: el DMN rechaza sin intervencion humana")
    void rechaza_cuando_hay_mora_vigente() {
        givenBureauReport(700, true);

        String applicationId = submit("4507771", new BigDecimal("12000000"), new BigDecimal("20000000"), 24);
        long processInstanceKey =
                processInstanceKeyOf(applicationId, "Activity_NotifyRejection", "EndEvent_Rejected");

        CamundaAssert.assertThat(byKey(processInstanceKey))
                .isCompleted()
                .hasVariable("riskDecision", "REJECTED")
                .hasCompletedElements("Activity_NotifyRejection", "EndEvent_Rejected");

        CreditApplication application = reload(applicationId);
        assertThat(application.status()).isEqualTo(ApplicationStatus.REJECTED);
    }

    @Test
    @DisplayName("Desembolso rechazado: el error de BPMN dispara la compensacion y libera la reserva")
    void compensa_la_reserva_cuando_el_desembolso_es_rechazado() {
        givenBureauReport(880, false);

        // 160.000.000 supera el limite del ledger (150.000.000) -> DISBURSEMENT_FAILED
        // cuota 6.666.666,67 sobre ingreso 30.000.000 -> ratio 0.2222, entra por APPROVED
        String applicationId = submit("4509991", new BigDecimal("30000000"), new BigDecimal("160000000"), 24);
        long processInstanceKey = processInstanceKeyOf(
                applicationId,
                "Activity_ScoreApplicant",
                "Activity_ReserveFunds",
                "Activity_DisburseLoan",
                "Event_CompensateOrigination",
                "Activity_ReleaseFunds",
                "EndEvent_DisbursementFailed");

        CamundaAssert.assertThat(byKey(processInstanceKey)).hasCompletedElements("Activity_ScoreApplicant");
        signApplication.sign(applicationId);

        CamundaAssert.assertThat(byKey(processInstanceKey))
                .isCompleted()
                .hasCompletedElements("Activity_ReserveFunds")
                .hasTerminatedElements("Activity_DisburseLoan");

        // Fase B (fix-process-test-failures): Event_CompensateOrigination, Activity_ReleaseFunds y
        // EndEvent_DisbursementFailed son los flow node instances 11, 12 y 13 creados en esta
        // corrida (la compensacion completa via boundary error), fuera de la pagina de 10 filas que
        // CamundaDataSource devuelve sin paginar -- misma causa raiz que en el camino feliz, ver
        // Javadoc de FlowNodeElementProbe. Se verifican aparte con la misma busqueda REST acotada
        // por flowNodeId. Asercion aditiva: no reemplaza hasCompletedElements/hasTerminatedElements
        // para los ids que si caen dentro de las primeras 10 filas.
        assertTailElementCompleted(processInstanceKey, "Event_CompensateOrigination");
        assertTailElementCompleted(processInstanceKey, "Activity_ReleaseFunds");
        assertTailElementCompleted(processInstanceKey, "EndEvent_DisbursementFailed");

        CreditApplication application = reload(applicationId);
        assertThat(application.status()).isEqualTo(ApplicationStatus.REVERTED);
        assertThat(application.reservationId()).isNull();
        assertThat(application.disbursementId()).isNull();
    }

    @Test
    @DisplayName("Zona gris: va a revision manual y el boundary timer escala sin interrumpir la tarea")
    void escala_al_supervisor_cuando_vence_el_sla_de_revision() {
        givenBureauReport(600, false);

        // score 600: no llega a ninguna regla de aprobacion automatica -> MANUAL_REVIEW
        String applicationId = submit("4503331", new BigDecimal("12000000"), new BigDecimal("20000000"), 36);
        long processInstanceKey = processInstanceKeyOf(
                applicationId, "Activity_ManualReview", "Activity_EscalateReview", "EndEvent_Escalated");

        CamundaAssert.assertThat(byKey(processInstanceKey))
                .hasVariable("riskDecision", "MANUAL_REVIEW")
                .hasActiveElements("Activity_ManualReview");

        // El SLA es PT4H. Adelantamos el reloj del motor en vez de esperar cuatro horas.
        processTestContext.increaseTime(Duration.ofHours(5));

        CamundaAssert.assertThat(byKey(processInstanceKey))
                .hasCompletedElements("Activity_EscalateReview", "EndEvent_Escalated")
                // cancelActivity="false": la tarea del analista sigue viva despues de la escalada
                .hasActiveElements("Activity_ManualReview");

        assertThat(reload(applicationId).status()).isEqualTo(ApplicationStatus.PENDING_REVIEW);
    }

    // ---------- helpers ----------

    private void givenBureauReport(int score, boolean hasActiveDefaults) {
        when(bureau.fetchReport(anyString()))
                .thenReturn(new BureauReport(score, hasActiveDefaults, new BigDecimal("1000000")));
    }

    private String submit(String document, BigDecimal monthlyIncome, BigDecimal requestedAmount, int termMonths) {
        return submitApplication.submit(new SubmitCommand(
                document, "Solicitante de prueba", monthlyIncome, requestedAmount, termMonths));
    }

    private long processInstanceKeyOf(String applicationId, String... expectedElementIds) {
        long processInstanceKey = reload(applicationId).processInstanceKey();
        observedInstances.add(
                new ObservedInstance(processInstanceKey, applicationId, List.of(expectedElementIds)));
        return processInstanceKey;
    }

    private CreditApplication reload(String applicationId) {
        return findApplication.findById(applicationId).orElseThrow();
    }

    // Fase B (fix-process-test-failures): asercion aditiva para un flow node instance que
    // CamundaAssert.hasCompletedElements no puede ver por el limite de pagina de 10 filas de
    // CamundaDataSource (ver Javadoc de FlowNodeElementProbe para la causa raiz completa, probada a
    // nivel bytecode). Imprime una linea DIAG con el resultado observado ANTES de la asercion, para
    // que el log de CI muestre el detalle exacto (HTTP status, total, cantidad de items, cuerpo
    // crudo) incluso si la asercion falla.
    private void assertTailElementCompleted(long processInstanceKey, String flowNodeId) {
        String camundaRestAddress;
        try {
            camundaRestAddress = processTestContext.getCamundaRestAddress().toString();
        } catch (Exception e) {
            camundaRestAddress = null;
        }
        FlowNodeElementProbe probe = new FlowNodeElementProbe(camundaRestAddress);
        FlowNodeElementProbe.Result result = probe.findElementState(processInstanceKey, flowNodeId);
        System.out.println(
                "DIAG | TAIL_ELEMENT | flowNodeId=" + flowNodeId + " pik=" + processInstanceKey + " | "
                        + result.diagnosticDetail());
        assertThat(result.state())
                .as(
                        "estado de %s (pik=%d) via busqueda REST acotada por flowNodeId -- %s",
                        flowNodeId, processInstanceKey, result.diagnosticDetail())
                .isEqualTo("COMPLETED");
    }

    // Registro minimo para el volcado diagnostico de Fase A: la key ya se resuelve en
    // processInstanceKeyOf(), y el agregado se recarga por applicationId dentro de @AfterEach.
    private record ObservedInstance(
            long processInstanceKey, String applicationId, List<String> expectedElementIds) {}
}
