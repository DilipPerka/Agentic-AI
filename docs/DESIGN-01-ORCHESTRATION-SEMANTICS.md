# Step 1 — Orchestration Semantics & Domain Model

**Status:** design only, no code.
**Satisfies:** CR2, CR4.0, CR4.2, CR4.3, CR4.4, CR4.5, CR4.11, CR7, EC2, EC6
**Exit criteria:** every state transition has a named trigger *and* a named guard;
the re-plan cascade is expressed as an algorithm, not prose.

---

## 1. The governing idea

Three rules generate almost everything below. If a design question comes up
later, resolve it by asking which rule applies.

| # | Rule | Consequence |
|---|------|-------------|
| **R1** | **A node may not declare its own success.** Success is granted by an exit gate that reads *evidence produced by tools*, not by the agent. | Kills "the agent said it worked". Makes CR4.3 real. |
| **R2** | **Waiting is a state, never a blocked thread.** | Approval gates survive restarts; other branches keep running. Makes CR4.6 and CR4.0 real. |
| **R3** | **Everything an agent reads or writes is declared and content-hashed.** | Enables fingerprint-based invalidation, least-privilege context, and lineage. Makes CR4.5 and CR4.11 real. |

---

## 2. Domain model

### 2.1 Entity map

```
Requirement ──1:N── Plan(version) ──1:N── Node ──1:N── NodeAttempt
     │                   │                  │              │
     │                   └── Edge            │              └── ToolInvocation
     │                                       ├── GateCheck (entry/exit)
     │                                       ├── Artifact
     │                                       ├── Decision
     │                                       └── ApprovalRequest
Run ──1:N── Event (append-only, monotonic seq)
Run ──1:N── ContextEntry (the blackboard)
Policy (global, versioned)
```

### 2.2 Core entities

**`Run`** — one execution against one requirement.
`id · requirementId · scenarioType{GREENFIELD, BROWNFIELD, AMBIGUOUS} · autonomyLevel{L0..L3} · workspaceRef{path, baseGitRef} · status · activePlanVersion · replanCount · createdAt · endedAt`

**`Requirement`** — raw intent plus its normalised form. Versioned, because clarification amends it.
`id · version · rawText · normalisedStatement · acceptanceCriteria[] · ambiguities[] · assumptions[] · outOfScope[]`

**`Plan`** — an immutable, versioned DAG. Re-planning never mutates a plan; it emits `version+1`.
`id · runId · version · supersedes · nodes[] · edges[] · authoredBy · rationale · planHash`

**`Node`** — one unit of work.
`id · stage · type · agentRole · reads[] · writes[] · entryGate · exitGate · retryPolicy · compensation · joinPolicy · declaredBlastRadius · status · attempt · fingerprint`

**`Edge`** — `from · to · kind{DATA, CONTROL, GUARD}`
`DATA` edges are *derived* from `reads`/`writes` overlap, not hand-authored. `CONTROL` is pure ordering. `GUARD` carries a predicate and may prune a branch to `SKIPPED`.

**`Artifact`** — anything produced.
`id · nodeId · type · uri · contentHash · supersedes`
Types: `REQUIREMENT_SPEC, AMBIGUITY_REGISTER, IMPACT_ANALYSIS, DESIGN_DOC, ADR, SOURCE_FILE, TEST_FILE, MIGRATION, BUILD_LOG, TEST_REPORT, COVERAGE_REPORT, DIFF, DOC, RISK_REGISTER, RELEASE_NOTE`

**`Decision`** — the unit of lineage. First-class, not a log line.
`id · nodeId · actor{AGENT:role | HUMAN:id | POLICY:name} · question · choice · rationale · alternativesConsidered[] · inputsConsulted[artifactId|decisionId] · confidence · timestamp`

**`ContextEntry`** — the blackboard. Scoped key/value with provenance.
`key · valueRef · writtenByNodeId · planVersion · contentHash`

**`Policy`** — `id · category{SECURITY, COMPLIANCE, CHANGE_CONTROL} · scope{TOOL_CALL, NODE, ARTIFACT, PLAN} · rule · effect{DENY, REQUIRE_APPROVAL, WARN} · severity`

**`ApprovalRequest`** — `id · nodeId|planVersion · reason · blastRadius · impactSummary · diffRef · status{PENDING, APPROVED, REJECTED, EXPIRED} · decidedBy · guidance`

**`Event`** — append-only, monotonic `seq` per run. The audit trail *and* the SSE feed (D9).
`seq · runId · nodeId? · type · actor · payload · timestamp`

**`ToolInvocation`** — `id · attemptId · tool · args · policyVerdict · exitCode · stdoutRef · durationMs`
Written **before** execution, so a crash mid-tool-call is detectable on recovery.

### 2.3 SDLC stage taxonomy (CR4.1)

| Stage | Present in |
|---|---|
| `REQUIREMENTS` | all runs |
| `CLARIFICATION` | ambiguous runs (conditional branch) |
| `IMPACT_ANALYSIS` | brownfield runs (conditional branch) |
| `DESIGN` | all runs |
| `PLANNING` | all runs |
| `IMPLEMENTATION` | all runs |
| `TESTING` | all runs |
| `REVIEW` | all runs |
| `DOCUMENTATION` | all runs |
| `RELEASE_READINESS` | all runs |

The two conditional stages are what make the graph *shape-dependent on the
requirement* — the same engine produces three visibly different DAGs. That is
the cheapest, most legible proof of CR4.0.

---

## 3. Node lifecycle

### 3.1 States

| State | Meaning | Terminal? |
|---|---|---|
| `PENDING` | exists; dependencies unsatisfied | no |
| `READY` | entry gate satisfied; queued for dispatch | no |
| `AWAITING_APPROVAL` | durable pause; a human must decide | no |
| `RUNNING` | agent executing | no |
| `RETRYING` | failed; backoff timer armed | no |
| `BLOCKED` | policy `DENY`, or unmet non-temporal precondition | no |
| `SUCCEEDED` | exit gate satisfied with evidence | soft* |
| `FAILED` | retries exhausted or non-retryable | soft* |
| `COMPENSATING` | rollback executing | no |
| `ROLLED_BACK` | side effects undone | soft* |
| `SKIPPED` | branch pruned by a `GUARD` edge or re-plan | soft* |
| `INVALIDATED` | upstream changed; must re-run | no |
| `CANCELLED` | safe-stop | yes |

\* "soft terminal": terminal for scheduling, but re-openable by invalidation.
Only `CANCELLED` is hard-terminal. This distinction is what makes CR4.11 possible.

### 3.2 Transition table — every edge has a trigger *and* a guard

| From | → To | Trigger | Guard |
|---|---|---|---|
| `PENDING` | `READY` | dependency reached terminal state | `joinPolicy` satisfied **AND** `entryGate == SATISFIED` |
| `PENDING` | `SKIPPED` | guard-edge evaluated | `GUARD` predicate false |
| `PENDING` | `BLOCKED` | policy evaluation | any policy `effect == DENY` |
| `READY` | `AWAITING_APPROVAL` | dispatch attempt | `approvalMatrix[autonomy][blastRadius] == APPROVE` and no valid approval |
| `READY` | `RUNNING` | dispatch attempt | approval not required or granted; concurrency slot free; run not paused |
| `AWAITING_APPROVAL` | `READY` | approval granted | approver authorised; approval not expired |
| `AWAITING_APPROVAL` | `FAILED` | approval rejected | rejection without guidance |
| `AWAITING_APPROVAL` | `INVALIDATED` | rejected **with guidance** | guidance admitted → triggers re-plan (§6) |
| `RUNNING` | `SUCCEEDED` | agent returned | `exitGate == SATISFIED` (all checks `PASS` or `WAIVED`) |
| `RUNNING` | `RETRYING` | agent error **or** exit gate failed | `attempt < maxAttempts` **AND** error class ∈ retryable |
| `RUNNING` | `FAILED` | agent error **or** exit gate failed | retries exhausted **OR** error non-retryable |
| `RUNNING` | `BLOCKED` | mid-execution policy check | tool call hit a `DENY` policy |
| `RETRYING` | `READY` | backoff timer elapsed | run still active; not safe-stopped |
| `FAILED` | `COMPENSATING` | rollback initiated (auto by policy, or human) | node declares a compensation action |
| `COMPENSATING` | `ROLLED_BACK` | compensation returned | compensation verified (workspace clean per `git status`) |
| `COMPENSATING` | `BLOCKED` | compensation failed | **partial rollback — always requires human** |
| `SUCCEEDED` | `INVALIDATED` | fingerprint recomputation | `fingerprint != storedFingerprint` |
| `SKIPPED` | `PENDING` | re-plan admitted | guard predicate now true |
| `INVALIDATED` | `PENDING` | re-plan admitted | compensation complete if node had side effects |
| *any non-hard-terminal* | `CANCELLED` | safe-stop | always permitted |

**Note the two most defensible rows:**
- `COMPENSATING → BLOCKED` — a *partial* rollback is the most dangerous state in
  any orchestrator. We never auto-retry it; it escalates to a human unconditionally.
- `AWAITING_APPROVAL → INVALIDATED` — rejecting with guidance doesn't just fail
  the node, it feeds the human's reasoning back into the planner. That is the
  human/agent feedback loop the brief asks for in §7.

---

## 4. Run lifecycle

| State | Meaning |
|---|---|
| `CREATED` | requirement accepted, not yet normalised |
| `PLANNING` | Planner agent building plan v1 |
| `RUNNING` | ≥1 node dispatchable or running |
| `AWAITING_INPUT` | **quiesced**: nothing can progress; ≥1 node awaiting approval or clarification |
| `REPLANNING` | invalidation cascade + new plan version being admitted |
| `PAUSED` | human-initiated hold |
| `COMPLETED` / `FAILED` / `STOPPED` / `ROLLED_BACK` | terminal |

**Critical subtlety:** a run is `AWAITING_INPUT` only when *no other node can make
progress*. If one branch awaits approval while another compiles, the run stays
`RUNNING`. This is the observable difference between an orchestrator and a script,
and it should be visible in the UI during the demo.

---

## 5. Gates (CR4.3)

A `Gate` is an ordered list of `Check`s. Each yields
`CheckResult{name, verdict ∈ {PASS, FAIL, WAIVED}, evidenceArtifactId, message}`.
`Gate == SATISFIED` iff no check is `FAIL`.

### 5.1 Entry checks
| Check | Passes when |
|---|---|
| `DependenciesSatisfied` | `joinPolicy` met over incoming edges |
| `RequiredInputsPresent` | every declared `read` key resolves to an artifact/context entry |
| `PolicyPermits` | no `DENY` policy matches this node |
| `ApprovalGranted` | approval present if the matrix demands one |
| `RunActive` | run not paused, stopped, or replanning |

### 5.2 Exit checks (selected by node type)
| Check | Evidence source |
|---|---|
| `ArtifactsProduced` | declared `writes` exist, non-empty, hashed |
| `BuildGreen` | `mvn -q compile` exit code |
| `TestsPassed` | parsed Surefire/Failsafe XML — **and no regression vs the baseline count** |
| `CoverageThreshold` | JaCoCo report ≥ configured line/branch floor |
| `StaticAnalysisClean` | max severity ≤ threshold |
| `SecretScanClean` | no credential patterns in the diff |
| `ReviewVerdict` | Reviewer agent returned `APPROVE` |
| `SchemaMigrationValid` | Flyway dry-run applies cleanly |
| `NoUncommittedDrift` | `git status --porcelain` empty after commit node |

### 5.3 Waivers
A `FAIL` may be downgraded to `WAIVED` **only** by a human approval carrying a
written justification. Waivers are audited, surfaced in release readiness, and
listed in `SUMMARY.md`. Agents can never waive their own gate — that would
collapse R1.

---

## 6. Scheduling (CR4.4)

### 6.1 Algorithm

```
onEvent(e):                      # event-driven, not polled
  if e ∈ {NODE_TERMINAL, APPROVAL_DECIDED, TIMER_FIRED, PLAN_ADMITTED, RUN_RESUMED}:
      tick(run)

tick(run):
  if run.status ∈ {PAUSED, STOPPED, REPLANNING}: return

  ready = { n ∈ plan.nodes
            | n.status ∈ {PENDING, RETRYING(elapsed), INVALIDATED(admitted)}
            ∧ joinSatisfied(n)
            ∧ evaluateEntryGate(n) == SATISFIED }

  for n in ready, bounded by maxConcurrency:
      verdict = approvalMatrix[run.autonomy][blastRadius(n)]
      if verdict == DENY:     n → BLOCKED;            continue
      if verdict == APPROVE and not hasValidApproval(n):
          raiseApprovalRequest(n); n → AWAITING_APPROVAL; continue
      n → RUNNING; dispatch(n) on a virtual thread

  quiesce(run)                   # see 6.3

  # safety net for timers and crash recovery only
  scheduleSweep(every 5s)
```

### 6.2 Join policies
A node with multiple incoming edges *is* a synchronisation barrier. Policy is explicit:

| Policy | `READY` when |
|---|---|
| `ALL` (default) | every dependency `SUCCEEDED` |
| `ANY` | ≥1 dependency `SUCCEEDED` (others → `SKIPPED`) |
| `N_OF_M` | ≥N dependencies `SUCCEEDED` |
| `ALL_SETTLED` | every dependency terminal, regardless of outcome — used by `RELEASE_READINESS`, which must report on failures too |

### 6.3 Quiescence
When no node is `RUNNING` and `ready == ∅`:
- any `AWAITING_APPROVAL` → run `AWAITING_INPUT`
- else any `FAILED` → run `FAILED` (and trigger compensation if policy says so)
- else any `BLOCKED` → run `FAILED` with policy reason
- else → run `COMPLETED`

### 6.4 Idempotency & crash recovery
- Execution is keyed by `(nodeId, attempt)`; a duplicate dispatch is a no-op.
- `ToolInvocation` rows are written *before* the tool runs. On startup, any
  invocation with no exit code means "crashed mid-call" → the attempt is failed
  and compensated, never silently resumed.
- On boot, every `RUNNING` node is demoted to `RETRYING` and re-driven through
  its compensation first.

---

## 7. Invalidation & re-planning (CR4.11)

This is the hardest requirement in the brief and the one most people fake.
We solve it with **content fingerprints**, borrowed from build systems.

### 7.1 Fingerprint

```
fingerprint(n) = H( n.definitionHash
                  ‖ n.agentRole ‖ n.blueprintVersion
                  ‖ H(sorted contentHash of every key in n.reads) )
```

Because `reads`/`writes` are declared (R3), this is computable without running
anything — which is what lets us detect staleness *cheaply and provably*.

### 7.2 Cascade algorithm

```
replan(run, trigger):
  assert run.replanCount < MAX_REPLANS                    # bound (§7.4)
  run → REPLANNING

  # 1. detect
  stale = ∅
  for n in topologicalOrder(plan):
      if n.status == SUCCEEDED and fingerprint(n) != n.storedFingerprint:
          stale ∪= {n}
  stale ∪= transitiveDependents(stale)

  # 2. compensate BEFORE re-execution, so the workspace cannot drift
  for n in reverseTopological(stale) where n.hasSideEffects:
      run n.compensation                                   # e.g. git revert <sha>
      n → ROLLED_BACK

  # 3. re-plan
  newPlan = PlannerAgent.plan(requirement, context, stale, trigger)
  diff    = planDiff(activePlan, newPlan)   # added / removed / modified / retained

  # 4. GOVERN THE PLAN ITSELF  <-- the differentiator
  if diff.removesApprovedArtifacts or diff.raisesMaxBlastRadius
     or diff.size > REPLAN_APPROVAL_THRESHOLD:
        raiseApprovalRequest(PLAN, diff); run → AWAITING_INPUT; return

  # 5. admit
  if newPlan.planHash ∈ run.recentPlanHashes: abort("re-plan oscillation")
  activePlan = newPlan; run.replanCount++
  retained nodes keep SUCCEEDED + artifacts     # no wasted work
  stale nodes → PENDING
  run → RUNNING; tick(run)
```

### 7.3 Triggers
1. Requirement amended (human clarification in an ambiguous run)
2. Approval **rejected with guidance**
3. An upstream node re-executed and produced a different output hash
4. A policy version changed mid-run

### 7.4 Bounds — because unbounded re-planning is a real failure mode
- `MAX_REPLANS` per run (default 3), then safe-stop
- **Plan-hash oscillation detector**: if a newly authored plan matches one of the
  last *k* plan hashes, abort rather than loop
- Every re-plan emits `PLAN_REVISED` with the diff, so the UI can show the graph
  changing shape — the single most compelling thing in the demo

**Step 4 of the algorithm is the phrase "while maintaining governance" from the
brief, made concrete: the plan is itself a governed artifact, not a free action.**

---

## 8. Controlled autonomy (CR7, CR4.6, CR4.8)

### 8.1 Blast-radius classification

| Radius | Signals |
|---|---|
| `CRITICAL` | touches secrets/credentials · modifies build or CI config · deletes outside declared scope · git history rewrite |
| `HIGH` | DB schema migration · breaking public API change · dependency added/upgraded · auth or security code · edits the redirect hot path |
| `MEDIUM` | additive endpoint · config change · refactor spanning > N files |
| `LOW` | tests only · docs only · comments · formatting |

Classification is computed from the **actual diff and tool calls**, not from what
the agent claims it is doing. An agent cannot talk its way down a tier.

### 8.2 Approval matrix

|          | LOW | MEDIUM | HIGH | CRITICAL |
|----------|-----|--------|------|----------|
| **L0** Observe   | APPROVE | APPROVE | APPROVE | DENY |
| **L1** Supervised| AUTO    | APPROVE | APPROVE | DENY |
| **L2** Delegated *(default)* | AUTO | AUTO | APPROVE | DENY |
| **L3** Autonomous| AUTO    | AUTO    | AUTO    | APPROVE |

Two invariants:
1. **`CRITICAL` is never `AUTO`, at any autonomy level.**
2. **Autonomy tunes approval; it never overrides policy.** A policy `DENY` blocks
   an L3 run exactly as it blocks an L0 run. Approval is discretionary,
   policy is absolute — keeping these separate is what makes "controlled
   autonomy" a control rather than a slogan.

---

## 9. Context & lineage (CR4.5)

### 9.1 Least-privilege context
An agent receives **only its declared `reads`** — never the whole run history.
Three benefits at once: deterministic fingerprints, a smaller LLM prompt, and a
security boundary (an Implementer node cannot read credentials it never declared).

### 9.2 The lineage query
The traversal that answers *"why does this file exist?"*:

```
Artifact(LinkController.java)
  → producedBy Node(IMPLEMENT_LINK_API, attempt 2)
      → Decision "Base62 over UUID"  (actor AGENT:Architect, rationale, alternatives[])
      → Decision "approved schema change" (actor HUMAN:sravan, ApprovalRequest#7)
      → inputsConsulted → Artifact(design.md) → Node(DESIGN) → Decision …
          → Requirement v2, clause AC-3
```

Every hop is a stored foreign key, not a reconstruction. This traversal is
exposed as one API call and rendered in the node inspector — it is the
demonstration of CR4.5 and CR4.9 together.

---

## 10. Persistence sketch

Tables: `run · requirement · plan · node · edge · gate_check · artifact ·
decision · context_entry · policy · approval_request · event · tool_invocation ·
node_attempt · metric_snapshot`

Indices that matter:
- `event(run_id, seq)` — SSE resume via `Last-Event-ID`
- `node(run_id, status)` — the scheduler's ready-set query
- `approval_request(status)` — the approval inbox
- `artifact(content_hash)` — fingerprinting

Store: **H2 file-mode** + Flyway. Swappable to Postgres via one property and one
dependency; the choice is not scored, and single-command startup is worth more
than the appearance of production infrastructure.

---

## 11. What this design buys us, per requirement

| Req | Mechanism |
|---|---|
| CR4.0 | Soft-terminal states + quiescence + `AWAITING_INPUT` ≠ `RUNNING` |
| CR4.2 | `Plan` as an immutable versioned DAG with derived `DATA` edges |
| CR4.3 | Gates as evidence-backed check lists (R1); waivers require a human |
| CR4.4 | Ready-set dispatch + explicit `joinPolicy` (4 kinds) |
| CR4.5 | First-class `Decision` + declared `reads`/`writes` + lineage traversal |
| CR4.6 | `AWAITING_APPROVAL` as durable state (R2) + approval matrix |
| CR4.7 | Retry/compensation/safe-stop in the transition table; partial rollback always escalates |
| CR4.8 | Policy absolute and orthogonal to autonomy |
| CR4.9 | Append-only `Event` with monotonic seq = audit trail = SSE feed |
| CR4.11 | Fingerprint cascade + plan diff + **governed plan admission** + oscillation bound |
| CR7 | Autonomy matrix; blast radius computed from real diffs, not agent claims |

---

## 12. Open risks carried into Step 2

1. **Blueprint fidelity** — the deterministic runtime templates code; genuine
   synthesis needs `ClaudeRuntime`. Declared in `SUMMARY.md`, not hidden.
2. **Compensation completeness** — `git revert` covers file changes; it does not
   cover a DB row an agent inserted. Mitigation: workspace tools are file-and-git
   only; agents never touch a live datastore.
3. **Fingerprint precision** — over-broad `reads` cause over-invalidation
   (wasted work, not incorrectness). Acceptable; noted as a trade-off.
4. **Concurrency vs. workspace** — parallel nodes writing the same files will
   conflict. Mitigation: enforce **disjoint `writes` among concurrently
   dispatchable nodes**, validated at plan-construction time in Step 4.2.
