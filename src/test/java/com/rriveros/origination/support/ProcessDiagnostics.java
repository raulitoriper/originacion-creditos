package com.rriveros.origination.support;

import com.rriveros.origination.domain.model.CreditApplication;
import io.camunda.process.test.impl.assertions.CamundaDataSource;
import io.camunda.process.test.impl.client.FlowNodeInstanceDto;
import io.camunda.process.test.impl.client.ProcessInstanceDto;
import io.camunda.process.test.impl.client.VariableDto;
import java.util.List;
import org.junit.jupiter.api.TestInfo;

/**
 * Volcado diagnostico de solo lectura para {@code CreditOriginationProcessTest},
 * {@code fix-process-test-failures}.
 *
 * <p>Fase A (ronda 1) traia aca cuatro secciones basadas en la search API tipada de {@code
 * ZeebeClient} (8.7.6): fallaban con HTTP 401 en todo run de CI porque en Camunda 8.7 esa API la
 * sirve la capa REST, que exige autenticacion, y el {@code ZeebeClient} autowired en el test no la
 * lleva. Fase B (ronda 1) las elimino y dejo solo AGGREGATE. Un timeout de asercion mas largo
 * (ronda 1) se probo despues en CI (run 30466947115, commit {@code 466c6d5}) y no cambio nada:
 * mismas 2 fallas, mismos elementos ausentes. Verificado por bytecode que ni el timeout de
 * asercion ni {@code CamundaAssert.reset()} tienen nada que ver con exportar o no exportar
 * elementos -- ver el comentario de {@code CreditOriginationProcessTest.configureAssertionTimeout}.
 *
 * <p>Ronda 2 (diagnostico) vuelve a traer INSTANCE, ELEMENTS y agrega VARIABLES, pero leyendolos
 * de {@code io.camunda.process.test.impl.assertions.CamundaDataSource}: es la MISMA clase que
 * {@code CamundaAssert} usa internamente para resolver sus propias aserciones (las que si pasan
 * en los tests de control), asi que si ella puede leer el estado, este volcado tambien puede.
 * {@code CamundaDataSource} y sus DTOs viven en paquetes {@code impl}: son API interna del jar de
 * CPT, sin garantia de compatibilidad entre versiones. Esta clase depende de ellos a proposito y
 * de forma temporal, solo para este diagnostico -- no es una decision de diseño permanente. Si
 * este archivo sigue existiendo despues de que la causa raiz quede resuelta, esa dependencia debe
 * eliminarse o reemplazarse por API publica.
 *
 * <p>Se imprimen TODOS los elementos que devuelve {@code CamundaDataSource}, sin filtrar contra
 * los ids esperados por cada escenario: el objetivo de este volcado es ver que hay realmente,
 * incluyendo elementos que nadie esperaba o estados que nadie predijo.
 *
 * <p>Invariantes que esta clase sostiene y que no se deben romper al modificarla:
 *
 * <ol>
 *   <li><b>Solo lectura.</b> Nunca emite un comando.
 *   <li><b>Nunca lanza.</b> Cada seccion esta envuelta en su propio try/catch; una falla se
 *       imprime como linea {@code DIAG-ERROR} y no se propaga al test.
 * </ol>
 */
public final class ProcessDiagnostics {

    private static final String PREFIX = "DIAG |";

    private ProcessDiagnostics() {}

    /**
     * Emite el estado observado de una instancia de proceso: instancia, elementos, variables y el
     * agregado. Pensado para invocarse desde {@code @AfterEach}, incluso cuando el metodo de test
     * ya lanzo una {@link AssertionError}.
     *
     * @param testInfo info del test en curso, solo para el nombre en los marcadores
     * @param processInstanceKey key de la instancia observada, solo para correlacionar el bloque
     *     con el resto del log de CI
     * @param aggregate estado del agregado ya resuelto por el llamador, o {@code null} si no se
     *     pudo resolver
     * @param camundaRestAddress direccion REST del broker de Camunda para este test
     *     ({@code CamundaProcessTestContext.getCamundaRestAddress()}), usada para construir el
     *     {@link CamundaDataSource} de solo lectura
     */
    public static void dump(
            TestInfo testInfo, long processInstanceKey, CreditApplication aggregate, String camundaRestAddress) {
        String testName = testInfo.getDisplayName();
        System.out.println("=== DIAG BEGIN " + testName + " pik=" + processInstanceKey + " ===");
        CamundaDataSource dataSource = createDataSource(camundaRestAddress);
        printInstance(dataSource, processInstanceKey);
        printElements(dataSource, processInstanceKey);
        printVariables(dataSource, processInstanceKey);
        printAggregate(aggregate);
        System.out.println("=== DIAG END " + testName + " pik=" + processInstanceKey + " ===");
    }

    private static CamundaDataSource createDataSource(String camundaRestAddress) {
        try {
            return new CamundaDataSource(camundaRestAddress);
        } catch (Exception e) {
            printError("DATASOURCE", e);
            return null;
        }
    }

    private static void printInstance(CamundaDataSource dataSource, long processInstanceKey) {
        if (dataSource == null) {
            return;
        }
        try {
            ProcessInstanceDto instance = dataSource.getProcessInstance(processInstanceKey);
            if (instance == null) {
                System.out.println(PREFIX + " INSTANCE | NOT_FOUND");
                return;
            }
            System.out.println(PREFIX + " INSTANCE"
                    + " | state=" + String.valueOf(instance.getState())
                    + " | processVersion=" + String.valueOf(instance.getProcessVersion())
                    + " | startDate=" + String.valueOf(instance.getStartDate())
                    + " | endDate=" + String.valueOf(instance.getEndDate()));
        } catch (Exception e) {
            printError("INSTANCE", e);
        }
    }

    private static void printElements(CamundaDataSource dataSource, long processInstanceKey) {
        if (dataSource == null) {
            return;
        }
        try {
            List<FlowNodeInstanceDto> elements =
                    dataSource.getFlowNodeInstancesByProcessInstanceKey(processInstanceKey);
            if (elements.isEmpty()) {
                System.out.println(PREFIX + " ELEMENTS | NONE");
                return;
            }
            for (FlowNodeInstanceDto element : elements) {
                System.out.println(PREFIX + " ELEMENTS"
                        + " | flowNodeId=" + String.valueOf(element.getFlowNodeId())
                        + " | state=" + String.valueOf(element.getState())
                        + " | startDate=" + String.valueOf(element.getStartDate())
                        + " | endDate=" + String.valueOf(element.getEndDate())
                        + " | incident=" + String.valueOf(element.getIncident()));
            }
        } catch (Exception e) {
            printError("ELEMENTS", e);
        }
    }

    private static void printVariables(CamundaDataSource dataSource, long processInstanceKey) {
        if (dataSource == null) {
            return;
        }
        try {
            List<VariableDto> variables = dataSource.getVariablesByProcessInstanceKey(processInstanceKey);
            if (variables.isEmpty()) {
                System.out.println(PREFIX + " VARIABLES | NONE");
                return;
            }
            for (VariableDto variable : variables) {
                System.out.println(PREFIX + " VARIABLES"
                        + " | name=" + String.valueOf(variable.getName())
                        + " | value=" + String.valueOf(variable.getValue()));
            }
        } catch (Exception e) {
            printError("VARIABLES", e);
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

    /**
     * Imprime una linea {@code DIAG-ERROR} con el mismo formato que usan las secciones internas
     * de esta clase. Publica para que otros puntos de solo lectura del test (p. ej. el
     * {@code findApplication.findById(...)} de {@code @AfterEach}) sostengan el mismo invariante
     * "nunca lanza" sin duplicar el formato de linea.
     *
     * @param section nombre de la seccion que fallo, para el prefijo de la linea
     * @param e excepcion capturada; nunca se relanza
     */
    public static void printError(String section, Exception e) {
        System.out.println(PREFIX + " " + section + " | DIAG-ERROR: "
                + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
    }
}
