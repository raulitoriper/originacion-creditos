# Diseño: CI y tests de dominio

## Enfoque técnico

Tres piezas independientes que se sostienen solas y se revisan en un PR: una clase de test JUnit 5
sin framework para `CreditApplication`, una guarda de argumento en `reject(String reason)`, y un
workflow de GitHub Actions de un solo job que ejecuta `mvn test` completo con JDK 21 fijado. El
README se actualiza al final, y **solo** con el resultado de un run real.

Nada toca `pom.xml`, el BPMN, el DMN ni los adaptadores. La única modificación de conducta productiva
es la guarda de `reject()`.

## Decisiones de arquitectura

| # | Decisión | Elección | Alternativa rechazada | Justificación |
|---|---|---|---|---|
| 1 | Excepción de la guarda de `reject()` | `IllegalArgumentException` | `InvalidApplicationStateException` | El agregado ya separa las dos familias: `submit()` valida sus argumentos con `IllegalArgumentException` (líneas 64-72) y `recordRiskDecision`/`reserveFunds`/`markDisbursed` usan `Objects.requireNonNull`. `InvalidApplicationStateException` se construye únicamente en `requireStatus` y en el chequeo de estado de `reject`, y su constructor exige `(id, action, current)` para armar el mensaje «porque esta en estado X». Un motivo vacío no tiene un estado que culpar: forzarlo a ese constructor produciría un mensaje que atribuye al estado un defecto de argumento. |
| 2 | Organización de los tests | Una clase por agregado, `CreditApplicationTest`, con `@Nested` por operación | Una clase por operación (`SubmitTest`, `RejectTest`, …) | Surefire encabeza la falla con la clase; JUnit 5 imprime los contenedores anidados como ruta (`CreditApplicationTest > reject() > rechaza un motivo en blanco`). Con nesting el nombre de la hoja carga solo el caso, no la operación. Diez archivos para un agregado fragmentan la lectura de las invariantes y obligan a una clase base compartida para los fixtures de estado, reintroduciendo el patrón `Abstract*`. |
| 3 | Dependencias nuevas | Ninguna | Declarar `junit-jupiter` o `assertj-core` explícitos | `spring-boot-starter-test` ya está en `pom.xml:64-68` y es la única fuente de JUnit Jupiter y AssertJ en este classpath; `CreditOriginationProcessTest` importa `org.junit.jupiter.api.Test`, `@DisplayName`, `@BeforeEach` y `org.assertj.core.api.Assertions.assertThat`, y compila (`mvn test-compile` en verde). `junit-jupiter-params` viene en el mismo agregado, así que `@ParameterizedTest` está disponible. Los tests de dominio no necesitan Mockito: el agregado no tiene colaboradores. |
| 4 | Nombre e inclusión en Surefire | Sufijo `Test` en `src/test/java/com/rriveros/origination/domain/model/CreditApplicationTest.java` | `TestCreditApplication` o `CreditApplicationTests` | El sufijo `Test` es aceptado tanto por los includes por defecto de Surefire como por la lista más angosta que aplica `spring-boot-starter-parent`. Sin colisión: FQN, paquete y nombre simple distintos de `CreditOriginationProcessTest`, sin recursos ni estado estático compartido. |
| 5 | Topología del workflow | Un job `build` con un solo `mvn -B test` | Dos jobs: uno rápido sin Docker + uno de proceso | La atribución ya la da Surefire, que reporta fallas por clase: el log distingue las dos suites sin una línea de YAML. Dos jobs duplican `checkout` + `setup-java` y exigen filtros `-Dtest=` en cada uno, un mecanismo que descarta en silencio cualquier clase que no matchee: exactamente el modo de falla que la decisión 4 evita. La señal rápida se obtiene mejor en local, donde `mvn test -Dtest=CreditApplicationTest` corre sin Docker y por lo tanto sin el bloqueo de esta máquina. |
| 6 | Maven Wrapper | No agregarlo | Agregar `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` | El runner trae Maven preinstalado (verificado en la exploración). El wrapper suma ~30 líneas de scripts y, según la variante, un `.jar` binario dentro del PR cuyo propósito es producir evidencia auditable. No aporta nada al resultado de este CI: no habría arreglado el bloqueo local (es Docker, no Maven) ni el `JAVA_HOME` en JDK 8. Es cobertura durable contra el drift de la versión de Maven, del mismo tipo que el pin de `testcontainers-bom`: pertenece al mismo cambio de endurecimiento de seguimiento. |
| 7 | Triggers y caché | `push` en `main`, `pull_request` hacia `main`, `concurrency` con `cancel-in-progress`, `permissions: contents: read`, `cache: maven` en `setup-java` | `push` en todas las ramas, `workflow_dispatch`, un step `actions/cache` separado | El badge sigue `main`, así que `main` debe ser trigger de `push` para que el badge tenga runs que reportar; `pull_request` da señal antes del merge. `push` en todas las ramas duplica runs con contenedores en cada rama de PR sin información extra. `cancel-in-progress` evita runs apilados en un PR que se actualiza rápido. La caché es un input de una línea en un step que ya es obligatorio por el JDK 21; un `actions/cache` propio gasta cinco líneas para lo mismo. |

### Notas que la tabla no alcanza a expresar

**Ubicación de la guarda dentro de `reject()`.** Va como primera sentencia, antes del corto-circuito de
idempotencia y antes del chequeo de `REJECTABLE`, siguiendo el orden que ya fija `submit()`: primero
el argumento, después el estado. Consecuencia deliberada y testeable: `reject("")` sobre una solicitud
ya `REJECTED` lanza en lugar de retornar. La idempotencia queda condicionada a un argumento válido, lo
que hace la guarda total: ningún estado puede colar un motivo vacío. Los reintentos de Zeebe no se ven
afectados porque un reintento reenvía el mismo motivo no vacío.

**Semántica de «vacío» y normalización.** La regla es la decidida: `reason == null ||
reason.trim().isEmpty()`. Se descarta `isBlank()` aunque sea el idiom de Java 11+, por fidelidad
literal a la regla acordada (difieren en algunos espacios Unicode exóticos). El motivo se almacena
**tal como llega**: la guarda valida, no normaliza. Trimear al guardar cambiaría datos persistidos y
no lo pide ninguna regla.

**El límite «sin frameworks» es convención, no control automático.** Los tests importan solo
`java.math.*`, `org.junit.jupiter.api.*`, `org.assertj.core.api.*` y
`com.rriveros.origination.domain.model.*`. Sin `@SpringBootTest`, sin `@ExtendWith(SpringExtension.class)`,
sin `@ActiveProfiles`, sin `@MockitoBean`; por lo tanto sin contexto Spring, sin H2 y sin Docker.
Nada en el build lo impide: un ArchUnit o un `maven-enforcer` con dependencias prohibidas requeriría
tocar `pom.xml`, que está fuera de alcance. Queda como candidato del cambio de seguimiento, y hasta
entonces lo sostiene la revisión.

**Incertidumbre reconocida en la decisión 4.** No se pudo leer el POM de
`spring-boot-starter-parent:3.4.6` (no está en el repositorio local alcanzable), así que no está
verificado cuál de los dos conjuntos de includes rige. Por eso se elige un sufijo válido bajo ambos:
`TestCreditApplication` matchearía solo el set por defecto y se saltaría en silencio si rige el de
Spring Boot.

## Límite hexagonal

Los tests viven en `src/test/java/com/rriveros/origination/domain/model/`, espejando el paquete de
producción. Verifican el agregado a través de sus operaciones públicas, no del constructor de
rehidratación: los fixtures de estado se construyen manejando transiciones reales
(`submit` → `recordScreening` → `reserveFunds`). Usar el constructor de 14 argumentos permitiría
fabricar estados que las transiciones no alcanzan y congelaría su orden de parámetros en diez
llamadas. `domain/model` sigue con cero imports de framework en producción y ahora también en test.

## Contrato de `reject(String reason)`

```java
// motivo con contenido: precede a idempotencia y a la validación de estado
if (reason == null || reason.trim().isEmpty()) {
    throw new IllegalArgumentException("El motivo de rechazo es obligatorio");
}
```

## Flujo

    push/PR ──→ actions/checkout ──→ setup-java (JDK 21, cache: maven) ──→ mvn -B test
                                                                              │
                        ┌─────────────────────────────────────────────────────┴────┐
                CreditApplicationTest                              CreditOriginationProcessTest
                (JUnit puro, sin Docker)                           (Spring + Zeebe en Testcontainers)
                        └──────────────────── Surefire report ─────────────────────┘
                                                    │
                                            badge en README (rama main)

## Cambios de archivos

| Archivo | Acción | Descripción |
|---|---|---|
| `src/test/java/com/rriveros/origination/domain/model/CreditApplicationTest.java` | Crear | Clase única con `@Nested` por operación; `@ParameterizedTest` sobre pares (estado, operación) para las transiciones ilegales, de modo que agregar un estado no pueda saltear cobertura en silencio |
| `src/main/java/com/rriveros/origination/domain/model/CreditApplication.java` | Modificar | Guarda de motivo como primera sentencia de `reject()` |
| `.github/workflows/ci.yml` | Crear | Un job `build`, JDK 21 explícito, `mvn -B test` completo |
| `README.md` | Modificar | Badge apuntando al workflow en `main` y fila `Tests de proceso ejecutados` (línea 144) con el estado real |

Los artefactos bajo `openspec/changes/` no cuentan en el presupuesto de 400 líneas de implementación,
con el mismo criterio de conteo que usó la propuesta.

## Estrategia de tests

| Capa | Qué se testea | Cómo |
|---|---|---|
| Unit | Las once filas de la tabla de invariantes: guardas de `submit`, escala 2 / `HALF_UP` de `monthlyInstallment`, escala 4 de `installmentToIncomeRatio`, máquina de estados completa, idempotencia de `releaseFunds` y `reject`, `recordRiskDecision` que no cambia el estado, y la nueva guarda de motivo | JUnit 5 + AssertJ, sin contexto ni Docker |
| Unit (negativo) | Toda transición ilegal lanza `InvalidApplicationStateException`, no `IllegalStateException` | `@ParameterizedTest` sobre pares (estado, operación); el display name identifica el par que falla |
| Integration | Los cuatro escenarios end-to-end existentes, sin modificarlos | `CreditOriginationProcessTest` en el mismo `mvn test` |
| E2E | Fuera de alcance | — |

## Threat Matrix

N/A. El workflow es YAML declarativo: no compone comandos de git ni de PR, no selecciona repositorio
ni `cwd` dinámicamente, no clasifica archivos ejecutables y no toma entrada no confiable. Ninguna
fila de la matriz aplica; no se generan tareas ni tests por ella.

## Evidencia: verificado contra no verificado

**Verificado leyendo archivos en esta sesión:**

- `reject()` no valida el contenido del motivo (`CreditApplication.java:145-154`).
- `InvalidApplicationStateException` se construye solo en `requireStatus` y en el chequeo de estado de
  `reject`, y su mensaje atribuye la falla al estado.
- Cadena completa del único llamador productivo: `NotificationWorker:37` →
  `ResolveApplicationService:42` → `reject(...)`, con `reasonFor(RiskDecision)` devolviendo un literal
  no vacío en las dos ramas (`NotificationWorker:43-47`). La guarda es aditiva y no puede romper los
  cuatro tests de proceso.
- `spring-boot-starter-test` presente; JUnit 5 y AssertJ ya en uso y compilando.
- `.mvn/wrapper` y `.github/workflows/` ausentes; `java.version` = 21.

**No verificado, y solo lo resuelve la ejecución en CI:**

- Que `mvn test` termine en verde. **Los cuatro tests de proceso nunca corrieron.**
- Que el runner siga trayendo Docker 28.0.4 al momento del run: eso viene de un manifiesto del
  2026-07-20, no de una ejecución.
- Que los tests de dominio pasen: están escritos contra invariantes leídas, no ejecutadas.
- Cuál de los dos conjuntos de includes de Surefire rige (mitigado por la decisión 4).

## Qué pasa si el primer run de CI falla

El cambio no está terminado, y el README no se toca. Procedimiento:

1. Clasificar la falla: infraestructura (negociación Docker/Testcontainers, pull de imagen, timeout)
   contra defecto real de proceso o de dominio.
2. Infraestructura → el arreglo pertenece al cambio de seguimiento del pin de `testcontainers-bom`;
   este cambio no puede reclamar la fila del README.
3. Defecto de proceso (ids de elementos del BPMN, límites de reglas del DMN, correlación de mensajes,
   semántica de `increaseTime`, H2 contra Postgres en el perfil `test`) → corregirlo acá si es chico y
   cae en la superficie ya tocada; si no, abrir un cambio dedicado y dejar la fila del README honesta,
   con el motivo y el enlace al run que falla.
4. Si falla un test de dominio porque un literal esperado está mal calculado, el defecto es del test,
   no del agregado.
5. Prohibido en todos los casos: `continue-on-error`, exclusiones `-Dtest=` o cualquier maniobra que
   ponga el badge en verde sin un run que lo respalde. Eso reproduciría, con más maquinaria, el
   mismo hueco de credibilidad que este cambio existe para cerrar.

## Migración / rollout

Sin migración. El workflow es aditivo y se desactiva borrando el archivo. La guarda de `reject()` se
revierte de forma aislada, con su test, sin tocar el resto.

## Preguntas abiertas

- [ ] Agrupación del cambio de seguimiento: pin de `testcontainers-bom`, Maven Wrapper y control
      automático de dependencias prohibidas en `domain/*` comparten motivación (endurecimiento
      reproducible). Que vayan juntos o separados es decisión del orquestador; no bloquea este diseño.
