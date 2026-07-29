# Exploración: CI y tests de dominio

Objetivo del cambio: cerrar la fila `Tests de proceso ejecutados — ❌ no` de la tabla de verificación
del README con evidencia ejecutada, no con una promesa. Es el hueco de credibilidad más grande del
proyecto: el README afirma cuatro tests contra un Zeebe real y dos líneas después admite que nunca
corrieron.

## Estado actual (verificado leyendo código)

| Hecho | Evidencia |
|---|---|
| `pom.xml` no declara `<dependencyManagement>` ni propiedad `testcontainers.version` | Las dos dependencias Camunda resuelven por `${camunda.version}` = 8.7.6 |
| `reject(String reason)` **no valida** el contenido de `reason` | `CreditApplication.java:145-154` — asigna directo; acepta `null` y cadena vacía |
| Cero tests de dominio | Único test: `CreditOriginationProcessTest` (4 escenarios, Testcontainers) |
| Sin CI | `.github/workflows/` ausente |
| Sin remoto git | `git remote -v` vacío; el repo nunca se publicó |
| Sin Maven Wrapper | No existe `.mvn/wrapper` |

## Pregunta 1 — ¿el intento `-Dtestcontainers.version=1.21.3` fue un no-op?

**Sí, verificado.** Ese flag solo tiene efecto si el POM resuelve la versión a través de una propiedad
con ese nombre exacto. Este `pom.xml` no la define, y la propiedad interna del árbol Camunda se llama
`version.testcontainers` y vive en `zeebe-parent`, un POM que este proyecto no hereda. El flag nunca
tuvo dónde enlazar.

**Consecuencia:** el intento fallido no es evidencia de que un bump de versión no funcione. Nunca se
probó un bump de verdad.

**La palanca correcta** es un `<dependencyManagement>` explícito en el `pom.xml` propio, pinneando
`org.testcontainers:testcontainers-bom` a una versión cuyo `docker-java` negocie API ≥ 1.40. El
`dependencyManagement` del proyecto que se construye gana sobre cualquier versión heredada
transitivamente.

**Sin verificar, requiere ejecución:** compatibilidad de API entre una Testcontainers más nueva y el
harness `camunda-process-test-java:8.7.6`, y si el `docker-java` resultante negocia efectivamente
contra Docker Engine 29.3.1. No se pudo leer el POM aplanado en Maven Central (repo1.maven.org
devolvió 403).

## Pregunta 2 — ¿CI en `ubuntu-latest` correría los tests de proceso sin modificarlos?

**Sí. Corregido respecto de la primera lectura de esta exploración.**

La imagen desplegada `ubuntu-24.04` versión `20260720.247.2` (20 de julio de 2026) declara **Docker
Server 28.0.4**, no 29.x. Fuente: manifiesto `Ubuntu2404-Readme.md` del repositorio
`actions/runner-images`.

Docker 28.x es exactamente el rango que el README documenta como funcional. Testcontainers 1.20.6
negocia sin problema contra esa versión, así que **CI puede ejecutar los cuatro tests de proceso que
esta máquina no puede** — su bloqueo es local, por Docker Engine 29.3.1.

**Matiz que importa para el diseño:** GitHub anunció el 30 de enero de 2026 el upgrade de los runners
a Docker 29.1.x con despliegue a partir del 9 de febrero de 2026. La imagen del 20 de julio todavía
trae 28.0.4, de modo que ese rollout no llegó o se revirtió. Es una bomba de tiempo: cuando GitHub
efectivamente publique Docker 29, CI se rompe con el mismo HTTP 400 que la máquina local.

Por eso el pin de `testcontainers-bom` de la Pregunta 1 **no es un lujo pospuesto, es la cobertura
durable** — y de paso desbloquearía los tests localmente.

Una lectura previa de esta exploración concluyó lo contrario apoyándose en el anuncio de GitHub en
lugar del manifiesto de la imagen. El anuncio describe un plan; el manifiesto describe el estado.

## Pregunta 3 — Superficie de tests de dominio

Invariantes verificadas en `CreditApplication.java`, todas testeables sin Docker:

| Operación | Qué verificar |
|---|---|
| `submit` | Ingreso > 0, monto > 0, plazo en `[3,120]`; estado inicial `SUBMITTED` |
| `monthlyInstallment` | División con escala 2, `HALF_UP` |
| `installmentToIncomeRatio` | Escala 4, `HALF_UP` |
| `recordScreening` | Solo desde `SUBMITTED`; pasa a `SCREENED` |
| `recordRiskDecision` | Desde `SCREENED` o `PENDING_REVIEW`; **no** cambia el estado; rechaza `null` |
| `escalateReview` | Desde `SCREENED` o `PENDING_REVIEW`; deja `PENDING_REVIEW` |
| `reserveFunds` | Desde `SCREENED` o `PENDING_REVIEW`; pasa a `FUNDS_RESERVED` |
| `markDisbursed` | Solo desde `FUNDS_RESERVED`; pasa a `DISBURSED` |
| `releaseFunds` | Idempotente si ya está `REVERTED`; anula `reservationId` y `disbursementId` |
| `reject` | Idempotente si ya está `REJECTED`; solo desde `SUBMITTED`, `SCREENED` o `PENDING_REVIEW` |
| Transiciones ilegales | Lanzan `InvalidApplicationStateException`, no `IllegalStateException` |

Huecos menores detectados, no necesariamente en alcance: `attachProcessInstance` no tiene guarda de
estado, y `recordScreening` no valida `report` nulo, por lo que produciría `NullPointerException` en
lugar de un error de dominio.

**Colisión con otro cambio:** la propuesta en vuelo `manual-review-camunda-form` planea agregar a
`reject()` una guarda que exija motivo con contenido. Un test que hoy fije "acepta cualquier motivo"
quedaría obsoleto cuando esa propuesta avance. Requiere coordinación explícita entre los dos cambios.

## Pregunta 4 — JDK y toolchain en CI

El JDK por defecto de `ubuntu-latest` es **17**, no 21 — la misma trampa categórica que el `JAVA_HOME`
apuntando a JDK 8 en esta máquina. CI debe fijar JDK 21 de forma explícita con `actions/setup-java`.
Maven viene preinstalado, sin bloqueo.

## Enfoques y pronóstico contra el presupuesto de 400 líneas

| # | Enfoque | Líneas est. | Riesgo |
|---|---|---|---|
| 1 | Solo tests de dominio | 150–300 | Bajo; cero riesgo de ejecución |
| 2 | Solo CI | 80–150 | Bajo, pero sin tests de dominio que ejecutar aporta poco |
| 3 | Tests de dominio + CI que ejecuta la suite completa | 250–380 | Medio en presupuesto |

**Recomendación: enfoque 3**, y con una diferencia respecto de la primera lectura: CI ejecuta `mvn
test` **completo, incluyendo los tests de proceso**, porque el runner trae Docker 28.0.4 y sí puede
correrlos. Excluirlos desperdiciaría justamente la evidencia que motiva el cambio.

El pin de `testcontainers-bom` queda como decisión abierta para `sdd-design`: cobertura durable
contra el rollout anunciado de Docker 29, con el beneficio secundario de desbloquear `mvn test` en
esta máquina.

## Prerequisitos

- **Debe existir un remoto en GitHub.** Sin repositorio publicado no hay Actions. Decidido: cuenta
  personal del autor, no la cuenta de trabajo ya autenticada en `gh`.

## Riesgos

| Sev. | Riesgo | Mitigación |
|---|---|---|
| Media | Rollout de Docker 29 en los runners rompe CI igual que localmente | Pin de `testcontainers-bom`; es la cobertura durable |
| Media | Colisión con la guarda de `reject()` de `manual-review-camunda-form` | Coordinar; no fijar en un test el comportamiento que esa propuesta va a cambiar |
| Media | Compatibilidad de Testcontainers más nueva con el harness Camunda 8.7.6 sin verificar | Solo se resuelve ejecutando; mantener el pin fuera del camino crítico |
| Baja | CI verde sobre Docker 28 sin verificación end-to-end previa a publicar el repo | El primer run del workflow es la verificación |
