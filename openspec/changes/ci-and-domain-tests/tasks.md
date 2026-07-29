# Tasks: CI y tests de dominio

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 243-315 (domain tests 200-250, guarda de `reject()` 5-8, workflow 30-45, README 8-12) |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | PR único |
| Delivery strategy | ask-always |
| Chain strategy | pending (no aplica: forecast Low, cabe en un PR único) |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: pending
400-line budget risk: Low

No hay decisión de encadenamiento pendiente: el forecast cabe holgado en el presupuesto de 400
líneas con un solo PR. La única decisión real que sigue pendiente es externa al alcance de tasks:
que el usuario complete el prerrequisito de la Tarea 1.1 antes de que la Fase 4 pueda observar un
run de Actions.

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| 1 | Tests de dominio + guarda de motivo en `reject()` | PR 1 | `mvn -B test -Dtest=CreditApplicationTest` | N/A — sin Docker, corre completo en esta máquina | Revertir `CreditApplicationTest.java` y la guarda en `CreditApplication.java` sin tocar CI/README |
| 2 | Workflow de CI (`mvn test` completo, JDK 21) | PR 1 | N/A local (Docker bloqueado) | Push a `main`/PR y observar el run en GitHub Actions | Borrar `.github/workflows/ci.yml`; aditivo y aislado |
| 3 | Evidencia en README (badge + fila real) | PR 1 | N/A (doc) | Requiere el run verde de la Unidad 2 como evidencia | Revertir el diff de `README.md`, sin dependencias de código |

## Phase 1: Prerrequisito externo

- [ ] 1.1 (Propietario: usuario, no el implementador) Autenticar la cuenta personal de GitHub y confirmar que el remoto ya wireado acepta push. Bloqueante para la Fase 4 — sin push no hay runs de Actions.

## Phase 2: Tests de dominio — comportamiento existente (verificable localmente, sin cambios de producción)

- [x] 2.1 Crear `src/test/java/.../domain/model/CreditApplicationTest.java` con `@Nested Submit` (creación válida e inválida) y `@Nested Calculos` (`monthlyInstallment()` escala 2 HALF_UP, `installmentToIncomeRatio()` escala 4 HALF_UP).
- [x] 2.2 Agregar `@Nested RecordScreening`, `RecordRiskDecision` (incl. `NullPointerException` en decisión nula), `EscalateReview`, `ReserveFunds`, `MarkDisbursed`, `ReleaseFunds` (incl. idempotencia en `REVERTED`).
- [x] 2.3 Agregar `@Nested IllegalTransitions` con `@ParameterizedTest` sobre pares (estado, operación) cubriendo la tabla completa de `InvalidApplicationStateException`.
- [x] 2.4 Ejecutar `mvn -B test -Dtest=CreditApplicationTest` y confirmar verde — establece la línea base antes de tocar `reject()`.

## Phase 3: TDD — guarda de motivo en `reject()` (único cambio de conducta productiva, RED→GREEN local)

- [x] 3.1 RED: agregar `@Nested Reject` con "rechazo válido" y "rechazo idempotente" (deben pasar ya) más "motivo vacío/en blanco/nulo" esperando `IllegalArgumentException` (deben fallar hoy).
- [x] 3.2 Ejecutar `mvn -B test -Dtest=CreditApplicationTest` y confirmar que los 3 escenarios de motivo fallan por la razón correcta (ninguna excepción lanzada todavía).
- [x] 3.3 GREEN: en `CreditApplication.java`, agregar `if (reason == null || reason.trim().isEmpty()) throw new IllegalArgumentException(...)` como primera sentencia de `reject()`, antes del corto-circuito de idempotencia.
- [x] 3.4 Ejecutar `mvn -B test -Dtest=CreditApplicationTest` y confirmar la suite de dominio completa en verde.

## Phase 4: Pipeline de CI (verificable SOLO en CI — depende del push de la Tarea 1.1)

- [x] 4.1 Crear `.github/workflows/ci.yml`: job `build`, triggers `push` a `main` + `pull_request` a `main`, `concurrency` con `cancel-in-progress`, `permissions: contents: read`.
- [x] 4.2 Steps del job: `actions/checkout`, `actions/setup-java` (JDK 21 explícito, `cache: maven`), y `mvn -B test` completo, sin `-Dtest=` ni `continue-on-error`.
- [ ] 4.3 Confirmar que la Tarea 1.1 se completó y observar el primer run del workflow tras el push — cubre `CreditOriginationProcessTest`, imposible de correr localmente por el bloqueo de Docker.
- [ ] 4.4 Si el run falla, aplicar el protocolo de clasificación del diseño (infraestructura vs. defecto de proceso/dominio) antes de continuar; no avanzar a la Fase 5.

## Phase 5: README — bloqueado hasta evidencia real de un run verde

- [ ] 5.1 [BLOQUEADO hasta 4.3 en verde] Reemplazar en `README.md:144` la fila `Tests de proceso ejecutados | ❌ no` por el resultado real del último run, con enlace al run.
- [ ] 5.2 [BLOQUEADO hasta 4.3 en verde] Agregar badge de CI en `README.md` sobre `main`, apuntando a `https://github.com/raulitoriper/originacion-creditos/actions/workflows/ci.yml`.
