# Propuesta: CI y tests de dominio

El README afirma cuatro tests contra un Zeebe real y dos líneas después admite que nunca corrieron.
Este cambio reemplaza esa fila `❌` por evidencia ejecutada, y de paso fija por primera vez las
invariantes del agregado en tests que no dependen de Docker.

## Intención

| Hoy | Después |
|---|---|
| Cero tests unitarios; las invariantes de `CreditApplication` viven solo en el código | Suite JUnit sin Docker que las congela |
| `reject()` acepta motivo `null` o vacío (`CreditApplication.java:145-154`) | Guarda de dominio: un rechazo sin motivo no es auditable |
| Sin CI; `mvn test` bloqueado en esta máquina por Docker Engine 29.3.1 | Workflow que ejecuta la suite completa sobre el Docker 28.0.4 del runner |
| Tabla de verificación con `❌ Tests de proceso ejecutados` | Fila con evidencia real y badge de estado |

## Alcance

### Incluido
- Tests unitarios de `CreditApplication`: guardas de `submit`, escala y redondeo de
  `monthlyInstallment` e `installmentToIncomeRatio`, máquina de estados completa, idempotencia de
  `releaseFunds` y `reject`, y que toda transición ilegal lance `InvalidApplicationStateException`.
- Guarda en `reject(String reason)`: exige motivo con contenido, más su test.
- `.github/workflows/ci.yml`: JDK 21 explícito vía `actions/setup-java` (el default de
  `ubuntu-latest` es 17) y `mvn test` completo, **sin excluir** los tests de proceso.
- `README.md`: fila de verificación corregida y badge de estado de CI.

### Excluido
- **Pin de `testcontainers-bom`** → cambio de seguimiento aparte. Su compatibilidad con
  `camunda-process-test-java` 8.7.6 no está verificada y no puede estar en el camino crítico de este
  entregable.
- **Guarda de estado en `attachProcessInstance`** y **chequeo de `report` nulo en `recordScreening`**
  → mismo cambio de seguimiento. Ninguna regla de negocio los exige hoy: ningún llamador del proceso
  puede pasar `null` a `recordScreening`, y definir los estados legales de `attachProcessInstance` es
  una decisión de ciclo de vida, no un hueco de test. Meterlos aquí sería un cambio de conducta sin
  verificación en el cambio cuyo propósito es producir verificación.
- Camunda Form (lo posee `manual-review-camunda-form`), patrón outbox, reconciliación de expiración
  de firma, adaptadores reales de bureau y ledger, autenticación: límites ya declarados en el README.

## Capacidades

**Nuevas**
- `credit-application-invariants`: máquina de estados, cálculos financieros, idempotencia y la regla
  de motivo obligatorio al rechazar.
- `build-verification-pipeline`: ejecución automatizada de la suite con toolchain fijado y evidencia
  publicada en el README.

**Modificadas**: ninguna. `openspec/specs/` está vacío.

## Enfoque

Enfoque 3 de la exploración: tests de dominio más CI que corre la suite entera. El bloqueo de
Testcontainers es local, no del runner: la imagen `ubuntu-24.04` `20260720.247.2` declara Docker
Server 28.0.4, dentro del rango que el README documenta como funcional. Excluir los tests de proceso
desperdiciaría justamente la evidencia que motiva el cambio.

La guarda de `reject()` es segura para el proceso actual: el único llamador productivo es
`NotificationWorker.reasonFor(...)`, que siempre devuelve un literal no vacío (verificado). La guarda
no puede romper los cuatro tests de proceso existentes.

## Áreas afectadas

| Área | Impacto | Líneas est. |
|---|---|---|
| `src/test/java/.../domain/model/CreditApplicationTest.java` | Nuevo | 200–250 |
| `domain/model/CreditApplication.java` (`reject`) | Modificado | 5–8 |
| `.github/workflows/ci.yml` | Nuevo | 30–45 |
| `README.md` | Modificado | 8–12 |

Total 243–315 líneas: **un solo PR**, con holgura frente al presupuesto de 400.

## Coordinación con `manual-review-camunda-form`

Ese cambio (propuesta lista, spec pendiente) lista en su alcance «Guarda de dominio: `reject()` exige
motivo con contenido» y la incluye en su capacidad `review-decision-audit`. Esa guarda pasa a ser
propiedad de este cambio. **Acción requerida por el orquestador**: dar de baja esa línea del alcance
y del criterio de éxito correspondiente de `manual-review-camunda-form` antes de escribir su spec.
Esta propuesta no edita sus archivos, solo lo señala.

## Riesgos

| Sev. | Riesgo | Mitigación |
|---|---|---|
| Media | El rollout anunciado de Docker 29.1.x en los runners rompería CI con el mismo HTTP 400 que la máquina local | El pin de `testcontainers-bom` es la cobertura durable; queda como seguimiento inmediato, no opcional |
| Media | Los tests de proceso nunca corrieron: el primer run de CI puede revelar fallas reales, no solo de infraestructura | Es el resultado buscado; si fallan, se corrige el proceso antes de tocar el README |
| Media | Sin remoto publicado no hay Actions; la cuenta autenticada en `gh` no es la de destino | Prerrequisito explícito: el autor publica en su cuenta personal |
| Baja | Los tests de dominio quedan escritos contra invariantes leídas, no ejecutadas | Corren sin Docker: `mvn test` en CI los valida en el primer run |

## Rollback

Revertir el commit. El workflow es aditivo y se desactiva borrando `.github/workflows/ci.yml`, sin
efecto en el runtime. El único cambio de conducta es la guarda de `reject()`: si algún llamador no
previsto pasara motivo vacío, se revierte ese bloque y su test de forma aislada, sin tocar los tests
de dominio ni el workflow. No hay migraciones ni cambios de contrato externo.

## Dependencias

- Repositorio publicado en GitHub bajo la cuenta personal del autor (prerrequisito, no entregable).
- Ninguna dependencia Maven nueva: JUnit 5 ya viene por `spring-boot-starter-test`.

## Criterios de éxito

- [ ] `mvn test` en verde en CI, incluyendo los cuatro escenarios de `CreditOriginationProcessTest`.
- [ ] El log del job muestra JDK 21, no 17.
- [ ] Los tests de dominio cubren todas las operaciones de la tabla de invariantes y corren sin Docker.
- [ ] `reject()` sin motivo falla con error de dominio, fijado por un test.
- [ ] La fila `Tests de proceso ejecutados` del README refleja el estado real y el badge apunta al workflow.
- [ ] Lo verificado y lo pendiente siguen separados en el README: nada se declara probado sin un run que lo respalde.
