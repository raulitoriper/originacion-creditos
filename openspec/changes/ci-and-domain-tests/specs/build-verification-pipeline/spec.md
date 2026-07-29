# Build Verification Pipeline Specification

## Purpose

Ejecución automatizada de la suite de tests con toolchain fijado, publicando evidencia real en el
README en lugar de una promesa sin correr.

## Requirements

### Requirement: Workflow de CI con JDK fijado

El sistema MUST definir un workflow de GitHub Actions (`.github/workflows/ci.yml`) que se dispare en
push y pull request contra `main`, y MUST fijar JDK 21 explícitamente vía `actions/setup-java` — el
default de `ubuntu-latest` es JDK 17.

#### Scenario: Job fija JDK 21
- GIVEN un run del workflow en `ubuntu-latest`
- WHEN se inspecciona el log del paso `setup-java`
- THEN la versión activa reportada es 21, no 17

### Requirement: Ejecución completa de la suite, incluyendo tests de proceso

El sistema MUST ejecutar `mvn test` completo en CI, SIN excluir `CreditOriginationProcessTest`. El
runner `ubuntu-24.04` declara Docker Server 28.0.4, compatible con Testcontainers 1.20.6; el bloqueo
de Docker Engine 29.3.1 es local a la máquina de desarrollo, no del runner de CI.

#### Scenario: Suite completa en verde
- GIVEN un push o pull request contra `main`
- WHEN el workflow ejecuta `mvn test`
- THEN los tests de dominio de `CreditApplication` y los 4 escenarios de
  `CreditOriginationProcessTest` corren y terminan en verde

### Requirement: Evidencia de verificación publicada en README

El sistema MUST reemplazar la fila `Tests de proceso ejecutados | ❌ no` de la tabla de verificación
del README por evidencia acorde al resultado real del workflow, y MUST incluir un badge de estado de
CI que trackee la rama `main`, con fallas visibles públicamente.

#### Scenario: Fila de verificación refleja el estado real
- GIVEN un run de CI ya ejecutado sobre `main`
- WHEN se lee la tabla de verificación del README
- THEN la fila de tests de proceso muestra el resultado real del último run, no una promesa
- AND un badge de CI enlaza al workflow y refleja su estado sobre `main`
