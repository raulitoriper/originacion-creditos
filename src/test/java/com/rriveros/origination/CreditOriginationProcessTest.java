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
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.zeebe.client.ZeebeClient;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    @BeforeEach
    void deployModels() {
        zeebeClient.newDeployResourceCommand()
                .addResourceFromClasspath(BPMN_RESOURCE)
                .addResourceFromClasspath(DMN_RESOURCE)
                .send()
                .join();
    }

    @Test
    @DisplayName("Score alto: aprueba por DMN, espera la firma y desembolsa")
    void aprueba_y_desembolsa_cuando_el_score_es_alto() {
        givenBureauReport(820, false);

        // cuota 30.000.000/24 = 1.250.000 sobre ingreso 12.000.000 -> ratio 0.1042
        // score 820 >= 750 y ratio <= 0.30 -> Rule_PrimeAutoApprove -> APPROVED
        String applicationId = submit("4501234", new BigDecimal("12000000"), new BigDecimal("30000000"), 24);
        long processInstanceKey = processInstanceKeyOf(applicationId);

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
                        "Activity_NotifyApproval",
                        "EndEvent_Disbursed");

        CreditApplication application = reload(applicationId);
        assertThat(application.status()).isEqualTo(ApplicationStatus.DISBURSED);
        assertThat(application.disbursementId()).isNotNull();
    }

    @Test
    @DisplayName("Mora vigente: el DMN rechaza sin intervencion humana")
    void rechaza_cuando_hay_mora_vigente() {
        givenBureauReport(700, true);

        String applicationId = submit("4507771", new BigDecimal("12000000"), new BigDecimal("20000000"), 24);
        long processInstanceKey = processInstanceKeyOf(applicationId);

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
        long processInstanceKey = processInstanceKeyOf(applicationId);

        CamundaAssert.assertThat(byKey(processInstanceKey)).hasCompletedElements("Activity_ScoreApplicant");
        signApplication.sign(applicationId);

        CamundaAssert.assertThat(byKey(processInstanceKey))
                .isCompleted()
                .hasCompletedElements(
                        "Activity_ReserveFunds",
                        "Event_CompensateOrigination",
                        "Activity_ReleaseFunds",
                        "EndEvent_DisbursementFailed")
                .hasTerminatedElements("Activity_DisburseLoan");

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
        long processInstanceKey = processInstanceKeyOf(applicationId);

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

    private long processInstanceKeyOf(String applicationId) {
        return reload(applicationId).processInstanceKey();
    }

    private CreditApplication reload(String applicationId) {
        return findApplication.findById(applicationId).orElseThrow();
    }
}
