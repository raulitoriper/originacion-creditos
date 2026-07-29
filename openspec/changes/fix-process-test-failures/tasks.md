# Tasks: fix-process-test-failures

## Review Workload Forecast

| Archivo | Acción | Líneas est. |
|---|---|---|
| `src/test/java/.../support/ProcessDiagnostics.java` | Crear | ~120 |
| `src/test/java/.../CreditOriginationProcessTest.java` | Modificar | ~30 |
| `src/test/resources/application-test.yaml` | Modificar | ~3 |
| **Fase A, total** | | **~153** |
| Fase B | Contingente (área definida por el volcado) | 40-200 |

Decision needed before apply: Yes
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: Low

Nota: la cadena no es por tamaño (Fase A ya está bajo presupuesto). Es por secuencia obligatoria:
Fase B no puede escribirse hasta leer el run real de Fase A. `delivery_strategy: ask-always` exige
confirmar con el usuario la estrategia de cadena antes de aplicar, aunque el riesgo de tamaño sea bajo.

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|---|---|---|---|---|---|
| 1 | Fase A: instrumentación diagnóstica de solo lectura | PR1 → main | `mvn -B test-compile` (local) | Run de CI de `CreditOriginationProcessTest` — Docker-bloqueado localmente, CI-only | `git revert` del commit único; toca solo `src/test/**` y `application-test.yaml` |
| 2 | Fase B: corrección en el área que indique la tabla de decisión | PR2, base = main tras merge de PR1 | `mvn -B test-compile` (local) | Run de CI esperando 58/58 y `conclusion=success` — CI-only | `git revert` del commit del fix; sin migración de motor |

## Phase 1: Fase A — Instrumentación de diagnóstico (test scope, PR1)

- [x] 1.1 Crear `ProcessDiagnostics.java`: helper estático, sin Spring, recibe `ZeebeClient` autowired por parámetro. Dos disparos (T1 inmediato, T2 tras `Thread.sleep(Duration.ofSeconds(3))`); 5 secciones (`INSTANCE`, `ELEMENTS`, `INCIDENTS`, `EXPECTED`, `AGGREGATE`) entre marcadores `DIAG BEGIN/END`, líneas `DIAG |`. Nunca lanza: envuelve todo, falla imprime `DIAG-ERROR`. Trampa de compilación: query `FlownodeInstanceQuery`, respuesta `FlowNodeInstance` (capitalización distinta); no existe `VariableQuery`/`search.response.Variable` en 8.7.6, no depender de eso. (Spec: Volcado diagnóstico de Fase A). Verificable: solo local por compilación.
- [x] 1.2 Modificar `CreditOriginationProcessTest.java`: registrar la key en `processInstanceKeyOf()` (línea 187); agregar `@AfterEach void dumpDiagnostics(TestInfo)` que invoca `ProcessDiagnostics.dump(...)` por key con ids esperados y estado del agregado vía `FindCreditApplicationUseCase` ya autowired. Sin cambios a ids ni aserciones existentes. (Spec: Volcado diagnóstico de Fase A). Verificable: solo local por compilación.
- [x] 1.3 Modificar `application-test.yaml`: agregar `io.camunda.process.test: DEBUG` y `io.camunda.zeebe.spring.client.jobhandling: DEBUG`; conservar `io.camunda: WARN` sin cambios. (Spec: Log del motor en DEBUG durante tests). Verificable: local por inspección.

## Phase 2: Compuerta de verificación local (obligatoria antes de push)

- [x] 2.1 Ejecutar `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && "C:/Users/rriveros/scoop/apps/maven/current/bin/mvn.cmd" -B test-compile`; confirmar sin errores. (Spec: Verificación asimétrica). Verificable: 100% local.
- [ ] 2.2 Si falla por nombres no verificados (`newFlownodeInstanceQuery()`, forma `filter(...).send().join().items()`, accessors), corregir contra el jar real, prestando atención a la asimetría `Flownode`/`FlowNode`, y repetir 2.1. (Spec: Verificación asimétrica). Verificable: 100% local. **No aplicó**: 2.1 compiló sin errores en el primer intento (los nombres se verificaron con `javap` contra el jar real antes de escribir el código, ver apply-progress).

## Phase 3: Fase A — Verificación de CI (PR1, CI-only)

- [ ] 3.1 Abrir PR1; confirmar por `git diff --stat` que solo toca `src/test/**`. (Spec: Prohibiciones). Verificable: local.
- [ ] 3.2 Disparar el run de CI y registrar su URL. (Spec: Verificación asimétrica). Verificable: CI-only.
- [ ] 3.3 Leer el log: confirmar `BUILD FAILURE`, mismas 2 fallas y elementos ausentes que `75e3776`. Si reporta `conclusion=success`, descartar la corrida como evidencia y no avanzar. (Spec: Fase A no debe alterar el resultado medido). Verificable: CI-only.
- [ ] 3.4 Leer qué emitió `CamundaProcessTestResultPrinter`/`CamundaProcessTestResultCollector` en el mismo run y registrar si duplicó variables/incidentes ya cubiertos por los bloques `DIAG`. (Spec: Volcado diagnóstico de Fase A). Verificable: CI-only.
- [ ] 3.5 Clasificar cada una de las 2 fallas contra la tabla de decisión (Diseño Decisión 6), de arriba hacia abajo, primera coincidencia gana; registrar fila y área del fix. (Spec: Corrección de Fase B guiada por la tabla de decisión). Verificable: CI-only.

## Phase 4: Fase B — Corrección contingente (PR2, solo tras leer 3.5)

- [x] 4.1 Implementar el fix únicamente en el área que indicó 3.5; no presuponer causa ni empezar antes de tener esa clasificación. (Spec: Corrección de Fase B guiada por la tabla de decisión). Verificable: local (compilación) + CI (comportamiento). **Clasificación** (a partir del run PR1/`0de59c1`, sección `AGGREGATE`: `REVERTED` con ambos ids nulos y `DISBURSED` con `disbursementId` real en las 4 instancias — el proceso terminó correctamente en los 4 escenarios): fila "COMPLETED con timestamp anterior a la falla" (tabla de decisión de `spec.md`) / fila 1 de `design.md` Decisión 6 ("`ELEMENTS` de T1 no contiene el sufijo y `ELEMENTS` de T2 sí" — patrón consistente con lo observado). Área del fix: estrategia de aserción del test, no el modelo. Local (compilación): confirmado. CI (comportamiento): **no verificable en este batch**, ver Phase 4.5/4.6.
- [x] 4.2 Fila aplicable: "timestamp anterior a la falla". Ajustado solo cómo y cuándo observa el test: `CamundaAssert.setAssertionTimeout(Duration.ofSeconds(20))` en un `@BeforeAll` de `CreditOriginationProcessTest`, verificado con `javap` contra `camunda-process-test-java-8.7.6.jar` antes de escribir el código (`setAssertionTimeout` invoca `Awaitility.setDefaultTimeout`; default `DEFAULT_ASSERTION_TIMEOUT` = 10 s; confirmado que `CamundaProcessTestExecutionListener` no resetea ese valor entre tests, solo `CamundaAssert.initialize`/`reset` del data source). Mismos ids, misma severidad de asercion, BPMN sin tocar. (Spec: Corrección de Fase B guiada por la tabla de decisión). Verificable: local (compilación, hecho) + CI (comportamiento — **CI-only, no verificable aquí**).
- [ ] 4.3 Fila aplicable: no aplica. La sección `AGGREGATE` del run PR1 (`REVERTED` con `reservationId`/`disbursementId` nulos, producido solo por `releaseFunds()` tras `FundsReleaseWorker`) prueba que la compensación ejecutó de punta a punta; un incidente técnico en `disburse-loan` habría impedido esa compensación. **Diferido a un cambio de seguimiento propio**, no se ejecuta en este cambio; `DisbursementWorker` no se toca. (Spec: Prohibiciones / defecto diferido). Verificable: local + CI. **No aplicó** por la razón anterior.
- [x] 4.4 Ejecutar la compuerta de 2.1 antes de cualquier push del fix. Verificable: 100% local. Ejecutado tras los cambios de Fase B: `BUILD SUCCESS` (ver Work Unit Evidence).
- [ ] 4.5 Abrir PR2 (base: main tras merge de PR1) y disparar el run de CI. (Spec: Cierre de Fase B con evidencia real de CI). Verificable: CI-only. **Pendiente — acción del orquestador, no de este batch de apply.**
- [ ] 4.6 Confirmar cierre válido: 58/58, `conclusion=success`, registrar URL; una inferencia local no se acepta como evidencia. (Spec: Cierre de Fase B con evidencia real de CI). Verificable: CI-only. **Pendiente — depende de 4.5.**
- [x] 4.7 Confirmar que ningún commit de A o B introdujo `continue-on-error`, `-Dtest=`, `@Disabled` ni aserciones debilitadas. (Spec: Prohibiciones sobre cómo se alcanza el verde). Verificable: local por inspección del diff. Confirmado por inspección de `git diff`: sin `continue-on-error`, sin `-Dtest=`, sin `@Disabled`, sin `assumeTrue`, ningún id ni aserción de elemento removida o debilitada — el único cambio de comportamiento de aserción es ampliar el timeout global de `CamundaAssert`.

## Phase 5: Cierre (bloqueado hasta el run verde real)

- [ ] 5.1 No tocar la fila «Tests de proceso ejecutados» del README hasta 4.6 cerrado en verde; queda fuera de alcance de este checklist y de `ci-and-domain-tests` fase 5. (Spec: Prohibiciones). Verificable: local por inspección.
