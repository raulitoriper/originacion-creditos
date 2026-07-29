# Apply Progress: fix-process-test-failures

## Batch history

- **Batch 1 (Phase A)**: `Phase 1: Fase A — Instrumentación de diagnóstico` plus the mandatory
  local verification gate (`Phase 2`, task 2.1). Phase B (Phase 4) and CI verification (Phase 3)
  were explicitly out of scope and untouched.
- **Batch 2 (Phase B, this batch)**: implements the fix identified by reading the real PR1 CI run
  (commit `0de59c1`), guards `findApplication.findById` in `@AfterEach`, removes the diagnostic
  sections that always return HTTP 401, and reconciles `proposal.md` with the shipped logging
  decision. See "Batch 2" sections below for full detail. This is the section-by-section merge:
  Batch 1's record is preserved verbatim below, Batch 2's additions follow.

## Scope of Batch 1 (Phase A)

Phase A only (`Phase 1: Fase A — Instrumentación de diagnóstico`) plus the mandatory local
verification gate (`Phase 2`, task 2.1). Phase B (Phase 4) and CI verification (Phase 3) are
explicitly out of scope for this batch and untouched.

## Strict TDD applicability — explicitly not applicable to this phase

`strict_tdd: true` is declared for this project, but Phase A adds **zero production behavior and
zero new assertions**. It is read-only diagnostic instrumentation in test scope. There is no RED
state to write: a test asserting "the dump exists" would assert on log output, which is weaker
evidence than the CI run itself. design.md states this explicitly under "Estrategia de testing".
No TDD Cycle Evidence table is produced for this reason — this is a declared exception, not a
silent skip.

## Completed Tasks

- [x] 1.1 Created `ProcessDiagnostics.java` — static helper, no Spring, `ZeebeClient` passed by
      the caller. Two shots (T1 immediate, T2 after `Thread.sleep(Duration.ofSeconds(3))`), 5
      sections (`INSTANCE`, `ELEMENTS`, `INCIDENTS`, `EXPECTED`, `AGGREGATE`) between `DIAG
      BEGIN/END` markers, `DIAG |` prefixed lines. Every section wrapped independently in
      try/catch — never throws, prints `DIAG-ERROR` on failure and continues with the remaining
      sections (partial-failure invariant from the Threat Matrix).
- [x] 1.2 Modified `CreditOriginationProcessTest.java` — `processInstanceKeyOf()` now records a
      `ObservedInstance(processInstanceKey, applicationId, expectedElementIds)` per call; added
      `@AfterEach void dumpDiagnostics(TestInfo)` that loops over all observed instances for the
      current test, reloads the aggregate via `findApplication.findById(applicationId)` (already
      autowired), and calls `ProcessDiagnostics.dump(...)`. Zero existing assertions or ids
      changed.
- [x] 1.3 Modified `application-test.yaml` — added `io.camunda.process.test: DEBUG` and
      `io.camunda.zeebe.spring.client.jobhandling: DEBUG`; `io.camunda: WARN` untouched.
- [x] 2.1 Ran the mandatory compile gate. **PASSED on the first attempt** (see Work Unit Evidence
      below) — task 2.2 (correcting unverified method names) did not apply because nothing failed
      to compile.

## Files Changed

| File | Action | What Was Done | Lines (add+del) |
|------|--------|---------------|---|
| `src/test/java/com/rriveros/origination/support/ProcessDiagnostics.java` | Created | Static diagnostic dump helper, 5 sections, 2 timed shots, never throws | 199 |
| `src/test/java/com/rriveros/origination/CreditOriginationProcessTest.java` | Modified | `ObservedInstance` record, `@AfterEach dumpDiagnostics`, `processInstanceKeyOf` now takes expected ids, 4 call sites updated, imports | 59 ins / 7 del |
| `src/test/resources/application-test.yaml` | Modified | 2 scoped log-level overrides added, `io.camunda: WARN` preserved | 3 ins / 1 del |
| **Total Phase A** | | | **269** |

`git diff --stat` (excluding this planning artifact) confirms only `src/test/**` is touched — no
`src/main`, BPMN, DMN, `pom.xml`, `README.md`, or workflow files.

## Deviations from Design

1. **`ProcessInstanceFilter` (8.7.6) has no `processInstanceKey` filter method — verified via
   `javap` against the real jar, not assumed.** `javap` on
   `io.camunda.zeebe.client.api.search.filter.ProcessInstanceFilter` shows exactly: `running`,
   `active`, `incidents`, `finished`, `completed`, `canceled`, `retriesLeft`, `errorMessage`,
   `activityId`, `startDate`, `endDate`, `bpmnProcessId`, `processDefinitionVersion`, `variable`,
   `batchOperationId`, `parentProcessInstanceKey`, `tenantId` — no `key` and no
   `processInstanceKey`. This is a real gap the design/tasks flagged as an "unverified method name"
   risk but did not anticipate this specific shape. `FlownodeInstanceFilter` and `IncidentFilter`
   **do** expose `processInstanceKey`, so `ELEMENTS` and `INCIDENTS` are filtered server-side as
   designed. For the `INSTANCE` section only, `ProcessDiagnostics.printInstance` fetches the full
   `newProcessInstanceQuery().send().join().items()` (unfiltered) and reduces client-side by
   `pi.getKey().equals(processInstanceKey)`. This is consistent with Decision 7's existing
   client-side reduction pattern ("orden calculado en el cliente ... las colecciones son
   diminutas") and does not introduce any new API surface, retries, or waiting.
2. **`ProcessInstance.getProcessVersion()`, not `getProcessDefinitionVersion()`.** Verified via
   `javap`; the design's data table used the informal name `processDefinitionVersion` for what
   the volcado prints, the real accessor is `getProcessVersion()`. Used the real accessor; the
   printed label in the dump still reads `processDefinitionVersion=` to match the design's output
   format exactly.
3. **`newFlownodeInstanceQuery()`, `filter(Consumer<F>)`, `send().join().items()`, and all
   response accessors (`getState()`, `getFlowNodeId()`, `getStartDate()`, `getEndDate()`,
   `getFlowNodeInstanceKey()`, `getErrorType()`, `getErrorMessage()`, `getCreationTime()`) were
   confirmed present and correctly named via `javap` against
   `zeebe-client-java-8.7.6.jar` before writing `ProcessDiagnostics.java`, not written from memory.
   No name had to be corrected after the fact — the compile gate passed on the first attempt.
4. **Line count came in higher than forecast (~269 vs ~153 estimated)**, still well under the
   400-line budget (`400-line budget risk: Low` holds). The delta is mostly
   `ProcessDiagnostics.java` (199 vs ~120 estimated) — the per-section try/catch wrapping
   (Threat Matrix "falla parcial" invariant) and the Javadoc contract block account for most of
   the difference — and the `ObservedInstance` record plus per-call-site expected-id lists in
   `CreditOriginationProcessTest.java` (62 vs ~30 estimated).

No other deviations. Design Decisions 2, 3, 4, 5, 6, 7 followed exactly as written (static
helper with no Spring annotations, `@AfterEach` placement, T1/T2 timing, log-level scoping,
decision table left untouched for Phase B, output format with fixed section order and
`String.valueOf(...)` printing).

## Issues Found

- **spec.md vs design.md conflict on the log-level requirement (pre-existing, not introduced by
  this batch)**: `specs/credit-origination-saga/spec.md` — Requirement "Log del motor en DEBUG
  durante tests" — literally requires `logging.level.io.camunda: DEBUG`. `design.md` Decision 5
  explicitly rejects this (verified: `io.camunda.zeebe.client.impl.worker.JobPoller` logs at
  DEBUG on every poll of every `@JobWorker`, which would bury the dump in noise) and instead
  specifies two scoped overrides while keeping `io.camunda: WARN`. Per this batch's instructions
  ("Design decisions to implement exactly — do NOT re-decide"), I followed `design.md` and
  `tasks.md` (task 1.3) literally, which is the two-override approach, **not** the spec's literal
  `DEBUG` text. I did not touch `state.yaml` or `spec.md` per the out-of-scope list. Flagging this
  so `sdd-verify` does not fail against the literal (stale) spec wording.

## Work Unit Evidence

| Evidence | Value |
|---|---|
| Focused test command and exact result | `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && cd C:/Repo/originacion-creditos && "C:/Users/rriveros/scoop/apps/maven/current/bin/mvn.cmd" -B test-compile` → `BUILD SUCCESS`, "Compiling 3 source files with javac [debug parameters release 21] to target\test-classes", no errors, no warnings surfaced in output. |
| Runtime harness command/scenario and exact result | N/A for this batch — `CreditOriginationProcessTest` cannot run locally (Docker Engine 29.3.1 vs Testcontainers 1.20.6 returns HTTP 400, per orchestrator instructions); the only real runtime verification is the CI run, which is Phase 3 (task 3.2/3.3), explicitly out of scope for this apply batch. |
| Rollback boundary | Single `git revert` reverts exactly 3 files: `src/test/java/com/rriveros/origination/CreditOriginationProcessTest.java`, `src/test/java/com/rriveros/origination/support/ProcessDiagnostics.java` (deletion), `src/test/resources/application-test.yaml`. No `src/main` touched, no migration, no engine state to unwind. |

## Remaining Tasks

- [ ] 2.2 — contingent, did not apply (see above)
- [ ] 3.1–3.5 — Phase A CI verification (CI-only, next batch/orchestrator action)
- [ ] 4.1–4.7 — Phase B (blocked until 3.5 is read; explicitly out of scope for this batch)
- [ ] 5.1 — Closure (blocked until 4.6 is green)

## Workload / PR Boundary

- Mode: stacked-to-main, PR1 of 2 (Phase A only)
- Current work unit: Unit 1 — "Fase A: instrumentación diagnóstica de solo lectura"
- Boundary: starts from the reference commit `75e3776` (2 failures, BUILD FAILURE) and ends with
  Phase A instrumentation compiled and ready to push as PR1 → main. Phase B cannot start until
  PR1's CI run is read (task 3.5).
- Estimated review budget impact: 269 authored changed lines, under the 400-line budget. Low risk
  confirmed empirically, not just forecast.

## Status (superseded by Batch 2 — see below for the current cumulative status)

---

# Batch 2 (Phase B)

## Scope of this batch

Phase 4 (Fase B) tasks 4.1, 4.2, 4.4, 4.7 — the locally verifiable ones — plus three items given
directly by the orchestrator's Phase B instructions that are not separate `tasks.md` checkboxes:
guarding `findApplication.findById` inside `@AfterEach`, removing the diagnostic sections that
always return HTTP 401, and reconciling `proposal.md`'s stale logging claim. Tasks 4.3 (deferred,
does not apply), 4.5/4.6 (CI-only), and 5.1 (blocked on 4.6) are explicitly out of scope for local
apply and remain unchecked.

## What the PR1 CI run (commit `0de59c1`) proved, and how this batch used it

Per the orchestrator's brief, the real CI run (`conclusion=failure`, 58 tests, 2 failures, same
missing elements as `75e3776`) is valid evidence. Its `AGGREGATE` section — read from JPA, unaffected
by the client-side 401s — showed:

- "Desembolso rechazado" (compensación): `status=REVERTED`, `reservationId=null`,
  `disbursementId=null` — producible only by `CreditApplication.releaseFunds()`, reachable only via
  `FundsReleaseWorker` on `Activity_ReleaseFunds`, reachable only if `Event_CompensateOrigination`
  fired. **The compensation path executed end to end.**
- "Score alto" (desembolso): `status=DISBURSED`, real `disbursementId` — producible only by
  `markDisbursed()` after the ledger accepted.

Both scenarios completed correctly at the domain level while `CamundaAssert.hasCompletedElements`
reported the same late elements as not activated in both. This maps to the decision table's last
row (`spec.md`, "Corrección de Fase B guiada por la tabla de decisión": `COMPLETED con timestamp
anterior a la falla` → `estrategia de aserción del test, no el modelo`), and to `design.md`
Decisión 6 row 1 (`ELEMENTS` absent at T1, present at T2 → "la vista exportada va detrás del
motor"). It also affirmatively rules out `design.md` row 3 (technical incident in
`disburse-loan`): that would have prevented the compensation from completing, and the aggregate
would not read `REVERTED`.

All three client-based dump queries (`INSTANCE`, `ELEMENTS`, `INCIDENTS`) failed with HTTP 401 in
that same run. Design's Decisión 1 already noted the search API in Camunda 8.7.6 is served over
REST (auth-required), unlike the gRPC command/job API the workers use; the `ZeebeClient` autowired
into the test carries no REST credentials. Every `DIAG | ... | MISSING` line the dump produced was
therefore an artifact of the 401, not evidence — including for elements a passing test's assertion
had already confirmed via `CamundaAssert`'s own internal `CamundaDataSource` path (which does not
go through that search API and is why the passing tests' element assertions succeed at all).

## Completed Tasks (Batch 2)

- [x] 4.1 Classified the two failures against the decision table using the PR1 `AGGREGATE`
      evidence above (fix area: test observation strategy, not the model) and implemented the fix
      only in that area.
- [x] 4.2 Added `@BeforeAll static void configureAssertionTimeout()` to
      `CreditOriginationProcessTest` calling `CamundaAssert.setAssertionTimeout(Duration.ofSeconds(20))`.
      Verified via `javap` against `camunda-process-test-java-8.7.6.jar` **before** writing the
      code (not guessed):
      - `CamundaAssert.setAssertionTimeout(Duration)` exists and its bytecode calls
        `org.awaitility.Awaitility.setDefaultTimeout(Duration)` directly — confirmed this is a
        real, public, static entry point, not an assumption from the design doc's "p. ej." wording.
      - The class's static initializer sets `DEFAULT_ASSERTION_TIMEOUT = Duration.ofSeconds(10)`
        and `DEFAULT_ASSERTION_INTERVAL = Duration.ofMillis(100)` — confirming the default window
        CPT polls against before throwing is 10 s, consistent with the "MISSING at ~10s (T1),
        present at ~13s (T2)" pattern the Phase A dump would have shown for these two failures.
      - Checked `camunda-process-test-spring-8.7.6.jar`'s
        `CamundaProcessTestExecutionListener` (the class that runs `afterTestMethod` per Decision
        4) via `javap`: it only calls `CamundaAssert.initialize(...)` and `CamundaAssert.reset()`
        between tests — neither touches `Awaitility`'s default timeout. So a single
        `@BeforeAll` call raises the timeout for the whole test class run, and nothing silently
        resets it between the 4 tests.
      - Same element ids, same assertion severity (`hasCompletedElements` unchanged everywhere),
        BPMN/DMN untouched. Only how long `CamundaAssert` waits before it gives up changed.
- [ ] 4.3 Did not apply — see decision table classification above (row: technical incident in
      `disburse-loan` requires an incident with a non-`UNHANDLED_ERROR_EVENT` `errorType`; the
      `AGGREGATE` evidence instead shows the compensation completing, which is incompatible with a
      worker-level technical incident having blocked it). `DisbursementWorker` was not touched, per
      the explicit prohibition in this batch's instructions. Deferred to its own follow-up change.
- [x] 4.4 Ran the compile gate after all Phase B changes (see Work Unit Evidence below):
      `BUILD SUCCESS` on the first attempt.
- [ ] 4.5 / 4.6 — CI-only, not executed by this apply batch (no CI trigger from this agent).
- [x] 4.7 Confirmed by `git diff` inspection: no `continue-on-error`, no `-Dtest=` exclusion, no
      `@Disabled`, no `assumeTrue`, no element assertion removed, reordered, or weakened. The only
      assertion-related behavior change is the global `CamundaAssert` timeout increase.

### Additional cleanup done in this batch (orchestrator-directed, not separate `tasks.md` items)

- **Guarded `findApplication.findById` inside `@AfterEach`.** Wrapped the call in a `try/catch`
  directly inside `dumpDiagnostics(TestInfo)`, matching the "never throws, print DIAG-ERROR and
  continue" pattern already established by `ProcessDiagnostics`'s internal print methods. Reused
  the existing line format instead of duplicating it: `ProcessDiagnostics.printError` was widened
  from `private` to `public static` specifically so `@AfterEach` can call it on catch, keeping one
  formatting source of truth.
- **Removed the four dump sections that always return 401.** `ProcessDiagnostics.java` no longer
  has `printInstance`, `printElements`, `printIncidents`, or `printExpected` (all of which queried
  `ZeebeClient`'s REST-backed search API and failed with HTTP 401 in the real CI run). Only
  `printAggregate` (JPA-backed, unaffected by the 401) remains. Collapsed the class's public
  surface to `dump(TestInfo, long, CreditApplication)` — dropped the `ZeebeClient` and
  `expectedElementIds` parameters since nothing inside the class consumes them anymore. Also
  collapsed the T1/T2 double-shot mechanism (`Thread.sleep(Duration.ofSeconds(3))` + a second
  identical print) to a single print: the T1/T2 comparison existed specifically to detect the
  exporter-lag pattern in the now-removed `ELEMENTS` section, and printing the same JPA-read
  `AGGREGATE` state twice, 3 seconds apart, for every test would have been dead weight with no
  diagnostic value — the same "don't leave code that produces no evidence" principle behind
  removing the 401 sections. Documented `CamundaDataSource` migration as an explicit out-of-scope
  follow-up in the class Javadoc, per this batch's explicit instruction not to depend on that
  internal API in this PR.
  - **Deliberately left as-is, not removed**: `ObservedInstance.expectedElementIds()` and the
    4-call-site vararg lists in `CreditOriginationProcessTest` (`processInstanceKeyOf(...)`).
    Nothing consumes that field anymore internally, but it still serves as inline documentation of
    each scenario's expected completed-element path directly at the assertion call site, and
    removing it would touch all 4 test method bodies for no functional gain. Flagging this
    explicitly rather than silently leaving unused code unexplained.
- **Fixed `proposal.md`'s stale logging claim.** The "Alcance/Incluido" item 2 and the "Riesgos"
  table both said this change raises `io.camunda` from `WARN` to `DEBUG` globally. That was never
  what shipped — `design.md` Decisión 5 (verified: `JobPoller` logs at DEBUG on every poll of every
  `@JobWorker`, which would bury the dump) and `spec.md`'s "Log del motor con overrides acotados
  durante tests" requirement both specify `io.camunda: WARN` preserved plus two scoped overrides.
  Reworded both `proposal.md` sections to match what `spec.md`/`state.yaml` already reconcile to.
  `state.yaml` itself was not touched (out of scope per this batch's constraints).

## Files Changed (Batch 2)

| File | Action | What Was Done | Lines (add+del) |
|------|--------|---------------|---|
| `src/test/java/com/rriveros/origination/CreditOriginationProcessTest.java` | Modified | `@BeforeAll configureAssertionTimeout()` raising `CamundaAssert` timeout to 20s; `@AfterEach` now guards `findApplication.findById` with try/catch; `dump(...)` call updated to the new 3-arg signature | 35 lines changed (net) |
| `src/test/java/com/rriveros/origination/support/ProcessDiagnostics.java` | Modified | Removed `printInstance`/`printElements`/`printIncidents`/`printExpected` and their imports (`FlowNodeInstance`, `Incident`, `ProcessInstance`, `Comparator`, `Optional`, `ZeebeClient`); kept only `printAggregate`; collapsed T1/T2 double-shot to a single print; widened `printError` to `public static`; rewrote class Javadoc to document the 401 finding and the deferred `CamundaDataSource` follow-up | 194 lines changed (net; net line count shrinks because 4 sections were deleted) |
| `openspec/changes/fix-process-test-failures/proposal.md` | Modified | Reworded "Alcance/Incluido" item 2 and the "Riesgos" table row to match the shipped two-scoped-override logging decision instead of a global `io.camunda: DEBUG` | 10 lines changed |
| **Total Batch 2** | | | **~77 additions / ~162 deletions per `git diff --stat`** |

`git diff --stat` (this batch) confirms only these 3 files changed — no `src/main`, BPMN, DMN,
`pom.xml`, `README.md`, `.github/workflows/ci.yml`, `state.yaml`, or `ci-and-domain-tests/` files
touched.

## Deviations from Design (Batch 2)

1. **Removing the four 401-producing sections was not something `design.md`/`tasks.md` anticipated
   at Phase A authoring time** (design.md's Decision 1 flagged the search API's method names as
   unverified but did not know the 401 outcome ahead of the real run). This batch follows the
   orchestrator's explicit Phase B instruction, grounded in the actual PR1 CI evidence, which
   supersedes the original Phase A design's assumption that the search API would work at all
   against this CI's Camunda container.
2. **`ProcessDiagnostics.printError` visibility changed from `private` to `public`** — a narrow,
   intentional widening to let `@AfterEach` reuse the exact same DIAG-ERROR line format instead of
   duplicating the format string in the test class. No other method's visibility changed.
3. **`ObservedInstance`/`processInstanceKeyOf(...)` were not simplified** even though
   `expectedElementIds` is no longer consumed by `ProcessDiagnostics` — see "Additional cleanup"
   above for the explicit reasoning (inline documentation value vs. touching all 4 test bodies).

No other deviations. Design Decisions 2, 3, 4 (guard invariants: solo lectura, nunca lanza) are
still honored by the reduced `ProcessDiagnostics`; Decisión 5's logging choice (`io.camunda: WARN`
+ two scoped overrides) is untouched by this batch, only `proposal.md`'s stale description of it
was corrected.

## Issues Found (Batch 2)

- **Whether the Phase B fix actually turns CI green is unknowable from this environment.**
  `CreditOriginationProcessTest` cannot run locally (Docker Engine 29.3.1 vs Testcontainers 1.20.6
  → HTTP 400, per this batch's own constraints). The 20-second timeout choice is grounded in the
  verified default (10s) plus the T1(~10s)/T2(~13s) pattern the PR1 dump would show for these two
  failures, with margin — but it is a CI-only claim, not a locally verified one. Flagging this
  explicitly so `sdd-verify`/the next CI run is the actual gate, not this batch's local compile
  pass.
- Same pre-existing `spec.md`/`design.md` log-level note from Batch 1 still applies (Batch 1's
  Issues Found section, preserved above) — unaffected by this batch, `state.yaml`/`spec.md` still
  reconciled to the two-override approach, only `proposal.md` needed the correction (done in this
  batch).

## Work Unit Evidence (Batch 2)

| Evidence | Value |
|---|---|
| Focused test command and exact result | `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && cd C:/Repo/originacion-creditos && "C:/Users/rriveros/scoop/apps/maven/current/bin/mvn.cmd" -B test-compile` → `BUILD SUCCESS` on the first attempt after all Phase B edits; "Compiling 3 source files with javac [debug parameters release 21] to target\test-classes", no errors. Also ran `mvn -B test -Dtest=CreditApplicationTest` → `Tests run: 54, Failures: 0, Errors: 0, Skipped: 0` — unaffected by this batch (no `src/main` touched). |
| Runtime harness command/scenario and exact result | N/A, same reason as Batch 1 — `CreditOriginationProcessTest` cannot run locally (Docker/Testcontainers mismatch). Whether the `setAssertionTimeout` fix actually closes the 2 failures is **CI-only and unverified from this environment**; this must not be claimed as proven until a real CI run reports 58/58 `conclusion=success` (tasks 4.5/4.6). |
| Rollback boundary | `git revert` of this batch's commit reverts exactly 3 files: `CreditOriginationProcessTest.java`, `ProcessDiagnostics.java`, `proposal.md`. No `src/main`, BPMN, DMN, or `state.yaml` touched; independent of Batch 1's commit (Batch 1 can be reverted separately without needing to revert this batch, though this batch's `ProcessDiagnostics` changes are a further edit of the file Batch 1 created). |

## Remaining Tasks (cumulative)

- [ ] 3.1–3.4 — Phase A CI verification steps not owned by this apply batch (orchestrator/CI action;
      3.5's classification was however already consumed by this batch per the orchestrator's brief,
      which asserted the PR1 run as valid evidence)
- [ ] 4.3 — deferred to its own follow-up change, does not apply here (see above)
- [ ] 4.5–4.6 — Phase B CI verification (CI-only, next orchestrator action: open PR2, trigger CI)
- [ ] 5.1 — Closure (blocked until 4.6 is green; README untouched, correctly still blocked)

## Workload / PR Boundary (cumulative)

- Mode: stacked-to-main, PR2 of 2 (Phase B), base = main after PR1 merges
- Current work unit: Unit 2 — "Fase B: corrección en el área que indique la tabla de decisión"
- Boundary: starts from PR1's merged state and ends with the Phase B fix (assertion timeout),
  the `@AfterEach` guard, the 401-section removal, and the `proposal.md` correction, compiled and
  ready to push as PR2. CI verification (4.5/4.6) is the next action, owned by the orchestrator.
- Estimated review budget impact: this batch's diff is ~77 insertions / ~162 deletions (net
  shrink) across 3 files — well under the 400-line budget on its own. Combined with Batch 1's 269
  lines, cumulative diff across both PRs is under budget per-PR (they ship as separate PRs per the
  `stacked-to-main` chain strategy), so no `size:exception` is needed for either.

## Status (cumulative, current)

9/13 total tasks complete: 1.1, 1.2, 1.3, 2.1 (Batch 1) + 4.1, 4.2, 4.4, 4.7 (Batch 2). Task 4.3
resolved as "does not apply, deferred" (not a completable checkbox). Remaining: 3.1–3.4 (CI-only,
orchestrator action on PR1), 4.5–4.6 (CI-only, orchestrator action on PR2), 5.1 (blocked on 4.6).
Local implementation and compile gate for both Phase A and Phase B are done. Do NOT commit or push
from this batch per the apply-phase constraints — the orchestrator owns opening PR2 and triggering
CI. Whether the Phase B fix actually resolves the 2 failures is unverified and CI-only.
