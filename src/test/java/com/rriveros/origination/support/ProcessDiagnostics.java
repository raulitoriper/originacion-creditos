package com.rriveros.origination.support;

import com.rriveros.origination.domain.model.CreditApplication;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.search.response.FlowNodeInstance;
import io.camunda.zeebe.client.api.search.response.Incident;
import io.camunda.zeebe.client.api.search.response.ProcessInstance;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.TestInfo;

/**
 * Volcado diagnostico de solo lectura para {@code CreditOriginationProcessTest}, Fase A de
 * {@code fix-process-test-failures}.
 *
 * <p>Invariantes que esta clase sostiene y que no se deben romper al modificarla:
 *
 * <ol>
 *   <li><b>Solo consultas.</b> Nunca emite un comando (nada de setVariables, publishMessage,
 *       activacion de jobs, increaseTime ni despliegues).
 *   <li><b>Sin espera condicional.</b> El unico delay es un {@code Thread.sleep} fijo e
 *       incondicional entre los dos disparos; no hay reintento hasta exito.
 *   <li><b>Nunca lanza.</b> Cada seccion esta envuelta por separado; una falla se imprime como
 *       linea {@code DIAG-ERROR} y no interrumpe el resto del volcado ni se propaga al test.
 *   <li><b>Camino uniforme.</b> Se invoca igual para los 4 tests, pasen o fallen. Sin ramas
 *       condicionales sobre el resultado del test.
 * </ol>
 *
 * <p>Utilitaria y estatica, sin anotaciones de Spring: no entra al contexto de la aplicacion y por
 * lo tanto no puede alterar el orden de arranque que se esta midiendo. Vive enteramente en test
 * scope y solo importa el lenguaje de frontera de {@code io.camunda.*}; no importa nada de
 * {@code domain/} salvo el propio agregado ya resuelto por el llamador via {@code
 * FindCreditApplicationUseCase}.
 */
public final class ProcessDiagnostics {

    private static final String PREFIX = "DIAG |";
    private static final Duration SECOND_SHOT_DELAY = Duration.ofSeconds(3);

    private ProcessDiagnostics() {}

    /**
     * Emite dos disparos (T1 inmediato, T2 tras un delay fijo) del estado observable de una
     * instancia de proceso. Pensado para invocarse desde {@code @AfterEach}, incluso cuando el
     * metodo de test ya lanzo una {@link AssertionError}.
     *
     * @param zeebeClient cliente ya autowired por el test, nunca se cierra aca
     * @param testInfo info del test en curso, solo para el nombre en los marcadores
     * @param processInstanceKey key de la instancia observada
     * @param expectedElementIds ids que el test asertó como parte del camino esperado
     * @param aggregate estado del agregado ya resuelto por el llamador, o {@code null} si no se
     *     pudo resolver
     */
    public static void dump(
            ZeebeClient zeebeClient,
            TestInfo testInfo,
            long processInstanceKey,
            List<String> expectedElementIds,
            CreditApplication aggregate) {
        String testName = testInfo.getDisplayName();
        shot(zeebeClient, testName, processInstanceKey, expectedElementIds, aggregate, "T1");
        try {
            Thread.sleep(SECOND_SHOT_DELAY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        shot(zeebeClient, testName, processInstanceKey, expectedElementIds, aggregate, "T2");
    }

    private static void shot(
            ZeebeClient zeebeClient,
            String testName,
            long processInstanceKey,
            List<String> expectedElementIds,
            CreditApplication aggregate,
            String shot) {
        System.out.println("=== DIAG BEGIN " + testName + " pik=" + processInstanceKey + " shot=" + shot + " ===");
        printInstance(zeebeClient, processInstanceKey);
        List<FlowNodeInstance> elements = printElements(zeebeClient, processInstanceKey);
        printIncidents(zeebeClient, processInstanceKey);
        printExpected(elements, expectedElementIds);
        printAggregate(aggregate);
        System.out.println("=== DIAG END " + testName + " pik=" + processInstanceKey + " shot=" + shot + " ===");
    }

    private static void printInstance(ZeebeClient zeebeClient, long processInstanceKey) {
        try {
            // ProcessInstanceFilter (8.7.6) no expone un filtro por processInstanceKey: se trae la
            // coleccion completa (diminuta en este escenario de test) y se reduce en el cliente.
            List<ProcessInstance> items =
                    zeebeClient.newProcessInstanceQuery().send().join().items();
            Optional<ProcessInstance> instance = items.stream()
                    .filter(pi -> pi.getKey() != null && pi.getKey().equals(processInstanceKey))
                    .findFirst();
            if (instance.isEmpty()) {
                System.out.println(PREFIX + " INSTANCE | NOT_FOUND");
                return;
            }
            ProcessInstance pi = instance.get();
            System.out.println(PREFIX + " INSTANCE"
                    + " | state=" + String.valueOf(pi.getState())
                    + " | processDefinitionId=" + String.valueOf(pi.getBpmnProcessId())
                    + " | processDefinitionKey=" + String.valueOf(pi.getProcessDefinitionKey())
                    + " | processDefinitionVersion=" + String.valueOf(pi.getProcessVersion())
                    + " | startDate=" + String.valueOf(pi.getStartDate())
                    + " | endDate=" + String.valueOf(pi.getEndDate()));
        } catch (Exception e) {
            printError("INSTANCE", e);
        }
    }

    private static List<FlowNodeInstance> printElements(ZeebeClient zeebeClient, long processInstanceKey) {
        try {
            List<FlowNodeInstance> elements = zeebeClient
                    .newFlownodeInstanceQuery()
                    .filter(f -> f.processInstanceKey(processInstanceKey))
                    .send()
                    .join()
                    .items()
                    .stream()
                    .sorted(Comparator
                            .comparing((FlowNodeInstance f) -> Optional.ofNullable(f.getStartDate()).orElse(""))
                            .thenComparing(f -> Optional.ofNullable(f.getFlowNodeInstanceKey()).orElse(0L)))
                    .toList();
            if (elements.isEmpty()) {
                System.out.println(PREFIX + " ELEMENTS | (vacio)");
            }
            for (FlowNodeInstance element : elements) {
                System.out.println(PREFIX + " ELEMENTS"
                        + " | elementId=" + String.valueOf(element.getFlowNodeId())
                        + " | state=" + String.valueOf(element.getState())
                        + " | start=" + String.valueOf(element.getStartDate())
                        + " | end=" + String.valueOf(element.getEndDate())
                        + " | key=" + String.valueOf(element.getFlowNodeInstanceKey()));
            }
            return elements;
        } catch (Exception e) {
            printError("ELEMENTS", e);
            return List.of();
        }
    }

    private static void printIncidents(ZeebeClient zeebeClient, long processInstanceKey) {
        try {
            List<Incident> incidents = zeebeClient
                    .newIncidentQuery()
                    .filter(f -> f.processInstanceKey(processInstanceKey))
                    .send()
                    .join()
                    .items();
            if (incidents.isEmpty()) {
                System.out.println(PREFIX + " INCIDENTS | (vacio)");
            }
            for (Incident incident : incidents) {
                System.out.println(PREFIX + " INCIDENTS"
                        + " | elementId=" + String.valueOf(incident.getFlowNodeId())
                        + " | errorType=" + String.valueOf(incident.getErrorType())
                        + " | errorMessage=" + String.valueOf(incident.getErrorMessage())
                        + " | state=" + String.valueOf(incident.getState())
                        + " | creationTime=" + String.valueOf(incident.getCreationTime()));
            }
        } catch (Exception e) {
            printError("INCIDENTS", e);
        }
    }

    private static void printExpected(List<FlowNodeInstance> elements, List<String> expectedElementIds) {
        try {
            for (String expectedId : expectedElementIds) {
                boolean present = elements.stream().anyMatch(e -> expectedId.equals(e.getFlowNodeId()));
                System.out.println(PREFIX + " EXPECTED | " + expectedId + " | " + (present ? "OK" : "MISSING"));
            }
        } catch (Exception e) {
            printError("EXPECTED", e);
        }
    }

    private static void printAggregate(CreditApplication aggregate) {
        try {
            if (aggregate == null) {
                System.out.println(PREFIX + " AGGREGATE | NOT_FOUND");
                return;
            }
            System.out.println(PREFIX + " AGGREGATE"
                    + " | status=" + String.valueOf(aggregate.status())
                    + " | reservationId=" + String.valueOf(aggregate.reservationId())
                    + " | disbursementId=" + String.valueOf(aggregate.disbursementId()));
        } catch (Exception e) {
            printError("AGGREGATE", e);
        }
    }

    private static void printError(String section, Exception e) {
        System.out.println(PREFIX + " " + section + " | DIAG-ERROR: "
                + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
    }
}
