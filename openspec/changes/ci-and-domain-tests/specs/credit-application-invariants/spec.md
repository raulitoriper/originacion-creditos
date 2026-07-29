# Credit Application Invariants Specification

## Purpose

Fija en tests JUnit, sin dependencia de Docker, las invariantes de negocio del agregado
`CreditApplication`: validaciones de creación, cálculos financieros, máquina de estados,
idempotencia y la nueva guarda de motivo obligatorio al rechazar.

## Requirements

### Requirement: Creación válida de una solicitud

El sistema MUST rechazar `submit(...)` cuando el ingreso mensual no es mayor a cero, el monto
solicitado no es mayor a cero, o el plazo está fuera de `[3, 120]` meses. El sistema MUST fijar el
estado inicial en `SUBMITTED` cuando los tres valores son válidos.

#### Scenario: Valores inválidos rechazados
- GIVEN ingreso ≤ 0, monto ≤ 0, o plazo fuera de `[3, 120]`
- WHEN se invoca `submit(...)`
- THEN el sistema lanza `IllegalArgumentException`

#### Scenario: Creación válida
- GIVEN ingreso, monto y plazo válidos
- WHEN se invoca `submit(...)`
- THEN la solicitud creada tiene estado `SUBMITTED`

### Requirement: Cálculos financieros con escala fija

El sistema MUST calcular `monthlyInstallment()` como monto ÷ plazo, escala 2, `HALF_UP`. El sistema
MUST calcular `installmentToIncomeRatio()` como `monthlyInstallment()` ÷ ingreso mensual, escala 4,
`HALF_UP`.

#### Scenario: Redondeo de cuota mensual
- GIVEN un monto y plazo cuya división exacta exige redondeo hacia arriba
- WHEN se invoca `monthlyInstallment()`
- THEN el resultado tiene escala 2 con `HALF_UP` aplicado

#### Scenario: Redondeo de relación cuota/ingreso
- GIVEN una cuota mensual e ingreso cuya división exige redondeo en el quinto decimal
- WHEN se invoca `installmentToIncomeRatio()`
- THEN el resultado tiene escala 4 con `HALF_UP` aplicado

### Requirement: Registro de bureau (screening)

El sistema MUST permitir `recordScreening` solo desde `SUBMITTED` y MUST transicionar a `SCREENED`.

#### Scenario: Screening válido
- GIVEN una solicitud en `SUBMITTED`
- WHEN se invoca `recordScreening(report)`
- THEN el estado pasa a `SCREENED`
- AND `bureauScore()` y `hasActiveDefaults()` reflejan el reporte

### Requirement: Registro de decisión de riesgo sin cambio de estado

El sistema MUST permitir `recordRiskDecision` solo desde `SCREENED` o `PENDING_REVIEW`, MUST NOT
modificar el estado, y MUST rechazar una decisión nula.

#### Scenario: Decisión registrada sin cambio de estado
- GIVEN una solicitud en `SCREENED`
- WHEN se invoca `recordRiskDecision(decision)` con decisión no nula
- THEN `riskDecision()` refleja la decisión
- AND el estado sigue siendo `SCREENED`

#### Scenario: Decisión nula rechazada
- GIVEN una solicitud en `SCREENED` o `PENDING_REVIEW`
- WHEN se invoca `recordRiskDecision(null)`
- THEN el sistema lanza `NullPointerException`

### Requirement: Escalamiento a revisión manual

El sistema MUST permitir `escalateReview` solo desde `SCREENED` o `PENDING_REVIEW`, y MUST dejar el
estado en `PENDING_REVIEW`.

#### Scenario: Escalamiento válido
- GIVEN una solicitud en `SCREENED`
- WHEN se invoca `escalateReview()`
- THEN el estado pasa a `PENDING_REVIEW`

### Requirement: Reserva de fondos

El sistema MUST permitir `reserveFunds` solo desde `SCREENED` o `PENDING_REVIEW`, y MUST
transicionar a `FUNDS_RESERVED`.

#### Scenario: Reserva válida
- GIVEN una solicitud en `PENDING_REVIEW`
- WHEN se invoca `reserveFunds(reservationId)`
- THEN el estado pasa a `FUNDS_RESERVED`
- AND `reservationId()` refleja el identificador recibido

### Requirement: Desembolso

El sistema MUST permitir `markDisbursed` solo desde `FUNDS_RESERVED`, y MUST transicionar a
`DISBURSED`.

#### Scenario: Desembolso válido
- GIVEN una solicitud en `FUNDS_RESERVED`
- WHEN se invoca `markDisbursed(disbursementId)`
- THEN el estado pasa a `DISBURSED`

### Requirement: Liberación de fondos idempotente

El sistema MUST permitir `releaseFunds` solo desde `FUNDS_RESERVED` o `DISBURSED`, MUST anular
`reservationId` y `disbursementId`, y MUST transicionar a `REVERTED`. El sistema MUST tratar
`releaseFunds` como no-op cuando el estado ya es `REVERTED`.

#### Scenario: Liberación válida
- GIVEN una solicitud en `FUNDS_RESERVED` con `reservationId` asignado
- WHEN se invoca `releaseFunds(reason)`
- THEN el estado pasa a `REVERTED`
- AND `reservationId()` y `disbursementId()` son nulos

#### Scenario: Liberación idempotente
- GIVEN una solicitud ya en `REVERTED`
- WHEN se invoca `releaseFunds(reason)` de nuevo
- THEN el estado permanece `REVERTED` sin lanzar excepción

### Requirement: Rechazo idempotente con motivo obligatorio

El sistema MUST permitir `reject` solo desde `SUBMITTED`, `SCREENED` o `PENDING_REVIEW`, y MUST
transicionar a `REJECTED`. El sistema MUST tratar `reject` como no-op cuando el estado ya es
`REJECTED`. El sistema MUST exigir un `reason` que, tras `trim()`, no sea vacío ni nulo; en ese caso
MUST lanzar una excepción de dominio sin modificar el estado.
(Previamente: `reject(String reason)` no validaba el contenido de `reason` — aceptaba `null` y cadena
vacía sin error, `CreditApplication.java:145-154`.)

#### Scenario: Rechazo válido con motivo
- GIVEN una solicitud en `SUBMITTED`
- WHEN se invoca `reject("Score insuficiente")`
- THEN el estado pasa a `REJECTED`
- AND `resolutionReason()` refleja el motivo

#### Scenario: Rechazo idempotente
- GIVEN una solicitud ya en `REJECTED`
- WHEN se invoca `reject(reason)` de nuevo con cualquier motivo
- THEN el estado permanece `REJECTED` sin lanzar excepción

#### Scenario: Motivo vacío o en blanco rechazado
- GIVEN una solicitud en `SUBMITTED`, `SCREENED` o `PENDING_REVIEW`
- WHEN se invoca `reject("")` o `reject("   ")`
- THEN el sistema lanza una excepción de dominio
- AND el estado no cambia

#### Scenario: Motivo nulo rechazado
- GIVEN una solicitud en `SUBMITTED`, `SCREENED` o `PENDING_REVIEW`
- WHEN se invoca `reject(null)`
- THEN el sistema lanza una excepción de dominio
- AND el estado no cambia

Nota de compatibilidad: el único llamador productivo de `reject(...)` en `src/` es
`ResolveApplicationService` (línea 42), alimentado por `NotificationWorker.reasonFor(RiskDecision)`
(línea 37), que siempre devuelve un literal no vacío en ambas ramas. Esta guarda no puede romper los
4 tests de proceso existentes.

### Requirement: Excepción de dominio en transiciones ilegales

El sistema MUST lanzar `InvalidApplicationStateException` — no `IllegalStateException` ni otra
excepción genérica — para toda transición invocada fuera de los estados permitidos.

| Operación | Estados permitidos |
|---|---|
| `recordScreening` | `SUBMITTED` |
| `recordRiskDecision` | `SCREENED`, `PENDING_REVIEW` |
| `escalateReview` | `SCREENED`, `PENDING_REVIEW` |
| `reserveFunds` | `SCREENED`, `PENDING_REVIEW` |
| `markDisbursed` | `FUNDS_RESERVED` |
| `releaseFunds` | `FUNDS_RESERVED`, `DISBURSED` (más `REVERTED` como no-op) |
| `reject` | `SUBMITTED`, `SCREENED`, `PENDING_REVIEW` (más `REJECTED` como no-op) |

#### Scenario: Tipo de excepción consistente por operación
- GIVEN cualquiera de las operaciones anteriores invocada desde un estado fuera de su lista permitida
- WHEN se captura la excepción lanzada
- THEN su tipo es `InvalidApplicationStateException`
