# Apply Progress: CI y tests de dominio

**Modo**: Strict TDD (activo)
**Alcance de este batch**: Fases 2, 3 y 4 (Fase 1 y Fase 5 fuera de alcance, ver `tasks.md`)

## Tareas completadas

- [x] 2.1 `CreditApplicationTest` con `@Nested Submit` y `@Nested Calculos`
- [x] 2.2 `@Nested RecordScreening`, `RecordRiskDecision`, `EscalateReview`, `ReserveFunds`, `MarkDisbursed`, `ReleaseFunds`
- [x] 2.3 `@Nested IllegalTransitions` parametrizado sobre la tabla completa de transiciones ilegales
- [x] 2.4 Suite de dominio verde antes de tocar `reject()`
- [x] 3.1 RED: `@Nested Reject` con casos de motivo vacío/en blanco/nulo esperando `IllegalArgumentException`
- [x] 3.2 Confirmado: los 3 escenarios de motivo fallan por la razón correcta (ninguna excepción lanzada)
- [x] 3.3 GREEN: guarda de motivo agregada como primera sentencia de `reject()`
- [x] 3.4 Suite de dominio completa en verde (54/54)
- [x] 4.1 `.github/workflows/ci.yml` creado: job `build`, triggers `push`/`pull_request` a `main`, `concurrency` con `cancel-in-progress`, `permissions: contents: read`
- [x] 4.2 Steps: `actions/checkout`, `actions/setup-java` (JDK 21, `cache: maven`), `mvn -B test` completo

## Tareas pendientes (fuera de alcance de este batch)

- [ ] 1.1 (usuario) — autenticación de GitHub, bloqueante externo
- [ ] 4.3 / 4.4 — observación del primer run real en CI, depende de 1.1
- [ ] 5.1 / 5.2 — README, bloqueado hasta evidencia real de un run verde

## Archivos modificados

| Archivo | Acción | Descripción |
|---|---|---|
| `src/test/java/com/rriveros/origination/domain/model/CreditApplicationTest.java` | Creado | 54 tests: `@Nested` por operación, `@ParameterizedTest` para las 34 transiciones ilegales, cero imports de Spring/JPA/Camunda |
| `src/main/java/com/rriveros/origination/domain/model/CreditApplication.java` | Modificado | Guarda de motivo (`reason == null \|\| reason.trim().isEmpty()`) como primera sentencia de `reject()`, antes del corto-circuito de idempotencia — lanza `IllegalArgumentException` |
| `.github/workflows/ci.yml` | Creado | Job único `build`, JDK 21 explícito vía `setup-java`, `mvn -B test` completo sin exclusiones |
| `openspec/changes/ci-and-domain-tests/tasks.md` | Modificado | Marcadas `[x]` las tareas 2.1-2.4, 3.1-3.4, 4.1-4.2 |

## TDD Cycle Evidence

| Task | Test File | Layer | Safety Net | RED | GREEN | TRIANGULATE | REFACTOR |
|------|-----------|-------|------------|-----|-------|-------------|----------|
| 2.1-2.3 | `CreditApplicationTest.java` | Unit | N/A (new) | ✅ Escritos como caracterización de conducta existente | ✅ 50/50 pasan en la primera corrida | ✅ 34 casos parametrizados + múltiples happy/edge por operación | ➖ No aplica — son characterization tests, no refactor de producción |
| 3.1-3.4 | `CreditApplicationTest.java` ($Reject) | Unit | ✅ 50/50 (resto de la suite) | ✅ Escrito antes de tocar `reject()` | ✅ 54/54 tras agregar la guarda | ✅ 3 casos (vacío, en blanco, nulo) + caso "guarda total en REJECTED" | ➖ None needed — guarda de una línea, ya sigue el estilo de `submit()` |

Nota sobre caracterización (Fase 2): siguiendo el approval-testing flow del módulo strict-tdd, los tests de
comportamiento existente se escribieron y ejecutaron de inmediato en verde — eso pin-ea la conducta actual,
no es una falla del ciclo RED→GREEN. El único cambio de conducta productiva de todo el change es la guarda
de `reject()` (Fase 3), y esa sí siguió RED→GREEN real y verificado por ejecución.

### Test Summary
- **Total tests escritos**: 54 (`CreditApplicationTest`)
- **Total tests en verde (corrida final)**: 54/54
- **Capas usadas**: Unit (54), Integration (0 — fuera de alcance de este batch), E2E (0)
- **Approval tests** (Fase 2, caracterización): 50
- **Funciones puras cubiertas**: `monthlyInstallment()`, `installmentToIncomeRatio()`

## Work Unit Evidence

| Evidence | Valor |
|---|---|
| Comando de test enfocado y resultado exacto | `mvn -B test -Dtest=CreditApplicationTest` → RED: `Tests run: 54, Failures: 4` (3 motivos + guarda total en REJECTED, razón correcta: "Expecting code to raise a throwable"). GREEN tras el fix: `Tests run: 54, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS` |
| Comando/escenario de runtime y resultado exacto | N/A para Fase 2/3 — sin Docker, corre completo en esta máquina (justificado: agregado sin colaboradores). Para Fase 4: N/A local — el workflow solo se puede verificar con un push real a GitHub, evidencia fuera del alcance de este batch |
| Rollback boundary | Revertir `CreditApplicationTest.java`, la guarda de 3 líneas en `CreditApplication.reject()` y `.github/workflows/ci.yml` de forma aislada — ninguno depende de README ni de otros archivos de producción |

Verificación adicional: `mvn -B test-compile` → `BUILD SUCCESS` (0.99s, "Nothing to compile - all classes are up
to date" tras la corrida de test previa), confirma que `CreditOriginationProcessTest` sigue compilando sin
cambios. **No se ejecutó `mvn test` completo** por instrucción explícita: `CreditOriginationProcessTest`
requiere Testcontainers/Docker y el Docker Engine 29.3.1 de esta máquina devuelve HTTP 400; esa verificación
queda para CI (Fase 4, tarea 4.3, bloqueada por el prerrequisito externo de la Fase 1).

El archivo `.github/workflows/ci.yml` no se puede verificar localmente en absoluto: ningún runner de GitHub
Actions se ejecuta en esta máquina. Queda pendiente de la tarea 4.3 tras el push del usuario.

## Desviaciones del diseño

Ninguna. La guarda de `reject()` implementa el contrato literal de `design.md` (`IllegalArgumentException`,
antes del corto-circuito de idempotencia, sin trim-normalizar el valor almacenado). El workflow sigue
exactamente la topología de la decisión 5 (un solo job) y los triggers/caché de la decisión 7.

## Riesgos

Ninguno nuevo. El único llamador productivo de `reject(...)` (`ResolveApplicationService:42`, vía
`NotificationWorker.reasonFor(RiskDecision)`) siempre entrega un literal no vacío en ambas ramas — la guarda
es aditiva y no puede romper los 4 tests de proceso existentes (no ejecutados en este batch, ver arriba).

## Estado

10/16 tareas totales completas (Fases 2, 3 y 4.1-4.2). Listo para verify sobre el alcance asignado
(Fases 2-4, con 4.3/4.4 pendientes del push externo). Fase 1 (usuario) y Fase 5 (README) quedan
explícitamente fuera de este batch.
