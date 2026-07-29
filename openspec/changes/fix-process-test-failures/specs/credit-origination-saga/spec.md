# Credit Origination Saga Specification

## Purpose

Fija qué debe entregar el diagnóstico de Fase A de la saga de originación end-to-end, antes de
que Fase B corrija algo.

## Requirements

### Requirement: Compensación completa como camino de referencia

Cuando el desembolso es rechazado por el ledger, el proceso MUST completar la compensación de
la reserva y terminar en `EndEvent_DisbursementFailed` con el agregado `REVERTED`.

#### Scenario: Compensación completa de la reserva
- GIVEN monto sobre el límite del ledger
- WHEN `Activity_DisburseLoan` es rechazado
- THEN el proceso completa `Event_CompensateOrigination` y `Activity_ReleaseFunds`, y termina
  `REVERTED` con `reservationId`/`disbursementId` nulos

### Requirement: Desembolso completo como camino de referencia

Cuando el desembolso es aceptado por el ledger, el proceso MUST terminar en `EndEvent_Disbursed`
con el agregado `DISBURSED` y `disbursementId` no nulo.

#### Scenario: Desembolso completo tras la firma
- GIVEN monto dentro del límite del ledger y firma recibida
- WHEN `Activity_DisburseLoan` es aceptado
- THEN el proceso completa `Activity_NotifyApproval` y `EndEvent_Disbursed`, y termina `DISBURSED`

### Requirement: Volcado diagnóstico de Fase A

`CreditOriginationProcessTest` MUST emitir en el log de CI, antes de propagar una aserción
fallida, un volcado por instancia con: estado/timestamp de cada element instance, incidentes
(mensaje/`errorType`), end event de salida (o "ninguno"), `processDefinitionVersion`, variables
relevantes, y estado del agregado (`status`, `reservationId`, `disbursementId`).

#### Scenario: Volcado disponible para las 4 instancias
- GIVEN un run de CI con la suite completa
- WHEN se inspecciona el log
- THEN aparece, delimitado por marcadores buscables, el volcado completo por instancia

### Requirement: Fase A no debe alterar el resultado medido

Instrumentar el volcado MUST NOT cambiar el resultado del build ni las fallas existentes;
`deployModels()` MUST seguir siendo la única vía de despliegue.

#### Scenario: Mismo resultado tras instrumentar
- GIVEN la Fase A aplicada
- WHEN el mismo run de CI ejecuta la suite
- THEN sigue en `BUILD FAILURE` con las mismas 2 fallas del run de referencia (`75e3776`)
- AND si en cambio reporta `conclusion=success`, esa corrida MUST descartarse como evidencia

### Requirement: Log del motor con overrides acotados durante tests

El perfil de test MUST conservar `logging.level.io.camunda: WARN` y agregar overrides acotados
—`io.camunda.process.test: DEBUG` y `io.camunda.zeebe.spring.client.jobhandling: DEBUG`— para
exponer el impresor de resultados de CPT y la invocación y falla de jobs. Un `io.camunda: DEBUG`
global MUST NOT usarse: `JobPoller` emite DEBUG en cada poll de cada uno de los 7 workers y
sepultaría el volcado en el log de CI.

#### Scenario: Incidentes visibles sin sepultar el volcado
- GIVEN `application-test.yaml` con `io.camunda: WARN` y los dos overrides acotados
- WHEN un job falla o entra en incidente
- THEN el log contiene la entrada correspondiente y el volcado sigue siendo legible

### Requirement: Verificación asimétrica — solo CI ejecuta el proceso real

Toda instrumentación MUST compile-verificarse con `mvn -B test-compile` antes de un run de CI;
ningún requisito verificable solo por ejecución real SHALL darse por verificado localmente: el
test no corre aquí.

#### Scenario: Compilación local antes de cada push
- GIVEN cambios en `src/test/**` o `application-test.yaml`
- WHEN se ejecuta `mvn -B test-compile`
- THEN compila sin errores antes de empujar el commit

### Requirement: Corrección de Fase B guiada por la tabla de decisión

El área del fix MUST determinarse exclusivamente por la evidencia del volcado. Las condiciones
son mutuamente excluyentes: cada una identifica un elemento o estado distinto.

| Evidencia | Área del fix |
|---|---|
| Salió por `EndEvent_Disbursed` sobre el límite | binding del límite / `LedgerGateway` |
| Incidente técnico en `disburse-loan` | ampliar `catch` de `DisbursementWorker`; si no, MUST diferirse |
| Rechazo del ledger sin `Boundary_DisbursementFailed` | propagación del error de negocio |
| `Event_CompensateOrigination` activado sin completar | wiring de compensación del BPMN |
| `COMPLETED` con timestamp anterior a la falla | estrategia de aserción del test, no el modelo |

#### Scenario: Fix de observación conserva ids y severidad
- GIVEN timestamps `COMPLETED` previos a la falla en el volcado
- WHEN se ajusta Fase B
- THEN el cambio MUST modificar solo cómo y cuándo observa el test, mismos ids y severidad, y
  MUST NOT modificar el BPMN

### Requirement: Cierre de Fase B con evidencia real de CI

Fase B MUST cerrarse con un run real: 58/58 tests, `conclusion=success`. Una inferencia local
MUST NOT aceptarse como evidencia de cierre.

#### Scenario: Cierre válido
- GIVEN un run de CI tras la corrección
- WHEN se registra su URL
- THEN reporta 58/58 y `conclusion=success`

### Requirement: Prohibiciones sobre cómo se alcanza el verde

Este cambio MUST NOT introducir `continue-on-error`, exclusiones `-Dtest=`, `@Disabled`, ni
aserciones debilitadas. La fila "Tests de proceso ejecutados" del README MUST permanecer intacta
hasta un run verde real.

#### Scenario: CI verde sin atajos
- GIVEN cualquier commit de este cambio
- WHEN se revisa el workflow y el test
- THEN no aparece `continue-on-error`, `-Dtest=`, `@Disabled`, ni aserciones removidas
