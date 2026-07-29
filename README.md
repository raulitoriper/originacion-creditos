# Originación de Créditos — Spring Boot 3 + Camunda 8

[![CI](https://github.com/raulitoriper/originacion-creditos/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/raulitoriper/originacion-creditos/actions/workflows/ci.yml)

Proceso de originación de créditos orquestado con **Camunda 8.7 (Zeebe)** y **Spring Boot 3.4**:
scoring por DMN, revisión humana con SLA, firma digital asincrónica y **compensación de saga** cuando
el desembolso falla. Arquitectura hexagonal, con tests que asiertan sobre el proceso y no sobre
métodos.

> **La tesis:** Camunda no se justifica por poder dibujar un flujo. Se justifica cuando el proceso es
> largo, tiene estado, espera a humanos y a sistemas externos que fallan. Este modelo está construido
> para demostrar eso. Un CRUD con un diagrama pegado encima no prueba nada.

---

## Por dónde empezar

Si tenés cinco minutos, mirá estos tres archivos en este orden:

| # | Archivo | Qué vas a ver | ⏱ |
|---|---|---|---|
| 1 | [`credit-origination.bpmn`](src/main/resources/models/credit-origination.bpmn) | El proceso. Abrilo en Camunda Modeler: DMN, user task, boundary timer, event-based gateway y compensación en un solo modelo. | 2 min |
| 2 | [`CreditOriginationProcessTest.java`](src/test/java/com/rriveros/origination/CreditOriginationProcessTest.java) | Cuatro tests contra un Zeebe real. Acá se ve si entiendo Camunda o solo lo uso. | 2 min |
| 3 | [`DisbursementWorker.java`](src/main/java/com/rriveros/origination/adapter/in/process/DisbursementWorker.java) | Fallo técnico vs error de negocio. La distinción que más se equivoca. | 1 min |

**Fuera de alcance a propósito:** autenticación, formularios de Tasklist, y adaptadores HTTP reales
para bureau y core bancario. Ver [Límites conocidos](#límites-conocidos) — están listados, no
escondidos.

---

## Arrancarlo en 3 pasos

**Requisitos:** JDK 21 (Camunda 8.7 exige 17+, Java 8 no sirve), Maven 3.6.3+, Docker.

```bash
# 1. Infraestructura: Zeebe + Operate + Tasklist + Elasticsearch + Postgres
docker compose up -d

# 2. La aplicación (despliega el BPMN y el DMN al arrancar)
mvn spring-boot:run

# 3. Verificar: crear una solicitud que se aprueba sola
curl -si localhost:8080/api/credit-applications -H 'Content-Type: application/json' -d '{
  "applicantDocument":"4501234","applicantName":"Ana Lopez",
  "monthlyIncome":12000000,"requestedAmount":30000000,"termMonths":24}'
```

**Esperado:** `201 Created`. En [Operate](http://localhost:8081) la instancia queda detenida en el
event-based gateway, esperando la firma. Seguí en [Recorrer los cuatro caminos](#recorrer-los-cuatro-caminos).

| Servicio | URL | Credenciales |
|---|---|---|
| API | http://localhost:8080 | — |
| Operate — ver instancias | http://localhost:8081 | `demo` / `demo` |
| Tasklist — revisión manual | http://localhost:8082 | `demo` / `demo` |
| Zeebe REST / gRPC | `:8088` / `:26500` | — |

> ⚠ **`mvn test` no corre con Docker Engine 29.x.** Es una incompatibilidad de Testcontainers, no del
> proyecto. Diagnóstico y workaround: [Incompatibilidad con Docker 29](#incompatibilidad-con-docker-29).

---

## El proceso

Abrí [`credit-origination.bpmn`](src/main/resources/models/credit-origination.bpmn) en Camunda
Modeler para verlo renderizado. En texto:

```
Solicitud recibida
  → Consultar bureau de crédito              (service task)
  → Evaluar política de riesgo               (business rule task → DMN)
  → ¿resultado?
      ├─ APPROVED       ───────────────────────────────┐
      ├─ MANUAL_REVIEW  → Revisión del analista        │  (user task)
      │                    ⤷ SLA 4 h → escalar al supervisor   (boundary timer, no interrumpe)
      │                    → ¿aprueba? ────────────────┤
      │                       ⤷ no → Notificar rechazo
      └─ REJECTED       → Notificar rechazo
                                                       │
  ┌────────────────────────────────────────────────────┘
  → Esperar firma digital  ó  vencimiento a 7 días      (event-based gateway)
  → Reservar fondos                         ⟵ compensación: Liberar fondos
  → Desembolsar préstamo
      ⤷ DISBURSEMENT_FAILED → lanzar compensación → Originación revertida
  → Notificar aprobación
  → Crédito desembolsado
```

### Qué demuestra cada pieza

| Elemento del modelo | Capacidad | Por qué importa |
|---|---|---|
| `Activity_ScoreApplicant` → DMN `credit_scoring` | Business Rule Task | La política de riesgo es una tabla versionada, no un `if` anidado en Java. Riesgo la cambia sin recompilar. |
| `Activity_ManualReview` (`zeebe:userTask`) | User Task nativa | El proceso se detiene esperando a una persona. Días, si hace falta. |
| `Boundary_ReviewSla` (`cancelActivity="false"`) | Boundary timer no interruptivo | SLA de 4 h: escala al supervisor **sin** matar la tarea del analista. Una línea de XML contra un scheduler, una tabla de vencimientos y un bug. |
| `Gateway_AwaitSignature` + mensaje / timer | Event-based gateway | Espera un evento externo *o* vence. Correlación por `applicationId`. |
| `Boundary_ReserveCompensation` + `Activity_ReleaseFunds` | Compensación (saga) | Si el desembolso falla, la reserva se revierte. El motor sabe *qué* completó y por lo tanto *qué* deshacer. Un `@Transactional` no cruza sistemas. |
| `Boundary_DisbursementFailed` (`errorRef`) | BPMN Error | Distingue **fallo técnico** (reintentar) de **error de negocio** (compensar). |

---

## Arquitectura

```
src/main/java/com/rriveros/origination/
├── domain/
│   ├── model/          ← cero imports de framework · CreditApplication (agregado), enums
│   ├── port/in/        ← cero imports de framework · casos de uso
│   ├── port/out/       ← cero imports de framework · contratos con el exterior
│   └── service/          implementación (@Service + @Transactional — ver nota)
└── adapter/
    ├── in/rest/          controlador HTTP
    ├── in/process/       ⭐ job workers de Camunda
    └── out/
        ├── persistence/  JPA (entidad separada del agregado)
        ├── bureau/  ledger/  notification/
        └── process/      ZeebeClient — único lugar que escribe en Camunda
```

### Tres decisiones que se pueden defender en una entrevista

| Decisión | Qué se gana |
|---|---|
| **Los job workers no tienen lógica.** Traducen un job a una llamada a un caso de uso y devuelven variables. | Lógica de negocio en un worker es lógica que no podés testear sin levantar un broker. |
| **El motor no es una base de datos.** Al arrancar se mandan 3 variables: `applicationId`, `requestedAmount`, `termMonths`. | Meter el agregado entero como variables de proceso duplica el estado y lo desincroniza. |
| **La entidad JPA está separada del agregado.** El precio es un mapeo a mano. | Un dominio con invariantes reales, sin constructor vacío ni setters públicos que Hibernate exige. |

> **Nota honesta sobre `domain/service`:** esas cinco clases sí usan `@Service` y `@Transactional`.
> Es una concesión deliberada: la alternativa purista agrega diez archivos para ganar una
> independencia que este proyecto nunca va a ejercer. El aislamiento total está donde paga —
> `model` y `port` compilan sin un solo jar de framework en el classpath.

---

## Qué está verificado y qué no

| Comprobación | Estado |
|---|---|
| Compila con Java 21 | ✅ 37 fuentes de producción + 4 de test, `release 21` |
| BPMN estructuralmente válido | ✅ 25 nodos, 23 flujos, DI completo, referencias resueltas, `incoming`/`outgoing` coherentes |
| Los 6 `job type` del modelo tienen worker 1:1 | ✅ exacto, sin huérfanos de ningún lado |
| `decisionId` del DMN ↔ `calledDecision` del BPMN | ✅ `credit_scoring` |
| `errorCode` del BPMN ↔ constante del worker | ✅ `DISBURSEMENT_FAILED` |
| `domain/model` y `domain/port` sin frameworks | ✅ cero imports |
| **Tests de proceso ejecutados** | ✅ **58/58 en CI** — [run](https://github.com/raulitoriper/originacion-creditos/actions/runs/30472808007) · localmente sigue bloqueado, ver [Docker 29](#incompatibilidad-con-docker-29) |

Los cinco checks estáticos son copy-paste. Ninguno debe imprimir un error:

```bash
# Compila
mvn test-compile

# Los job types del BPMN y los @JobWorker coinciden 1:1 (el diff debe salir vacío)
diff <(rg -oNI 'taskDefinition type="([^"]+)"' -r '$1' src/main/resources/models/credit-origination.bpmn | sort -u) \
     <(rg -oNI '@JobWorker\(type = "([^"]+)"'  -r '$1' src/main/java | sort -u) && echo "OK 1:1"

# El DMN que invoca el BPMN existe con ese id  → credit_scoring (una sola línea)
rg -oNI 'decisionId="([^"]+)"|<decision id="([^"]+)"' -r '$1$2' src/main/resources/models/ | sort -u

# El errorCode del modelo y el del worker son el mismo → DISBURSEMENT_FAILED (una sola línea)
rg -oNI 'errorCode="([^"]+)"|DISBURSEMENT_FAILED = "([^"]+)"' -r '$1$2' \
   src/main/resources/models/ src/main/java | sort -u

# El núcleo no conoce ningún framework (no imprime nada)
rg -c "^import (io\.camunda|org\.springframework|jakarta)" \
   src/main/java/com/rriveros/origination/domain/model/ \
   src/main/java/com/rriveros/origination/domain/port/
```

---

## Detalles

### Recorrer los cuatro caminos

El bureau simulado deriva el score de **los últimos tres dígitos del documento**, así que la demo es
reproducible:

| Documento termina en | Score | Camino |
|---|---|---|
| `000`–`199` | bajo + mora vigente | rechazo automático por DMN |
| `200`–`599` | zona gris | revisión manual + SLA |
| `600`–`999` | alto | aprobación automática |

```bash
# ── 1. Camino feliz: aprueba, espera la firma, desembolsa
#    (la solicitud de "Arrancarlo en 3 pasos")
curl -X POST localhost:8080/api/credit-applications/<id>/signature   # firma digital
curl -s      localhost:8080/api/credit-applications/<id>             # → status DISBURSED

# ── 2. Rechazo automático por el DMN — nunca pasa por un humano
curl -s localhost:8080/api/credit-applications -H 'Content-Type: application/json' -d '{
  "applicantDocument":"4500055","applicantName":"Bruno Diaz",
  "monthlyIncome":8000000,"requestedAmount":20000000,"termMonths":24}'
# → status REJECTED

# ── 3. Revisión manual + SLA  (documento terminado en 200-599)
#    La tarea aparece en Tasklist. Completala con la variable  reviewApproved = true|false
#    Si la dejás 4 h sin tocar, el boundary timer escala al supervisor y la tarea SIGUE VIVA.

# ── 4. Compensación: monto sobre el límite del ledger (150.000.000)
curl -s localhost:8080/api/credit-applications -H 'Content-Type: application/json' -d '{
  "applicantDocument":"4509991","applicantName":"Carla Ruiz",
  "monthlyIncome":30000000,"requestedAmount":160000000,"termMonths":24}'
curl -X POST localhost:8080/api/credit-applications/<id>/signature
# → reserva OK, desembolso rechazado, compensación libera la reserva
# → status REVERTED, reservationId = null   (en Operate se ve el camino de compensación)
```

### Tests

```bash
mvn test    # 58/58 en CI. Localmente requiere Docker ≤ 28.x
```

`CreditApplicationTest` cubre las invariantes del agregado con **54 tests unitarios sin Docker** —
`domain/model` sin un solo import de framework, así que corren en cualquier máquina.

`CreditOriginationProcessTest` levanta **un Zeebe real en Testcontainers** con
`camunda-process-test-spring`:

| Test | Qué prueba |
|---|---|
| `aprueba_y_desembolsa_cuando_el_score_es_alto` | Ruta feliz completa + correlación del mensaje de firma |
| `rechaza_cuando_hay_mora_vigente` | La regla DMN de mora corta el flujo sin humano |
| `compensa_la_reserva_cuando_el_desembolso_es_rechazado` | BPMN error → compensación → `Activity_ReleaseFunds` ejecutada |
| `escala_al_supervisor_cuando_vence_el_sla_de_revision` | `increaseTime(5h)` dispara el boundary timer; la user task **sigue activa** |

El BPMN es código ejecutable: gateways, timers, correlación y compensación son lógica de negocio y se
testean como tal. Testear solo los workers con Mockito no prueba el proceso — prueba métodos.

### Incompatibilidad con Docker 29

Con **Docker Engine 29.x** los tests fallan antes de crear el primer contenedor:

```
DockerClientProviderStrategy: Could not find a valid Docker environment
  EnvironmentAndSystemPropertyClientProviderStrategy: BadRequestException (Status 400 ...)
  NpipeSocketClientProviderStrategy:                  BadRequestException (Status 400 ...)
```

**Causa:** el daemon de Docker 29 declara `MinAPIVersion = 1.40` y rechaza con HTTP 400 las
versiones anteriores. `camunda-process-test-spring:8.7.6` fija **Testcontainers 1.20.6**, cuyo
docker-java negocia por debajo de ese mínimo.

Comprobable sin Java:

```bash
docker version --format '{{.Server.APIVersion}} / min {{.Server.MinAPIVersion}}'   # 1.54 / min 1.40
DOCKER_API_VERSION=1.32 docker info    # → 400 Bad Request
DOCKER_API_VERSION=1.40 docker info    # → OK
```

**Descartado sin éxito:** `DOCKER_HOST` a los tres pipes (`docker_engine`,
`dockerDesktopLinuxEngine`, `docker_cli`), `DOCKER_API_VERSION=1.44`, y
`-Dtestcontainers.version=1.21.3`. Todos siguen dando 400.

**Workaround:** Docker Engine 28.x o anterior, o un daemon remoto vía `DOCKER_HOST=tcp://...`.

### Versiones

| | |
|---|---|
| Java | 21 |
| Spring Boot | 3.4.6 |
| Camunda | 8.7.6 — última **estable** (8.8 sigue en alpha) |
| SDK | `io.camunda:spring-boot-starter-camunda-sdk` |
| Tests de proceso | `io.camunda:camunda-process-test-spring` |

> **Nota de migración:** en 8.7 el cliente es `io.camunda.zeebe.client.ZeebeClient` y las properties
> viven bajo `camunda.client.*`. En 8.8 pasa a `io.camunda.client.CamundaClient`. Si migrás, empezá
> por ahí.

---

## Límites conocidos

Están acá a propósito. Un README que dice "todo listo" es menos creíble que uno que sabe dónde están
las costuras.

| Área | Límite | Respuesta correcta |
|---|---|---|
| Alta de solicitud | Doble escritura: `SubmitCreditApplicationService` guarda en Postgres y arranca la instancia en la misma transacción. Si el commit falla después, queda un proceso huérfano. | **Outbox transaccional.** Documentado en el código, no escondido detrás de algo que parece atómico y no lo es. |
| Vencimiento de firma | `EndEvent_SignatureExpired` cierra la instancia pero no actualiza el estado del agregado. | Job de conciliación, o un worker antes del end event. |
| Revisión manual | Sin formulario: se completa seteando `reviewApproved` a mano en Tasklist. | Un Camunda Form. |
| Integraciones | Bureau y ledger simulados (`SimulatedCreditBureauAdapter`, `InMemoryLedgerAdapter`). | Adaptadores HTTP reales — no toca una línea de dominio: para eso están los puertos. |
| Seguridad | Sin autenticación. Camunda corre sin Identity/Keycloak y la API está abierta. | Es una demo local, no un despliegue. |
| Infraestructura | `docker-compose.yml` no fue arrancado de punta a punta; sigue la configuración estándar de Camunda 8.7 Self-Managed. | Verificarlo en la máquina destino. |
| Tests | Corren en CI (58/58), pero **no localmente**: Docker Engine 29.x rompe Testcontainers 1.20.6. | Pinnear `testcontainers-bom` en un `dependencyManagement` propio, o Docker ≤ 28.x. |
| Aserciones de proceso | **`camunda-process-test-java` 8.7.6 tiene un bug de paginación**: `CamundaApiClient` consulta `/v1/flownode-instances/search` con un cuerpo constante, sin `size` ni `page`, así que recibe el default del servidor de **10 elementos** — y `CamundaDataSource` descarta el campo `total` que delataría el truncamiento. Cualquier proceso de más de 10 elementos recibe aserciones de elementos **falsamente negativas y silenciosas**. Probado con `javap` contra el jar. | Por eso existe [`FlowNodeElementProbe`](src/test/java/com/rriveros/origination/support/FlowNodeElementProbe.java): una búsqueda acotada por `flowNodeId` devuelve una sola fila y es inmune al límite. **No lo borres pensando que es sobreingeniería** — sin él, `EndEvent_Disbursed` y toda la cadena de compensación quedan sin asertar. Reportar el bug aguas arriba y quitar la sonda cuando CPT pagine bien. |

---

## Próximo paso

En orden de valor:

1. **Reportar aguas arriba el bug de paginación** de `camunda-process-test-java` 8.7.6 y quitar
   `FlowNodeElementProbe` cuando esté arreglado.
2. **Pinnear `testcontainers-bom`** en un `dependencyManagement` propio, para poder correr `mvn test`
   completo sin depender de la versión de Docker de la máquina.
3. El **Camunda Form** de la revisión manual (propuesta en `openspec/changes/manual-review-camunda-form/`).
4. Ampliar el `catch` de `DisbursementWorker`: hoy solo atrapa `DisbursementRejectedException`, así que
   cualquier otra excepción se escapa como fallo técnico en lugar de error de negocio.
5. El worker que reconcilia el estado del agregado en la rama de vencimiento de firma.
