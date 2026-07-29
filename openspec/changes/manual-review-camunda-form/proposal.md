# Propuesta: Camunda Form para la revisión manual

El analista de riesgo aprobará o rechazará desde Tasklist, y la decisión —con su autor y su motivo
real— quedará persistida en Postgres. Hoy `Activity_ManualReview` no tiene formulario ni mecanismo
que la complete.

## Intención

| Hoy | Después |
|---|---|
| La tarea queda activa indefinidamente | El analista decide en Tasklist |
| El motivo de rechazo es un literal en `NotificationWorker` | El comentario del analista llega a `resolutionReason` |
| No se registra quién decidió | Columna `reviewed_by` auditable |

## Alcance

### Incluido
- `manual-review.form`: schemaVersion 18, sin `$schema`, `bindingType="deployment"`.
- Solo variables que ya viajan en el proceso, más `reviewApproved` y `reviewComment`.
- Service task `record-review-decision` antes de `Gateway_ReviewOutcome`, con worker propio.
- Assignee vía `ZeebeClient.newUserTaskQuery()`, **sin filtro por `state`**.
- `reviewedBy` en el agregado, la entidad JPA y una migración Flyway `V2`.
- Dos tests de proceso: aprobación y rechazo tras revisión manual.

### Excluido
- Contraoferta, pedido de documentación, caminos nuevos en el modelo.
- `applicantName` y `monthlyIncome`: exigirían variables al arranque, contra el principio "el motor
  no es una base de datos".
- Task Listeners y `camunda-client-java`: fuera del classpath.
- Migración compatible con H2: en tests rige `ddl-auto: create-drop`.
- Guarda de dominio que exige motivo con contenido en `reject()`: la posee el cambio
  `ci-and-domain-tests`. Esta propuesta la consume, no la implementa.

## Capacidades

**Nuevas**
- `manual-review-form`: campos, visibilidad condicional y datos permitidos.
- `review-decision-audit`: autor de la decisión y persistencia. La obligatoriedad del motivo la
  aporta `ci-and-domain-tests`.

**Modificadas**: ninguna. `openspec/specs/` está vacío.

## Enfoque

Enfoque A de la exploración: el worker consulta el assignee, guarda `reviewedBy` y ejecuta el
registro de aprobación o rechazo antes del gateway. Auditoría inmediata, y `reject()` ya es
idempotente, así que un reintento de `NotifyRejection` no corrompe el estado.

**Decisión abierta, para `sdd-design`**: la consulta de assignee es una lectura, y
`ZeebeOriginationProcessAdapter` se declara en su Javadoc "único punto que conoce la API de Camunda 8
para escribir". Alternativas: extender esa clase o crear un adaptador hermano de solo lectura. Esta
propuesta no la resuelve.

## Áreas afectadas

| Área | Impacto |
|---|---|
| `resources/forms/manual-review.form` | Nuevo |
| `resources/models/credit-origination.bpmn` | `formDefinition` + service task |
| `resources/db/migration/V2__add_reviewed_by_to_credit_application.sql` | Nuevo, Postgres |
| `adapter/in/process/` | Worker nuevo |
| `adapter/out/process/ProcessModelDeployment.java` | `.form` en `@Deployment` |
| `domain/model/CreditApplication.java` | `reviewedBy` |
| `domain/port/in/ResolveApplicationUseCase.java` + `ResolveApplicationService.java` | Método nuevo |
| `adapter/in/process/NotificationWorker.java` | Sin literal si el rechazo viene de revisión |
| `adapter/out/persistence/CreditApplicationJpaEntity.java` | Columna y mapeos |
| `test/.../CreditOriginationProcessTest.java` | Despliegue del `.form` y 2 tests |

Estimación: ~330 líneas, dentro del presupuesto de 400. Un solo PR.

## Riesgos

| Sev. | Riesgo | Mitigación |
|---|---|---|
| CRITICAL | Que `conditional.hide` saltee `validate.required` en form-js 1.15.3 no está verificado en vivo; la evidencia es indirecta (issue cerrado en 2023) | Smoke test explícito; la regla se blinda además en el dominio |
| CRITICAL | Los índices de `newUserTaskQuery()` se pueblan de forma asíncrona; filtrar por `state("COMPLETED")` puede devolver vacío por carrera | No filtrar por `state`: el assignee se fija al reclamar la tarea |
| Media | El motor no aplica la validación del formulario: cualquier cliente puede completar sin comentario | La guarda de `reject()` que aporta `ci-and-domain-tests` valida el motivo |
| Media | `@Variable` sobre variable ausente devuelve `null`, no falla | Validar explícitamente en el worker |
| Media | `mvn test` no es ejecutable en esta máquina (Docker 29 frente a Testcontainers 1.20.6) | Solo `mvn test-compile`; el comportamiento en runtime queda pendiente de verificación real |

## Rollback

Revertir el commit y redesplegar. La migración `V2` solo agrega una columna anulable, deshecha con
`ALTER TABLE ... DROP COLUMN reviewed_by`. Como el `.form` usa `bindingType="deployment"`, las
instancias vivas siguen atadas a la versión con la que arrancaron.

## Dependencias

Ninguna dependencia externa nueva: todo se apoya en `zeebe-client-java` 8.7.6, ya en el classpath.

Dependencia interna: la guarda de motivo en `reject()` la entrega el cambio `ci-and-domain-tests`.
Este cambio la consume y su spec no debe volver a especificarla.

## Criterios de éxito

- [ ] Aprobar en Tasklist lleva la solicitud a firma; rechazar la deja rechazada.
- [ ] El `resolutionReason` del rechazo es el texto del analista, no un literal.
- [ ] `reviewed_by` guarda el assignee de la tarea.
- [ ] `mvn test-compile` en verde; la verificación en runtime queda pendiente por el bloqueo de Docker.
