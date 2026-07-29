# Apply Progress: fix-process-test-failures

## Scope of this batch

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

## Status

4/13 total tasks complete (1.1, 1.2, 1.3, 2.1). Phase A implementation and local compile gate are
done. Ready for the orchestrator to open PR1 and trigger the CI run (Phase 3) — do NOT commit or
push from this batch per the apply-phase constraints.
