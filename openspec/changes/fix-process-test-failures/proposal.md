# Propuesta: fix-process-test-failures

**Decisión central: diagnóstico primero, corrección contingente.** La causa raíz no se conoce y la
lectura estática está agotada. El primer entregable no es un arreglo: es instrumentación que haga que
el test reporte lo que realmente ocurrió. El alcance de la corrección se define con la salida de ese
run, no antes.

## Estado del conocimiento

| Afirmación | Estado |
|---|---|
| Las 2 fallas son deterministas (reproducción elemento por elemento en dos attempts del commit `75e3776`) | Probado |
| Exportador de Elasticsearch **intermitente** como causa | Refutado por el experimento |
| `isCompleted()` pasa en ambas fallas; la excepción salta en `ElementAssertj.hasCompletedElements` | Probado (stack trace, ambos attempts) |
| Los end events son observables en general (`EndEvent_Rejected` se ve completado en el test que pasa) | Probado |
| El factor común es la firma: los 2 tests que fallan llaman `signApplication.sign(...)`, los 2 que pasan no | Probado |
| Ids de elementos, `errorCode`, wiring de compensación, límite del ledger en el perfil de test, monto enviado, conversión a `ZeebeBpmnError`, fuga de estado entre tests | Descartados por inspección |
| **Por qué la instancia completa sin que se registre el end event final** | Abierto |
| Brecha de observación **sistemática** (no intermitente) | Abierto. El determinismo no la descarta: en ambas fallas los elementos ausentes son exactamente un **sufijo** del camino esperado (el último en la falla 1, los últimos tres en la falla 2). Ese patrón es más compatible con una ventana de observación truncada que con un token que se desvía. Señal, no conclusión. |
| `@BeforeEach deployModels()` | No hay configuración de despliegue en `src/main/resources`, así que es la única vía de despliegue y no debe eliminarse. Zeebe deduplica recursos idénticos por checksum, por lo que no se espera proliferación de versiones. Se confirma imprimiendo `processDefinitionVersion` en el volcado, sin costar un run extra. |

## Intención

Las 2 fallas bloquean la única evidencia end-to-end de la compensación de saga, que es la tesis
central del README, y mantienen bloqueada la fase 5 de `ci-and-domain-tests`. El test solo corre en
CI (Docker Engine 29.3.1 vs Testcontainers 1.20.6 devuelve HTTP 400 en esta máquina) y cada run cuesta
~4 min. Con presupuesto de información escaso, gastar runs en arreglos a ciegas es la peor estrategia
disponible.

## Alcance

### Incluido

1. **Fase A — diagnóstico (se entrega sola).** Volcado, en el log de CI, del estado real observado
   para las 4 instancias: cada element instance con estado y timestamp, incidentes con mensaje y
   `errorType`, estado de la instancia y end event por el que salió, `processDefinitionVersion`,
   variables relevantes, y estado del agregado (`status`, `reservationId`, `disbursementId`).
2. **Fase A — logs del motor.** Subir `io.camunda` de `WARN` a `DEBUG` en el perfil de test para que
   activaciones de job, reintentos e incidentes queden en el log del run.
3. **Fase B — corrección, contingente.** Área definida por la tabla de decisión de abajo.

### Excluido

- Ampliar el `catch` de `DisbursementWorker` en la Fase A. El defecto latente es real
  (captura solo `DisbursementRejectedException`, con `retries="1"` en `Activity_DisburseLoan`), pero
  cambiar comportamiento en el mismo run en que se mide destruye la atribución del resultado. Entra en
  Fase B **solo si** el volcado muestra un incidente técnico en `disburse-loan`; en caso contrario pasa
  a un cambio de seguimiento propio.
- La doble escritura de `SubmitCreditApplicationService`: límite ya documentado, no explica las fallas.
- Tocar el workflow. El volcado va a stdout, que CI ya captura.
- La fila «Tests de proceso ejecutados» del README: la actualiza `ci-and-domain-tests` fase 5 tras un
  verde real.

## Capabilities

### New Capabilities

- `credit-origination-saga`: comportamiento observable del proceso de originación end-to-end —
  aprobación automática y desembolso, rechazo por mora, escalada por SLA, y compensación de la reserva
  cuando el desembolso es rechazado. Fija qué significa «correcto» para que la Fase B tenga un objetivo
  de aceptación en lugar de «que el test pase».

### Modified Capabilities

- Ninguna. `build-verification-pipeline` conserva sus requisitos; este cambio los cumple, no los altera.

## Enfoque

Fase A no cambia ninguna aserción ni ningún id: envuelve las cadenas de aserción para que el volcado
se emita **antes** de que la falla se propague, y el test siga fallando. Un verde en Fase A sería
señal de que se alteró lo que se estaba midiendo.

### Tabla de decisión Fase A → Fase B

| Evidencia en el volcado | Causa | Área del fix |
|---|---|---|
| La instancia del test 2 salió por `EndEvent_Disbursed` | el ledger no rechazó 160.000.000 | binding del límite / adaptador `LedgerGateway` |
| Incidente técnico en `disburse-loan` | excepción no convertida a error de negocio | `DisbursementWorker` (defecto latente confirmado) |
| Sin registro de `Boundary_DisbursementFailed` pese a rechazo del ledger | el error de negocio no se propagó | worker o `errorCode` en runtime |
| `Event_CompensateOrigination` activado y sin completar | la compensación no resuelve el handler | wiring de compensación en el BPMN |
| Los elementos constan como `COMPLETED` con timestamp anterior a la falla | ventana de observación truncada | estrategia de aserción (`CamundaAssert.setAssertionTimeout`), no el modelo |

## Áreas afectadas y pronóstico de líneas

| Área | Impacto | Líneas |
|---|---|---|
| `src/test/java/com/rriveros/origination/support/ProcessDiagnostics.java` | Nuevo | ~110 |
| `src/test/java/com/rriveros/origination/CreditOriginationProcessTest.java` | Modificado | ~35 |
| `src/test/resources/application-test.yaml` | Modificado | ~4 |
| **Fase A, total** | | **~150** |
| Fase B | Contingente | 40–200 |

Presupuesto de 400 líneas: **Fase A, riesgo bajo**. Fase A y Fase B **deben ir en PRs separados**, no
por tamaño sino por secuencia: la Fase B no puede escribirse hasta leer el run de la Fase A. Si el
usuario pidiera un único PR con A+B, el pronóstico pasa a medio/alto y la decisión es suya, no de esta
propuesta.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| El volcado no captura el dato decisivo y se gasta un run sin cerrar el diagnóstico | Media | Volcar estado completo de elementos, incidentes, variables, versión de definición y agregado, no solo lo sospechado |
| `io.camunda: DEBUG` infla el log y dificulta encontrar el volcado | Media | Delimitar el volcado con marcadores buscables por test |
| La instrumentación enmascara la falla y CI pasa a verde sin arreglo | Baja | Criterio de éxito explícito: la Fase A debe seguir en BUILD FAILURE con las mismas 2 fallas |
| La Fase B necesita más de un run | Media | La tabla de decisión se resuelve con el volcado; si queda ambigua, se amplía la instrumentación antes de intentar un fix |

## Plan de rollback

- **Fase A**: toca únicamente `src/test/**` y el logging del perfil de test. `git revert` del commit
  restaura el estado exacto; `src/main`, el BPMN, el DMN, `pom.xml`, el README y el workflow quedan
  intactos por construcción.
- **Fase B**: `git revert` del commit del fix. Si el fix toca el BPMN, el revert también lo restaura:
  el despliegue ocurre desde el classpath en cada test y no hay estado de motor persistente entre runs
  de CI, así que no hay migración de versiones que deshacer.
- Ningún paso requiere revertir el README ni el workflow, porque ninguno se modifica.

## Dependencias

- CI es el único ejecutor de `CreditOriginationProcessTest`. Toda verificación de proceso exige un run
  real.
- `ci-and-domain-tests` fase 5 (README y badge) permanece bloqueada hasta que este cambio produzca
  verde. Este cambio no la desbloquea por sí mismo ni toca su carpeta.

## Criterios de éxito

- [ ] Un run de CI real emite el volcado con estado y timestamp de cada element instance, incidentes y
      end event de salida para las 4 instancias. Evidencia: URL del run registrada.
- [ ] El volcado permite clasificar cada falla en exactamente una fila de la tabla de decisión, sin
      inferencia adicional.
- [ ] La Fase A **no** cambia el resultado del build: sigue en BUILD FAILURE con las mismas 2 fallas y
      los mismos elementos ausentes.
- [ ] Fase B: run de CI real con 58/58 en verde y `conclusion=success`, URL registrada. No se acepta
      inferencia local como evidencia.
- [ ] `CreditOriginationProcessTest` sigue asertando `hasCompletedElements` sobre los mismos ids. Sin
      `-Dtest=`, sin `continue-on-error`, sin `@Disabled`, sin aserciones debilitadas.
- [ ] La fila «Tests de proceso ejecutados» del README permanece intacta hasta el verde real.
- [ ] `mvn -B test-compile` en verde localmente antes de cada push.

## Ronda de preguntas de propuesta

Pendiente de revisión del usuario antes de pasar a specs y design.

1. ¿Se acepta gastar un run de CI en un diagnóstico que **no** intenta arreglar nada, a cambio de
   evitar varios runs a ciegas?
2. Si el volcado mostrara que el modelo y los workers están correctos y el problema es la estrategia
   de aserción, ¿el arreglo aceptable es ajustar la observación del test —manteniendo los mismos ids y
   la misma severidad de aserción— o se exige un cambio en el modelo?
3. El defecto latente de `DisbursementWorker`: ¿se acepta diferirlo a un cambio propio si el volcado no
   lo implica, o se prefiere corregirlo en la Fase B de todos modos por ser un riesgo de saga real?
4. ¿Basta con que el volcado quede en el log del run, o se requiere además persistirlo como artefacto
   descargable de CI (implicaría tocar el workflow, hoy fuera de alcance)?
