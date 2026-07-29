package com.rriveros.origination.support;

import com.rriveros.origination.domain.model.CreditApplication;
import org.junit.jupiter.api.TestInfo;

/**
 * Volcado diagnostico de solo lectura para {@code CreditOriginationProcessTest},
 * {@code fix-process-test-failures}.
 *
 * <p>Fase A incluia aca cuatro secciones adicionales (INSTANCE, ELEMENTS, INCIDENTS, EXPECTED)
 * basadas en la search API tipada de {@code ZeebeClient} (8.7.6). El run real de CI de Fase A
 * (PR1, commit {@code 0de59c1}) mostro que las tres consultas contra esa search API fallan con
 * HTTP 401 en todo run: en Camunda 8.7 esa API la sirve la capa REST, que exige autenticacion, y
 * el {@code ZeebeClient} que el test tiene autowired no la lleva -- a diferencia de la API de
 * comandos/jobs por gRPC, que si funciona. Cada linea {@code DIAG-ERROR: 401} era ruido, no
 * evidencia: aparecia incluso para elementos que una asercion exitosa de {@code CamundaAssert} ya
 * habia confirmado por otra via (su {@code CamundaDataSource} interno, que no pasa por esa search
 * API). Fase B elimina esas cuatro secciones en lugar de dejarlas fallando en cada run de CI.
 *
 * <p>Se conserva unicamente AGGREGATE: lee el agregado via el puerto de entrada
 * {@code FindCreditApplicationUseCase} (JPA/H2), es independiente del cliente de Camunda, y fue
 * la seccion que efectivamente cerro el diagnostico de Fase A (estados {@code REVERTED} y
 * {@code DISBURSED} con ids consistentes en las 4 instancias observadas).
 *
 * <p>Seguimiento pendiente, fuera de alcance de este cambio: migrar a
 * {@code io.camunda.process.test.impl.assertions.CamundaDataSource} restauraria INSTANCE/
 * ELEMENTS/INCIDENTS sin depender de la search API REST, pero es una clase interna del jar de CPT
 * sin constructor ni firma verificables sin compilar contra ella, y no debe viajar en el PR cuyo
 * unico objetivo es poner CI en verde.
 *
 * <p>Invariantes que esta clase sostiene y que no se deben romper al modificarla:
 *
 * <ol>
 *   <li><b>Solo lectura.</b> Nunca emite un comando.
 *   <li><b>Nunca lanza.</b> La seccion esta envuelta en try/catch propio; una falla se imprime
 *       como linea {@code DIAG-ERROR} y no se propaga al test.
 * </ol>
 */
public final class ProcessDiagnostics {

    private static final String PREFIX = "DIAG |";

    private ProcessDiagnostics() {}

    /**
     * Emite el estado del agregado para una instancia de proceso observada. Pensado para
     * invocarse desde {@code @AfterEach}, incluso cuando el metodo de test ya lanzo una {@link
     * AssertionError}.
     *
     * @param testInfo info del test en curso, solo para el nombre en los marcadores
     * @param processInstanceKey key de la instancia observada, solo para correlacionar el bloque
     *     con el resto del log de CI
     * @param aggregate estado del agregado ya resuelto por el llamador, o {@code null} si no se
     *     pudo resolver
     */
    public static void dump(TestInfo testInfo, long processInstanceKey, CreditApplication aggregate) {
        String testName = testInfo.getDisplayName();
        System.out.println("=== DIAG BEGIN " + testName + " pik=" + processInstanceKey + " ===");
        printAggregate(aggregate);
        System.out.println("=== DIAG END " + testName + " pik=" + processInstanceKey + " ===");
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
