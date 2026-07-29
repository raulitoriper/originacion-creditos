# Exploración: dos fallas reales en CreditOriginationProcessTest

Origen: primer run de CI de la historia del proyecto (run 30454853341, commit `75e3776`). 58 tests,
2 fallas. Los 54 tests de dominio en verde. De los 4 escenarios de proceso —que nunca se habían
ejecutado en ninguna máquina— pasaron 2 y fallaron 2.

## Descartado por inspección (terreno sólido)

Todo esto se verificó leyendo código y quedó **descartado como causa**:

| Candidato | Veredicto |
|---|---|
| Typo en ids de elementos | Descartado. `EndEvent_Disbursed`, `Event_CompensateOrigination`, `Activity_ReleaseFunds`, `EndEvent_DisbursementFailed`, `Activity_ReserveFunds`, `Activity_DisburseLoan` existen verbatim en el BPMN. |
| `errorCode` desalineado | Descartado. La constante `DISBURSEMENT_FAILED` del worker coincide letra por letra con `errorCode` de `<bpmn:error id="Error_DisbursementFailed">`, referenciado por `errorRef` en `Boundary_DisbursementFailed`. |
| Wiring de compensación incorrecto | Descartado. `Boundary_ReserveCompensation` está `attachedToRef="Activity_ReserveFunds"`; la `bpmn:association` conecta con `Activity_ReleaseFunds` (`isForCompensation="true"`); `Event_CompensateOrigination` es un `intermediateThrowEvent` con `compensateEventDefinition`. Estructuralmente correcto. |
| Límite del ledger mal configurado en tests | Descartado. `src/test/resources/application-test.yaml` define `origination.ledger.disbursement-limit: 150000000` y el test pide 160.000.000. Debe rechazar. |
| Monto equivocado al desembolsar | Descartado. `FundApplicationService.disburse()` pasa `application.requestedAmount()` al ledger, no otro campo. |
| Conversión a error de negocio ausente | Descartado para el camino esperado. `DisbursementWorker` captura `LedgerGateway.DisbursementRejectedException` y lanza `ZeebeBpmnError(DISBURSEMENT_FAILED, ...)`. |
| Fuga de estado entre tests | Descartado. Cada test obtiene su `processInstanceKey` desde su propio agregado (`processInstanceKeyOf`, línea 188) y aserta con `byKey(...)` explícito. Que las dos fallas reporten el mismo key `2251799813685259` se explica porque `@CamundaSpringProcessTest` resetea el motor entre tests, así que la primera instancia de cada uno recibe el mismo key. Benigno. |

## Defecto latente real, probado por código

`DisbursementWorker` captura **solo** `LedgerGateway.DisbursementRejectedException`. Pero
`FundApplicationService.disburse()` llama primero a `repository.getById(applicationId)`, que puede
lanzar `IllegalArgumentException("No existe la solicitud ...")`. Esa excepción no se convierte en
`ZeebeBpmnError` y se propaga como fallo técnico. Con `Activity_DisburseLoan` en `retries="1"`, eso
produce un incidente técnico casi inmediato en lugar de disparar la compensación.

Es un defecto real y vale corregirlo, pero **no está probado que sea la causa de la Falla 2**: para
que `getById` falle en el desembolso tendría que fallar después de haber tenido éxito en
`Activity_ReserveFunds` sobre el mismo `applicationId`, y nada en el código borra filas tras el commit.

## Doble escritura (límite ya documentado en el README)

`SubmitCreditApplicationService` guarda el agregado y arranca la instancia en la misma transacción,
así que Zeebe puede activar un job y un worker leer el agregado antes del commit. El punto más
expuesto es `Activity_QueryBureau`: es el primer job de los 4 tests y tiene `retries="3"`, de modo que
un primer intento fallido se autocura sin tumbar nada. Esa es la explicación más consistente para el
`IllegalArgumentException: No existe la solicitud 789bd3e4-...` del log, que no está atribuido a
ningún test en la evidencia disponible.

Real y digno de arreglo, pero **no explica la Falla 2**.

## Lo que queda sin explicación

### Falla 2 — `CreditOriginationProcessTest.java:138`

No se activaron `Event_CompensateOrigination`, `Activity_ReleaseFunds` ni `EndEvent_DisbursementFailed`.
`Activity_ReserveFunds` sí completó. Todos los mecanismos que podrían explicarlo fueron verificados y
están correctos.

Detalle relevante: el error reportado es de `hasCompletedElements`, que en la cadena viene **después**
de `isCompleted()` (línea 137). Si la cadena falla en la primera aserción incumplida, entonces
`isCompleted()` pasó y la instancia completó — lo que implicaría que salió por otro end event, es
decir que el desembolso tuvo éxito. Eso contradice la configuración verificada.

**Advertencia**: esa inferencia asume que `CamundaAssert` corta en la primera aserción fallida. No está
verificado que no acumule fallas o que no reporte solo una de varias. No debe tratarse como hecho.

### Falla 1 — `CreditOriginationProcessTest.java:94`

Solo `EndEvent_Disbursed` no se activó. `Activity_NotifyApproval` completó, y el camino
`Activity_NotifyApproval` → `Flow_Notify_End` → `EndEvent_Disbursed` es directo e incondicional, sin
gateway ni espera. No hay mecanismo plausible en el modelo para que el token se detenga ahí.

## Hipótesis que la exploración no consideró: salud del exportador

En los logs del run aparecen, de forma repetida, errores reales de Elasticsearch:

```
status: 503 ... error.type=SearchPhaseExecutionException, error.message=all shards failed
Caused by: org.elasticsearch.action.NoShardAvailableActionException
io.camunda.operate.zeebeimport.elasticsearch.ElasticsearchRecordsReader - Exception occurred for
alias [zeebe-record-decision-requirements], while obtaining next Zeebe records batch
```

`CamundaAssert` observa el estado de los elementos a través de registros **exportados**. Si el
exportador o los índices estuvieron degradados, un elemento que sí se ejecutó puede aparecer como
«not activated». Eso explicaría ambas fallas con una sola causa, y sin ningún defecto en el modelo ni
en el código.

Matiz que impide darlo por cierto: los errores citados son de índices de decisiones
(`decision-requirements`, `decision-evaluation`), no de instancias de proceso, y dos de los cuatro
tests pasaron sus aserciones de elementos. La hipótesis es seria pero no está probada.

## Corrección de una afirmación previa

Se afirmó que las fallas «no son de infraestructura, y eso está probado», con base en que los
contenedores levantaron y dos tests pasaron. Eso prueba que no hubo un fallo **total** de
infraestructura. **No prueba** que un exportador inestable no haya provocado que las aserciones de
elementos vieran datos incompletos. La afirmación estaba sobredimensionada.

## Próximo paso decisivo: determinismo antes que teoría

Ninguna cantidad de lectura de código puede distinguir entre «defecto real» y «exportador inestable».
Un re-run del mismo commit sí:

- Fallan **las dos, idénticas** → determinista → defecto real, y se investiga con stack trace atribuido.
- Fallan **distintas, o pasan** → no determinista → problema de exportación u observación, y el arreglo
  va sobre la estrategia de espera de las aserciones, no sobre el modelo.

Ese experimento cuesta un re-run y resuelve lo que el diagnóstico estático no puede. Se disparó como
attempt 2 del run 30454853341.

## Prioridad

La Falla 2 primero: toca la compensación de saga, que es la tesis central del README. La Falla 1
después, con alta probabilidad de ser observación y no modelo.

## Prohibido

- `continue-on-error` en el workflow.
- Exclusiones `-Dtest=` para forzar verde.
- Tocar la fila «Tests de proceso ejecutados» del README antes de un run verde real.
