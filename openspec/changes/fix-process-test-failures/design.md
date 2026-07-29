# Design: fix-process-test-failures — Fase A (diagnóstico)

## Enfoque técnico

La causa raíz **no se conoce**. Este diseño no la asume: describe un instrumento de medición. La Fase A
agrega observación de solo lectura en test scope, la deja emitir aun cuando la aserción ya lanzó, y
convierte la salida en una tabla de decisión mecánica. El criterio de aceptación es contraintuitivo y
está fijado por el usuario: **el build debe seguir en BUILD FAILURE con las mismas 2 fallas y los mismos
elementos ausentes**. Un verde en Fase A significa que la instrumentación alteró lo medido.

Separación explícita entre lo verificado y lo abierto:

- **Verificado leyendo archivos y artefactos** (secciones «Decisión 1», «Decisión 5» y «Límites hexagonales»).
- **Solo lo puede cerrar el run de la Fase A** (sección «Lo que el volcado NO va a poder decir»).

---

## Decisión 1 — Qué API expone realmente los datos

**Elección**: la **search API tipada de `io.camunda.zeebe.client.ZeebeClient` 8.7.6** para element
instances, estado de instancia e incidentes; el **impresor de resultados propio de CPT** para variables
de proceso; y el puerto de entrada `FindCreditApplicationUseCase` para el estado del agregado.

### Método de verificación

No hay JDK invocable ni Bash en este contexto, así que no se pudo ejecutar `mvn -B test-compile` ni
descompilar. Lo que sí se pudo hacer: los **nombres de entrada de un zip se almacenan sin comprimir** en
el directorio central, de modo que `rg` confirma presencia o ausencia de clases dentro de los jars ya
resueltos en `C:\Users\rriveros\.m2\repository`. Cada afirmación de abajo es el resultado de una sonda
sobre el jar real de la versión real.

### Presente (verificado)

En `zeebe-client-java-8.7.6.jar`:

| Tipo | Uso en el volcado |
|---|---|
| `io.camunda.zeebe.client.api.search.query.ProcessInstanceQuery` | estado de la instancia, `processDefinitionVersion` |
| `io.camunda.zeebe.client.api.search.query.FlownodeInstanceQuery` | element instances con estado y timestamps |
| `io.camunda.zeebe.client.api.search.query.IncidentQuery` | incidentes con `errorType` y mensaje |
| `io.camunda.zeebe.client.api.search.response.ProcessInstance` | ídem |
| `io.camunda.zeebe.client.api.search.response.FlowNodeInstance` | ídem |
| `io.camunda.zeebe.client.api.search.response.Incident` | ídem |
| `io.camunda.zeebe.client.api.search.response.SearchQueryResponse` | acceso a los items |
| `...search.filter.ProcessInstanceFilter` / `FlownodeInstanceFilter` / `IncidentFilter` | filtro por `processInstanceKey` |

En `camunda-process-test-java-8.7.6.jar`: `io.camunda.process.test.api.CamundaAssert`,
`...api.CamundaProcessTestContext`, `...impl.assertions.ElementAssertj` (la clase del stack trace),
`...impl.assertions.CamundaDataSource`, `...impl.client.{FlowNodeInstanceDto, ProcessInstanceDto,
IncidentDto, VariableDto}`, `...impl.testresult.{CamundaProcessTestResultCollector,
CamundaProcessTestResultPrinter, ProcessInstanceResult, OpenIncident}`.

En `camunda-process-test-spring-8.7.6.jar`: `io.camunda.process.test.api.CamundaSpringProcessTest` y
`io.camunda.process.test.api.CamundaProcessTestExecutionListener` — nótese que el listener vive en el
paquete **`api`**, dato que la Decisión 5 usa.

### Ausente (verificado)

| Ausencia | Consecuencia de diseño |
|---|---|
| El artefacto `camunda-client-java` no está en `.m2`; no existe `io.camunda.client.CamundaClient` | se confirma que en 8.7 el cliente es `ZeebeClient`. Nada del volcado lo menciona |
| No existen `...search.query.VariableQuery` ni `...search.response.Variable` | **en 8.7.6 no hay búsqueda de variables por cliente**. Las variables de proceso no se obtienen por esta vía; ver Decisión 5 |
| `io.camunda.process.test.impl.client.CamundaDataSource` (probado, no existe con ese paquete) | el `CamundaDataSource` real está en `impl.assertions`; queda como plan de segunda ronda, no se usa en Fase A |

### Alternativas rechazadas

| Alternativa | Motivo del rechazo |
|---|---|
| `io.camunda.process.test.impl.assertions.CamundaDataSource` | es la fuente más rica (incluye variables), pero es API interna y no se pueden verificar constructor ni firmas sin compilar. Se reserva como fallback de segunda ronda si las variables resultan el dato decisivo |
| Reconstruir el estado con sondas repetidas de `CamundaAssert` | solo API pública, pero no da incidentes, ni `processDefinitionVersion`, ni timestamps, y **agrega espera con reintento**, que es exactamente lo que no se puede introducir |
| HTTP crudo contra el endpoint REST de Operate/v2 | requiere descubrir la dirección y armar un cliente JSON: más código y más modos de falla que el cliente tipado ya autowired |

### Lo que NO se pudo verificar, y su mitigación

Los **nombres de método** viven en el constant pool comprimido y no son legibles sin JDK. Quedan sin
verificar `newFlownodeInstanceQuery()`, `newProcessInstanceQuery()`, `newIncidentQuery()`, la forma
`filter(f -> f.processInstanceKey(k)).send().join().items()` y los accessors de las respuestas. El costo
de errar es **una compilación, no un run**, y la puerta es `mvn -B test-compile` local antes del push.

Dos trampas concretas a esperar en esa compilación:

1. **Asimetría de nombres real y verificada**: la query se llama `Flownode…` y la respuesta `FlowNode…`.
2. El accessor de estado puede devolver un enum: el volcado debe imprimir con `String.valueOf(...)` para
   no acoplarse a un tipo enumerado que no se pudo verificar.

---

## Decisión 2 — Dónde vive la instrumentación

**Elección**: clase nueva `src/test/java/com/rriveros/origination/support/ProcessDiagnostics.java`,
utilitaria y **estática**, sin anotaciones de Spring. Recibe el `ZeebeClient` ya autowired como
parámetro.

**Alternativas consideradas**: un `@TestComponent`/`@Component` en test scope, o una clase en
`src/main` reutilizable.

**Justificación**: un bean entraría al application context del test y podría alterar el orden de
arranque, es decir, podría cambiar lo que se mide. Una clase estática invocada desde el test no puede.
`src/main` está excluido por la propuesta y por el plan de rollback: la Fase A debe revertirse con un
`git revert` que no toque producción.

### Límites hexagonales (`rules.design`)

`ProcessDiagnostics` observa el estado runtime del adaptador de orquestación y vive por completo en test
scope. Importa `io.camunda.*`, que ya es el lenguaje de frontera de `adapter/in/process` y
`adapter/out/process`, y **no importa nada de `domain/`**. La sección del agregado la produce el test
usando el puerto de entrada `FindCreditApplicationUseCase` que ya tiene autowired; el helper nunca
alcanza el repositorio. Así la instrumentación no cruza de observación-de-adaptador a acceso-a-dominio.

---

## Decisión 3 — Cómo no alterar lo medido

Invariantes que la implementación debe sostener. No son recomendaciones: son el contrato de la Fase A.

1. **Solo consultas.** Nada de `newSetVariablesCommand`, `newPublishMessageCommand`, activación de jobs,
   `increaseTime` ni despliegues.
2. **Sin espera condicional.** Ni Awaitility, ni bucle de reintento hasta éxito, ni `CamundaAssert`
   dentro del helper. El volcado no espera a que una condición se cumpla, por lo tanto no puede
   convertir un rojo en verde.
3. **Nunca lanza.** Todo el cuerpo va envuelto y cualquier falla se imprime como línea `DIAG-ERROR`. Una
   excepción del diagnóstico dentro de `@AfterEach` agregaría una falla espuria y podría desplazar la
   real, rompiendo el criterio «las mismas 2 fallas».
4. **Camino de código uniforme** para los 4 tests, pasen o fallen. Sin ramas condicionales: atribución
   limpia, y los 2 tests que pasan quedan como **grupo de control en el mismo formato**.
5. Todo ocurre **después** de que la aserción ya lanzó y de que la instancia ya completó (`isCompleted()`
   pasa en ambas fallas), así que ningún token puede moverse como consecuencia del volcado.

### El punto central: distinguir ventana truncada de token que nunca llegó

**Dos disparos, ambos impresos.**

- **T1**: al entrar a `@AfterEach`, sin demora. Dato importante: `CamundaAssert` ya estuvo haciendo
  polling durante todo su assertion timeout antes de lanzar, así que «ausente en T1» ya significa
  «ausente durante al menos ese timeout después de que la instancia completó».
- **T2**: tras un único `Thread.sleep` fijo e incondicional de 3 s (`Duration.ofSeconds(3)`), mismo
  conjunto de consultas.

Lectura del contraste:

| Observación | Lectura |
|---|---|
| Sufijo ausente en T1 **y** presente en T2 | la vista exportada va detrás del motor: el defecto está en cómo y cuándo observa el test |
| Sufijo ausente en T1 **y** en T2 | los elementos no tienen registro exportado en ~13 s; los incidentes, el end event de salida y el agregado dicen a dónde fue el token |
| Sufijo **ya presente** en T1 | el registro existía cuando mirábamos y no cuando miró `CamundaAssert`: apunta a la ruta de selector/filtro, y exonera tanto al modelo como al exportador |

Costo: 3 s fijos por test, ~12 s sobre una clase que hoy tarda 227 s. No es reintento y no es
condicional, y eso es lo que impide que sea una mitigación disfrazada.

---

## Decisión 4 — Cómo emite el volcado cuando la aserción falla

**Elección**: `@AfterEach void dumpDiagnostics(TestInfo testInfo)` en `CreditOriginationProcessTest`, más
registro de las process instance keys observadas.

**Justificación**:

- JUnit 5 ejecuta `@AfterEach` **aunque el método de test haya lanzado**, así que los 2 tests que fallan
  quedan cubiertos. Es exactamente el requisito «debe emitir bajo `AssertionError`».
- El `afterTestMethod` de Spring —donde CPT imprime su resultado y resetea el motor— corre **después** de
  todos los `@AfterEach` de JUnit. En el momento del volcado el contenedor y la vista exportada están
  intactos y todavía sin resetear.
- Cero registro de extensiones nuevas, cero cambios en las cadenas de aserción, cero cambio de severidad.

**Detalle de registro**: `processInstanceKeyOf(applicationId)` (línea 187) ya es el único punto por donde
los 4 tests obtienen su key. Registrar ahí cubre los 4 escenarios con una línea agregada.

**Alternativas rechazadas**:

| Alternativa | Motivo |
|---|---|
| `try/catch` alrededor de cada cadena y rethrow (redacción de la propuesta) | toca 6 cadenas de aserción, es fácil equivocarse y arriesga tragarse la falla. Misma información con más riesgo |
| Extensión `TestWatcher` / `AfterTestExecutionCallback` | también dispara en falla y además entrega el `Throwable`, pero ese texto ya está en el reporte de surefire y en el log de CI. Un archivo más, un registro más, ningún dato más |
| Soft assertions de AssertJ o `assertThatCode` | cambia la severidad de la aserción. Descartado por decisión del usuario |

---

## Decisión 5 — El cambio de nivel de log

### Hallazgo que reencuadra el problema

Los stack traces de Elasticsearch y las líneas `io.camunda.operate.zeebeimport...` que ya aparecen en el
log de CI vienen de **dentro del contenedor `camunda/camunda:8.7.6`**, transmitidos como texto plano por
Testcontainers. Operate no está en el classpath de este proyecto, así que esas líneas **no pueden** ser
loggers de la JVM del test. Conclusión: `logging.level.io.camunda: WARN` nunca las suprimió, y tampoco
suprime el logging de incidentes del broker. La afirmación de la propuesta de que ese setting oculta
«activaciones de job, reintentos e incidentes» es cierta solo para los del **lado cliente**.

### Qué sí oculta y conviene destapar

- `io.camunda.process.test.api.CamundaProcessTestExecutionListener` — el impresor de resultados propio de
  CPT (`CamundaProcessTestResultPrinter` + `CamundaProcessTestResultCollector`, ambos verificados en
  8.7.6) loguea a INFO. Es la fuente más barata de **variables de proceso** e incidentes abiertos, que el
  cliente 8.7.6 no puede consultar. Máximo valor por línea cambiada.
- `io.camunda.zeebe.spring.client.jobhandling` — invocación de jobs y manejo de fallas de comando de los
  workers de la aplicación.

### Qué debe seguir suprimido

`io.camunda.zeebe.client.impl.worker.JobPoller` (verificado presente) loguea a DEBUG en cada poll de cada
worker. Con 7 métodos `@JobWorker` sobre ~227 s son miles de líneas y sepultarían el volcado. Por eso se
**descarta** el `io.camunda: DEBUG` global que sugería la propuesta.

**Elección**: mantener `io.camunda: WARN` y agregar dos overrides con alcance acotado.

```yaml
logging:
  level:
    io.camunda: WARN                                    # se conserva: evita el ruido del JobPoller
    io.camunda.process.test: DEBUG                      # habilita el impresor de resultados de CPT
    io.camunda.zeebe.spring.client.jobhandling: DEBUG   # invocacion y falla de jobs de la app
```

**Nota verificada**: subir `com.rriveros.origination` no aporta nada. **Ninguna clase de
`adapter/in/process` tiene logger**. El comportamiento de los workers solo es visible a través de los
incidentes del motor, no de logs de aplicación. Agregar logging a los workers exigiría tocar `src/main`,
que la Fase A excluye.

---

## Decisión 7 — Formato de salida

- Un bloque por instancia, abierto por `=== DIAG BEGIN <testName> pik=<key> shot=T1 ===` y cerrado por el
  `DIAG END` correspondiente. Toda línea interior con prefijo fijo `DIAG |`. El buscador del visor de
  Actions es por línea: un prefijo fijo hace recuperable todo el volcado con una sola búsqueda aunque se
  intercale con la salida del contenedor, y el par BEGIN/END lo acota.
- A `System.out`, **una impresión por línea**, no un string multilínea gigante: el visor trunca mal las
  líneas largas, y el intercalado con el stdout del contenedor es por línea de todos modos.
- Secciones en orden fijo, para que dos volcados se puedan diferenciar a ojo:
  `INSTANCE` (estado, `processDefinitionId`/`Key`, `processDefinitionVersion`, `startDate`, `endDate`) →
  `ELEMENTS` (una línea por element instance: `elementId | state | start | end | key`) →
  `INCIDENTS` (`elementId | errorType | errorMessage | state | creationTime`) →
  `EXPECTED` (los ids que el test asertó, cada uno marcado `OK`/`MISSING` contra `ELEMENTS`) →
  `AGGREGATE` (`status`, `reservationId`, `disbursementId`).
- **Orden calculado en el cliente** (por `startDate`, desempate por key), no con la sort API de la query:
  una superficie de API sin verificar menos, y las colecciones son diminutas.
- La sección `EXPECTED` es lo que vuelve mecánica la tabla de decisión: el lector no tiene que
  cruzar datos con el mensaje de surefire.
- Estados impresos con `String.valueOf(...)`; timestamps tal como los devuelve la API. Sin formateo
  ingenioso que pueda esconder un `null`.

---

## Decisión 6 — Tabla de decisión Fase A → Fase B

Se refina la tabla de la propuesta para que cada condición sea una **observación literal en el volcado**.
Se evalúa **de arriba hacia abajo, gana la primera coincidencia**: así son exclusivas en la práctica sin
pretender que las causas subyacentes lo sean.

| # | Condición observable en el volcado | Lectura | Área del fix |
|---|---|---|---|
| 1 | `ELEMENTS` de T1 no contiene el sufijo y `ELEMENTS` de T2 sí | la vista exportada va detrás del motor | observación del test (`CamundaAssert.setAssertionTimeout` / punto de aserción). No se toca el BPMN |
| 2 | `ELEMENTS` de T1 **ya** contiene el sufijo | el registro existía cuando mirábamos, no cuando miró `CamundaAssert` | ruta de selector/filtro de la aserción; segunda ronda dirigida a `ElementAssertj` |
| 3 | `INCIDENTS` con entrada en `Activity_DisburseLoan` y `errorType` técnico (distinto de `UNHANDLED_ERROR_EVENT`) | la excepción no se convirtió en error de negocio | `DisbursementWorker`: activa el defecto latente diferido |
| 4 | `INCIDENTS` con `UNHANDLED_ERROR_EVENT` en `Activity_DisburseLoan` | el `errorCode` no resuelve contra `Boundary_DisbursementFailed` en runtime | `errorCode` / `errorRef` en runtime |
| 5 | En el test 2, `INSTANCE.state=COMPLETED` y `ELEMENTS` marca `EndEvent_Disbursed` completado | el ledger no rechazó 160.000.000 | binding de `origination.ledger.disbursement-limit` / `InMemoryLedgerAdapter` |
| 6 | `Event_CompensateOrigination` presente en `ELEMENTS` con estado distinto de `COMPLETED` | la compensación no resuelve su handler | wiring de compensación en el BPMN |
| 7 | En el test 2, `Activity_DisburseLoan` en `COMPLETED` y `AGGREGATE.disbursementId` no nulo | el desembolso tuvo éxito: el escenario no ejercita la compensación | supuesto del escenario (monto o límite) |
| 8 | Ninguna fila anterior aplica | el volcado no cerró el diagnóstico | ampliar la instrumentación (segunda ronda) **antes** de intentar un fix |

Las filas 1–2 afirman que el sufijo **está** en algún disparo; las filas 3–7 describen caminos donde
**no está** y por qué. Esa es la línea de corte que las hace exclusivas. Las filas 5 y 7 se solapan
conceptualmente; la 5 se evalúa primero porque es la afirmación más fuerte.

La fila 8 no es relleno: es la salida pactada de antemano, para que una segunda ronda de diagnóstico sea
una rama planificada y no una sorpresa.

---

## Flujo de datos

```
CreditOriginationProcessTest
  |  (1) processInstanceKeyOf() -> registra pik
  |  (2) CamundaAssert...       -> AssertionError (se propaga intacta)
  v
@AfterEach dumpDiagnostics(TestInfo)
  |
  +--> ProcessDiagnostics.dump(zeebeClient, test, pik, expectedIds, aggregate)
  |        T1 --> search API de ZeebeClient --> vista exportada (Zeebe -> ES -> REST)
  |        sleep 3 s fijo
  |        T2 --> misma consulta
  |        `--> System.out (bloques DIAG)
  |
  +--> findApplication.findById() --> H2   (seccion AGGREGATE)

luego, Spring: CamundaProcessTestExecutionListener.afterTestMethod
  `--> CamundaProcessTestResultPrinter (INFO, desbloqueado por el nivel de log)
  `--> reset del motor
```

---

## Cambios de archivos y pronóstico de líneas

| Archivo | Acción | Contenido | Líneas |
|---|---|---|---|
| `src/test/java/com/rriveros/origination/support/ProcessDiagnostics.java` | Crear | helper estático: 2 disparos, 5 secciones, nunca lanza, javadoc con el contrato de invariantes | ~120 |
| `src/test/java/com/rriveros/origination/CreditOriginationProcessTest.java` | Modificar | lista de keys observadas + 1 línea en `processInstanceKeyOf` + `@AfterEach dumpDiagnostics(TestInfo)` + imports. Ni un id ni una aserción cambian | ~30 |
| `src/test/resources/application-test.yaml` | Modificar | 2 overrides de nivel acotados (línea 22 se conserva) | ~3 |
| **Total Fase A** | | | **~153** |

`400-line budget risk: Low`. No hace falta preguntar al usuario por tamaño. **Sí** se mantiene la
separación en PRs por **secuencia**: la Fase B no puede escribirse hasta leer el run de la Fase A.

Sin cambios en `src/main`, BPMN, DMN, `pom.xml`, `README.md`, `.github/workflows/ci.yml` ni
`openspec/changes/ci-and-domain-tests/`.

---

## Estrategia de testing

| Capa | Qué se verifica | Cómo |
|---|---|---|
| Unit | nada nuevo | `ProcessDiagnostics` es instrumentación; un test unitario de un formateador de logs sería ceremonia |
| Compilación | que toda API usada exista en 8.7.6 | `mvn -B test-compile` local antes del push: la única puerta local disponible, porque Docker bloquea `mvn test` |
| Integración | que el volcado emita para las 2 fallas y **no** cambie el resultado | run real de CI: debe seguir en BUILD FAILURE con las mismas 2 fallas y los mismos elementos ausentes, y con 4 bloques DIAG × 2 disparos en el log |

**Nota sobre `strict_tdd: true`**: la Fase A no agrega comportamiento de producción ni aserciones
nuevas. No hay test RED que escribir: un test que asertara «el volcado existe» estaría asertando sobre
salida de log, evidencia más débil que el propio run de CI. Se declara explícitamente en lugar de
omitirse en silencio.

---

## Threat Matrix

No hay routing, shell, subprocesos, automatización de VCS/PR ni clasificación de archivos ejecutables.
La única frontera de integración es una lectura contra un contenedor de test ya en ejecución, usando el
cliente que el test ya tenía autowired. Filas aplicables:

| Fila | Aplicabilidad | Comportamiento esperado |
|---|---|---|
| Dependencia degradada (la search API responde 503 o timeout) | **Aplicable** — es el escenario bajo sospecha | invariante 3: se imprime `DIAG-ERROR` con la excepción y el test conserva su resultado original. Nunca se propaga |
| Falla parcial (una consulta responde, otra no) | **Aplicable** | cada sección se consulta y se imprime por separado; una sección vacía se marca explícitamente, no se omite |
| Routing / shell / subprocesos / VCS / archivos ejecutables | **N/A** | el cambio no introduce ninguna de esas fronteras |

---

## Migración / rollout

Sin migración. Fase A entrega sola y se revierte con `git revert`; toca únicamente `src/test/**`. El
despliegue del modelo ocurre desde el classpath en cada test (`@BeforeEach deployModels()`, única vía
porque `ProcessModelDeployment` es `@Profile("!test")`) y **no se elimina ni se altera**. Sin estado de
motor persistente entre runs de CI, así que no hay versiones que deshacer.

---

## Lo que el volcado NO va a poder decir

Esto es un límite del instrumento, no pesimismo. Una segunda ronda de diagnóstico es una posibilidad
conocida.

1. **No distingue «nunca existió» de «nunca se exportó».** Nuestro volcado y `CamundaAssert` leen la
   **misma** vista exportada. Si Elasticsearch perdió el registro de forma permanente, T1 y T2 salen
   vacíos por igual y las filas 3–7 se evaluarían sobre datos incompletos. Separar eso exigiría leer el
   log propio del broker o el estado de la partición, fuera del alcance de la Fase A.
2. **Puede no mostrar variables de proceso.** El cliente 8.7.6 **no tiene** `VariableQuery` (verificado).
   Las variables dependen de que el impresor de CPT dispare. Si resultan el dato decisivo, es segunda
   ronda con el `CamundaDataSource` interno.
3. **No dice por qué un worker se comportó como se comportó.** Ninguna clase de `adapter/in/process`
   loguea, y la Fase A no toca `src/main`. Solo se ve la consecuencia en el motor (incidente o
   completado), nunca la rama interna del worker.
4. **No reconstruye historia.** La search API devuelve estado actual: un elemento activado y luego
   terminado muestra solo su estado final. Los timestamps mitigan esto, no lo reemplazan por un event log.
5. **Sobre `deployModels()` solo reporta `processDefinitionVersion`.** Si la versión es > 1 el volcado lo
   señala, pero probar que la instancia corrió contra una versión distinta de la asertada exigiría otra
   ronda.
6. **No dice nada sobre la doble escritura** de `SubmitCreditApplicationService`, límite conocido y fuera
   de alcance.

---

## Preguntas abiertas

- [ ] `mvn -B test-compile` es la única forma de confirmar los nombres de método de la search API 8.7.6.
      Este entorno no tiene JDK invocable; la fase de apply debe correrlo **antes** de cualquier push, y
      corregir la asimetría `Flownode`/`FlowNode` si aparece.
- [ ] No se pudo verificar a qué nivel loguea `CamundaProcessTestResultPrinter`. Se asume INFO y se pone
      `io.camunda.process.test` en DEBUG para cubrir INFO y DEBUG. Si igual no aparece, las variables de
      proceso quedan como hueco declarado (límite 2).
- [ ] Los 3 s de T2 son un valor elegido, no medido. Si T1 y T2 salen idénticos y vacíos, el run no
      permite descartar que un T3 más tardío sí viera los registros.
