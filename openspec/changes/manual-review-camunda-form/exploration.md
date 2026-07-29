# Exploración — Camunda Form para la revisión manual

Fase: `sdd-explore` · Cambio: `manual-review-camunda-form` · Fecha: 2026-07-28

## Objetivo

Determinar cómo incorporar un Camunda Form a la tarea `Activity_ManualReview` del proceso
`credit-origination`, de modo que el analista de riesgo pueda aprobar o rechazar la solicitud desde
Tasklist, y que la decisión quede persistida y auditable en la base propia.

## Alcance de negocio fijado

Decisiones tomadas por el usuario antes de la exploración. Son límites, no sugerencias.

| Decisión | Valor | Consecuencia técnica |
|---|---|---|
| Facultades del analista | Solo aprobar o rechazar | `Gateway_ReviewOutcome` ya evalúa `reviewApproved`; no se agregan caminos al modelo |
| Comentario | Obligatorio solo al rechazar | El texto debe terminar como `resolutionReason` del agregado |
| Datos visibles | Solo variables que ya viajan en el proceso | Prohibido agregar variables al arranque de la instancia |
| Auditoría | Registrar qué analista decidió | Columna nueva, migración Flyway y campo en el agregado |

La tercera restricción proviene de un principio ya documentado en el README: el motor de procesos no
es una base de datos. Mostrar nombre o ingreso del solicitante exigiría enviarlos como variables al
arranque, duplicando en Zeebe estado que ya vive en Postgres.

## Estado actual

`Activity_ManualReview` es un user task nativo (`<zeebe:userTask />` con
`<zeebe:assignmentDefinition candidateGroups="analistas-riesgo" />`). Hoy no tiene formulario
asociado ni existe ningún mecanismo que la complete: ni el código de producción ni
`CreditOriginationProcessTest` la cierran. El único test que la involucra
(`escala_al_supervisor_cuando_vence_el_sla_de_revision`) verifica el boundary timer de escalado y
deja la tarea activa a propósito.

`ResolveApplicationService.rejectAndNotify(applicationId, decision, reason)` ya existe, pero se
invoca únicamente desde `NotificationWorker`, que construye el motivo con un literal:
`"Rechazado por el analista de riesgo"`. Ese literal es exactamente lo que debe reemplazar el
comentario real del analista.

### Variables disponibles cuando la tarea se activa

| Variable | Origen |
|---|---|
| `applicationId`, `requestedAmount`, `termMonths` | `ZeebeOriginationProcessAdapter.startOrigination` |
| `bureauScore`, `hasActiveDefaults`, `installmentToIncomeRatio` | resultado del job `query-credit-bureau` |
| `riskDecision` | salida del DMN `credit_scoring` |

`applicantName` y `monthlyIncome` **no** son variables de proceso. Son obligatorias en el agregado y
en la entidad JPA, pero nunca se envían a Zeebe. El formulario no puede mostrarlas sin violar la
restricción de alcance.

## Incógnita 1 — Cómo llega la identidad del analista al dominio

La respuesta que sugiere la documentación genérica de Camunda no aplica a este proyecto.

El patrón `job.getUserTask().getAssignee()`, propio de los Task Listeners, pertenece al cliente
unificado `camunda-client-java` (paquete `io.camunda.client`), que **no está en el classpath**. El
proyecto depende de `io.camunda:zeebe-client-java:8.7.6`, fijado transitivamente por
`spring-boot-starter-camunda-sdk`.

El header reservado `io.camunda.zeebe:assignee` solo se puebla cuando el assignee se resuelve por
expresión FEEL en `assignmentDefinition`. Este proceso usa `candidateGroups`: el analista reclama la
tarea manualmente en Tasklist, por lo que ese header no está disponible.

**Vía viable:** `ZeebeClient.newUserTaskQuery()`, disponible en la misma clase que ya inyecta
`ZeebeOriginationProcessAdapter`. Es la REST API v2 expuesta como comando nativo del cliente que ya
está en el proyecto; no requiere agregar dependencias.

### Verificación independiente del orquestador

Las afirmaciones anteriores fueron contrastadas contra el jar de fuentes
`zeebe-client-java-8.7.6-sources.jar`, no solo contra documentación:

| Afirmación | Resultado |
|---|---|
| `ZeebeClient.newUserTaskQuery()` existe | Confirmado: `UserTaskQuery newUserTaskQuery();` |
| `ActivatedJob.getUserTask()` existe | Confirmado que **no** existe |
| `UserTaskFilter.processInstanceKey(Long)` | Confirmado |
| `UserTaskFilter.elementId(String)` | Confirmado |
| `UserTaskFilter.state(String)` | Confirmado |
| `UserTask.getAssignee()` | Confirmado |
| `UserTask.getState()`, `getCompletionDate()` | Confirmados |

## Incógnita 2 — Comentario obligatorio solo al rechazar

Claves reales de form-js para schemaVersion 18:

- `conditional.hide`: expresión FEEL. Para ocultar el comentario al aprobar: `=reviewApproved = true`.
- `validate.required: true`.

El defecto histórico por el cual un campo oculto se validaba igual como requerido
(`bpmn-io/form-js#825`) está cerrado desde septiembre de 2023, muy anterior a form-js 1.15.3 (junio
de 2025), que corresponde a schemaVersion 18. La evidencia es indirecta: no se pudo renderizar el
formulario en vivo.

**Hallazgo más relevante que la clave JSON:** esta validación es del lado del cliente. El motor no la
aplica. Cualquier cliente que complete la tarea directamente —incluidos los tests de este
repositorio, que usan `zeebeClient`— puede enviar `reviewApproved = false` sin comentario. Y
`CreditApplication.reject(String reason)` hoy no valida que `reason` tenga contenido. La regla de
negocio debe blindarse en el dominio, no solo en el `.form`.

## Incógnita 3 — Superficie de cambio

| Archivo | Acción |
|---|---|
| `src/main/resources/forms/manual-review.form` | Crear. schemaVersion 18, sin `$schema` |
| `src/main/resources/models/credit-origination.bpmn` | Agregar `<zeebe:formDefinition formId="manual-review" bindingType="deployment" />` e insertar un service task antes de `Gateway_ReviewOutcome` |
| `adapter/out/process/ProcessModelDeployment.java` | Agregar el `.form` a `@Deployment(resources = {...})` |
| `adapter/in/process/` | Nuevo worker para el job `record-review-decision` |
| `domain/model/CreditApplication.java` | Campo `reviewedBy` y su transición |
| `domain/port/in/ResolveApplicationUseCase.java` y su servicio | Método para registrar la decisión del analista |
| `adapter/in/process/NotificationWorker.java` | Dejar de construir el motivo con un literal cuando el rechazo viene de revisión manual |
| `adapter/out/persistence/CreditApplicationJpaEntity.java` | Columna `reviewed_by`, actualizar `fromDomain` y `toDomain` |
| `src/main/resources/db/migration/V2__add_reviewed_by_to_credit_application.sql` | Crear, sintaxis Postgres |
| `src/test/java/.../CreditOriginationProcessTest.java` | Agregar el `.form` al despliegue y **dos tests nuevos**: aprobación y rechazo tras revisión manual |

Sobre H2 en tests: `application-test.yaml` tiene `flyway.enabled: false` y `ddl-auto: create-drop`,
por lo que la anotación `@Column` es suficiente. No hace falta una migración compatible con H2.

Nota de diseño pendiente: la nueva consulta de assignee es una **lectura**. Agregarla a
`ZeebeOriginationProcessAdapter` contradiría su propio Javadoc, que lo declara "único punto que
conoce la API de Camunda 8 para escribir". Corresponde decidir en diseño si se extiende esa clase o
se crea un adaptador hermano de solo lectura.

## Enfoques comparados

| | A — el service task nuevo persiste todo | B — el service task solo guarda `reviewedBy` |
|---|---|---|
| Descripción | Consulta el assignee, guarda `reviewedBy` y ejecuta el registro de aprobación o rechazo antes del gateway | Guarda `reviewedBy` y pasa el comentario como variable; `NotificationWorker` sigue ejecutando el rechazo |
| A favor | Auditoría inmediata; no depende de cuándo llegue la firma | Menos responsabilidad en el paso nuevo |
| En contra | Hay que evitar que `NotifyRejection` reintente el rechazo con otro motivo; mitigado porque `reject()` ya es idempotente | El motivo real llega tarde si se aprueba y la firma demora; dos lugares tocan el mismo agregado |

**Recomendación: enfoque A**, con `newUserTaskQuery()` sin filtro por `state`. Es la única
combinación que no agrega variables al arranque, no agrega dependencias, usa lo que ya está en el
classpath y persiste la decisión en el mismo instante en que se toma.

## Riesgos

| Severidad | Riesgo | Mitigación |
|---|---|---|
| CRITICAL | Que un campo oculto por `conditional.hide` efectivamente saltee `validate.required` en form-js 1.15.3 no está verificado en vivo, solo por evidencia indirecta | Smoke test explícito en apply o verify antes de dar la regla por garantizada |
| CRITICAL | Los índices que respaldan `newUserTaskQuery()` se llenan de forma asíncrona. Filtrar por `state("COMPLETED")` puede devolver vacío por carrera | No filtrar por `state`: el `assignee` queda fijado cuando el analista reclama la tarea, mucho antes de completarla. El retry del job worker da margen adicional. Documentar en diseño |
| Media | La regla "comentario obligatorio al rechazar" no la aplica el motor | Guarda en el dominio: `reject()` debe exigir un motivo con contenido |
| Media | El literal exacto de `state` en `UserTaskFilter` no se verificó contra una ejecución real | Irrelevante si se adopta la mitigación de no filtrar por `state` |
| Media | `mvn test` sigue bloqueado por Docker 29 frente a Testcontainers 1.20.6 | Solo `mvn test-compile` es ejecutable en esta máquina. Ver `openspec/config.yaml` |

## Conclusión

Listo para la fase de propuesta. Las tres incógnitas quedaron resueltas con evidencia de código
fuente; dos de ellas contradicen lo que sugiere la documentación genérica de Camunda, que asume un
cliente unificado que este proyecto no utiliza. Quedan dos riesgos CRITICAL que solo pueden cerrarse
con ejecución real, y una decisión de diseño abierta sobre dónde ubicar el adaptador de lectura.
