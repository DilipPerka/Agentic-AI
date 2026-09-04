# Agentic SDLC Orchestrator — Master Plan & Traceability

**Purpose:** single source of truth for what we build, in what order, and which
requirement each step satisfies. If something isn't traceable to an ID in
section 2, we don't build it.

**Status legend:** `[ ]` not started · `[~]` in progress · `[x]` done · `[!]` blocked

---

## 0. The one-line framing

> We are building an **Agentic SDLC Orchestrator** (the product).
> A **URL shortener** is the workload it generates and evolves (the evidence).

Requirement §4 contains no mention of URLs — every noun in it describes a
workflow engine. That is the tell, and it drives every decision below.

```
sdlc-orchestrator/          <- the product (Spring Boot 3, multi-module)
└── workspace/
    └── urlshortener/       <- the workload; created & modified by the orchestrator
```

The orchestrator never imports the shortener. It touches it only through a
sandboxed tool interface (path-jailed filesystem, maven, git). That boundary is
a governance control, not just tidiness.

---

## 1. Decision log (ADRs)

| ID | Decision | Rationale |
|----|----------|-----------|
| D1 | Orchestrator is the product; URL shortener is its workload | §4 and §6 score orchestration, not URL shortening |
| D2 | Pluggable `AgentRuntime`: deterministic default + Claude provider | Reproducible offline demo; LLM path proves genuine agency |
| D3 | Real execution — real files, real `mvn test`, real `git` | Exit gates and rollback must be evidence-backed, not simulated |
| D4 | JDK 21 + Spring Boot 3.x | Records, sealed types, virtual threads for parallel branches |
| D5 | API-first; console UI is a pure client of the REST API | Headless testability; no orchestration logic in JavaScript |
| D6 | No-build static UI + Mermaid + SSE, served from the jar | Single `java -jar`; zero build risk; UI polish is not scored |
| D7 | Six console screens + demo page for the generated shortener | Covers every UI-dependent requirement; closes the demo loop |
| D8 | Async engine + persisted state from day one | Approval gates must not block threads; runs must survive restart |
| D9 | Event log is both the audit trail and the SSE feed | One mechanism satisfies CR4.9 and the live UI |

---

## 2. Requirement inventory (IDs used everywhere below)

### §2 Scenario
| ID | Requirement |
|----|-------------|
| SC1 | URL shortener built from scratch with core APIs |
| SC2 | Analytics |
| SC3 | Reliability features |
| SC4 | Complete and improve over 2–3 days using AI assistance |
| SC5 | Demonstrate engineering judgment |

### §3 Scope
| ID | Requirement |
|----|-------------|
| SP1 | Greenfield scenarios (new systems/features) |
| SP2 | Brownfield scenarios (enhancements, refactors, bug fixes) |
| SP3 | Test and documentation improvements |
| SP4 | Well-defined **and ambiguous** requirements |

### §4 Core Requirements
| ID | Requirement |
|----|-------------|
| CR1 | Requirement understanding: interpret intent, identify ambiguity, normalize |
| CR2 | Task decomposition into actionable tasks with dependencies and sequencing |
| CR3 | Codebase reasoning (brownfield): impacted modules/services/APIs/data flows |
| **CR4** | **Workflow orchestration (critical differentiator) — expanded below** |
| CR4.0 | Non-linear, **stateful** execution with governance, *not* linear task chaining |
| CR4.1 | Coordinates full SDLC: requirements → architecture/design → implementation → testing → documentation → release readiness |
| CR4.2 | Explicit dependency **graph** (a real, serialisable DAG) |
| CR4.3 | **Entry/exit gates** on every node (preconditions + evidence-backed acceptance) |
| CR4.4 | Sequential **and parallel** paths with synchronisation (join barriers) |
| CR4.5 | Preserve cross-stage **context and decision lineage** |
| CR4.6 | Enforce **human approval checkpoints** for high-impact actions |
| CR4.7 | Bounded **retries, fallback, rollback, safe-stop** |
| CR4.8 | **Policy guardrails**: security, compliance, change control |
| CR4.9 | **Audit-grade observability and traceability** |
| CR4.10 | Reliability **metrics**: success rate, retry/rollback frequency, MTTR, end-to-end latency |
| CR4.11 | **Dynamic re-planning** when upstream outputs change |
| CR5 | Engineering output: production-quality code, API/schema definitions, unit + integration tests, docs |
| CR6 | Validation & risk control: risks, trade-offs, failure scenarios, validation, safety guardrails |
| CR7 | Controlled autonomy: agents execute multi-step work; humans oversee, approve, own final QC |
| CR8 | Final engineering summary: plan/rationale, artifacts, risks/trade-offs/validation, assumptions, limitations |

### §5 Deliverables
| ID | Deliverable |
|----|-------------|
| DL1 | Working prototype, runnable end-to-end |
| DL2 | Architecture overview: components, orchestration model, control flow, key decisions |
| DL3 | Three scenarios — greenfield, brownfield, ambiguous — each showing decomposition, orchestration, validation |
| DL4 | Setup instructions |
| DL5 | Testing approach, limitations, trade-offs |

### §6 Evaluation Criteria
| ID | Criterion |
|----|-----------|
| EC1 | Effectiveness of agentic orchestration |
| EC2 | Architecture / system design quality |
| EC3 | Depth of decomposition and execution quality |
| EC4 | Realism / quality of outputs |
| EC5 | Validation and risk-management rigour |
| EC6 | Clarity and defensibility of decisions |
| EC7 | Core engineering principles: modular, testable, reliable, secure, scalable, safe change management |
| EC8 | Engineering judgment |

---

## 2b. Delivery approach — vertical slices

Phases A–E below describe *what* must exist, not the order we build it. We deliver in **runnable
increments**, each one end-to-end (domain → API → tests), so there is always something working
rather than three phases of design and a cliff.

| Increment | Delivers | Steps touched | Status |
|---|---|---|---|
| **1** | Requirement decomposition: requirement in → task graph with dependencies and sequencing out | CR2 (part of 1, 3, 7) | `[x]` **done — 42/42 tests green, all 3 scenarios verified running** |
| **2** | Execution engine: async scheduler, node state machine, parallel dispatch + joins, failure propagation, append-only event log, safe-stop | 4 | `[x]` **done — 65/65 tests green, verified live** |
| **3a** | Governance: entry/exit gates, non-blocking approvals, policy engine, autonomy L0–L3, decision log | 5 | `[x]` **done — 99/99 tests green, verified live** |
| **3b** | Persistence: H2 + Flyway, write-through on every transition, startup recovery; runs survive restart while awaiting approval | 5 | `[x]` **done — 101/101 tests green, verified across a real process restart** |
| **4a** | Reliability: bounded retry with backoff, node timeouts, degraded fallback, compensating rollback, pause/resume | 6 | `[x]` **done — 118/118 tests green, verified live** |
| **4b** | Re-planning: fingerprint invalidation cascade, plan diff, governed plan admission, oscillation bound, rejection-guidance feedback loop | 6.6 | `[x]` **done — 139/139 tests green, verified live. CR4 complete.** |
| **5** | Agents + sandboxed tools: path-jailed filesystem, allowlisted processes, real `mvn` and `git`, content screening, evidence-backed exit gates, git-revert compensation | 7 | `[x]` **done — 186/186 tests green; a real URL shortener generated, 19 of its own tests passing** |
| **6** | Metrics, SSE, console UI — reliability metrics derived from the event log, live SSE feed with replay, six-screen browser console, `DEMO.md` | 8 | `[x]` **done — 196/196 tests green; whole system drivable from the browser** |
| **7** | Scenario runs: greenfield and brownfield executed end-to-end in agent mode, with baseline seeding so brownfield modifies a real codebase | 9, 10 | `[x]` **partial — greenfield + brownfield verified (34 generated tests green); ambiguous scenario and the written scenario docs not done** |
| 8 | Docs, risk register, summary | 12, 13, 14 | `[ ]` |

---

## 3. The steps

### PHASE A — Design (no code)

#### `[x]` Step 0 — Requirement normalisation
- `[x]` 0.1 Identify the two-system model (orchestrator vs workload)
- `[x]` 0.2 Record ambiguities in the brief itself (from-scratch vs improve; "agentic" undefined; rollback scope; no SLO targets; prototype vs production-grade)
- `[x]` 0.3 Lock decisions D1–D9
- `[x]` 0.4 Define the workload spec (what the shortener must become)
- **Output:** this document
- **Satisfies:** CR1, SC5, EC6, EC8

#### `[x]` Step 1 — Orchestration semantics & domain model
> **Output:** [`docs/DESIGN-01-ORCHESTRATION-SEMANTICS.md`](docs/DESIGN-01-ORCHESTRATION-SEMANTICS.md)
- `[x]` 1.1 Core entities: `Run`, `Requirement`, `Plan`, `Node`, `Edge`, `Gate`, `Artifact`, `Decision`, `ContextEntry`, `Policy`, `ApprovalRequest`, `Event`, `ToolInvocation`
- `[x]` 1.2 **Node lifecycle state machine** — 13 states, full transition table with trigger *and* guard per edge; soft vs hard terminal distinction
- `[x]` 1.3 **Run lifecycle state machine** — incl. `AWAITING_INPUT` quiescence rule (run stays `RUNNING` while any branch can progress)
- `[x]` 1.4 Gate semantics: entry/exit check lists, `CheckResult` with evidence refs, human-only waivers
- `[x]` 1.5 Scheduling algorithm: event-driven ready-set dispatch, 4 join policies, quiescence, idempotency & crash recovery
- `[x]` 1.6 Invalidation & re-plan: content-fingerprint cascade, compensate-before-re-execute, plan diff, **governed plan admission**, oscillation bound
- `[x]` 1.7 Autonomy L0–L3 + blast-radius classification + approval matrix (2 invariants: `CRITICAL` never auto; autonomy never overrides policy)
- `[x]` 1.8 Persistence model / ER sketch + the indices that matter
- **Exit criteria:** ✅ every state transition has a named trigger and guard; ✅ re-plan cascade written as an algorithm
- **Satisfies:** CR2, CR4.0, CR4.2, CR4.3, CR4.4, CR4.5, CR4.11, CR7, EC2, EC6

#### `[ ]` Step 2 — Architecture & module design
- `[ ]` 2.1 Maven multi-module layout and dependency direction rules
- `[ ]` 2.2 Component diagram + control-flow diagram
- `[ ]` 2.3 Sequence diagrams: happy path · approval pause/resume · failure → retry → rollback · re-plan cascade
- `[ ]` 2.4 Concurrency model (virtual threads, ready-set dispatch, idempotency of node execution)
- `[ ]` 2.5 Security model: sandbox boundary, path jail, command allowlist, secret handling
- `[ ]` 2.6 REST API surface design
- **Exit criteria:** a reader can explain the control flow without reading code
- **Satisfies:** DL2, EC2, EC6, EC7

---

### PHASE B — Kernel

#### `[ ]` Step 3 — Bootstrap
- `[ ]` 3.1 Install JDK 21 (Temurin), verify `mvn -v` uses it
- `[ ]` 3.2 Multi-module skeleton: `core`, `agents`, `tools`, `api`, `app`
- `[ ]` 3.3 Spring Boot 3.x, H2 (file mode) + Flyway, Actuator, springdoc-openapi
- `[ ]` 3.4 Test scaffolding: JUnit 5, AssertJ, Testcontainers-optional, JaCoCo
- `[ ]` 3.5 Green build + `/actuator/health` responding
- **Exit criteria:** `mvn clean verify` green from a clean clone
- **Satisfies:** DL1, EC7

#### `[ ]` Step 4 — Graph engine (the kernel) ⭐ critical path
- `[ ]` 4.1 Domain entities + JPA mappings + Flyway migrations
- `[ ]` 4.2 DAG construction + validation (cycle detection, unreachable-node detection, **disjoint `writes` among concurrently dispatchable nodes**)
- `[ ]` 4.3 Async scheduler: ready-set computation, dispatch on virtual threads
- `[ ]` 4.4 Parallel branches + join/barrier synchronisation
- `[ ]` 4.5 Entry/exit gate evaluation engine
- `[ ]` 4.6 Append-only `Event` log + publisher (backbone for audit **and** SSE)
- `[ ]` 4.7 Crash recovery: rehydrate in-flight runs on startup
- `[ ]` 4.8 Engine unit + integration tests, including a restart-mid-run test
- **Exit criteria:** a run with a parallel branch + join executes correctly, is killed mid-flight, restarts, and completes
- **Satisfies:** CR4.2, CR4.3, CR4.4, CR4.9 (partial), EC1, EC2, EC7

#### `[ ]` Step 5 — Governance layer ⭐ critical path
- `[ ]` 5.1 Policy engine + policy definitions (security / compliance / change control)
- `[ ]` 5.2 Blast-radius classifier (schema change, public API change, dependency add, secret touch, hot-path edit)
- `[ ]` 5.3 Autonomy levels L0–L3 wired to the classifier
- `[ ]` 5.4 `ApprovalRequest` lifecycle; **non-blocking** `AWAITING_APPROVAL`; approve/reject/reject-with-guidance API
- `[ ]` 5.5 Append-only audit log with actor attribution (agent vs human)
- `[ ]` 5.6 `Decision` records with provenance links (lineage)
- `[ ]` 5.7 Tests: policy denial halts a node; approval resumes a run; audit is immutable
- **Exit criteria:** a policy-violating agent action is blocked and audited; a high-impact action waits for a human across a restart
- **Satisfies:** CR4.5, CR4.6, CR4.8, CR4.9, CR7, EC5

#### `[ ]` Step 6 — Reliability & failure control
- `[ ]` 6.1 Bounded retry with backoff, per-node retry policy
- `[ ]` 6.2 Fallback strategies (degrade to a simpler agent/strategy on repeated failure)
- `[ ]` 6.3 Compensating rollback: per-node-type compensation (git revert, artifact removal, state unwind)
- `[ ]` 6.4 Safe-stop / kill switch / pause / resume
- `[ ]` 6.5 Tool timeouts + circuit breaking
- `[ ]` 6.6 Re-plan trigger on upstream change → invalidation cascade (implements 1.6)
- `[ ]` 6.7 **Failure-injection tests** — deliberately fail a build and assert retry → rollback → safe-stop
- **Exit criteria:** all four mechanisms in CR4.7 are individually test-proven
- **Satisfies:** CR4.7, CR4.11, CR6, EC5

---

### PHASE C — Agents & surface

#### `[ ]` Step 7 — Agent runtime + sandboxed tool layer ⭐ critical path
- `[ ]` 7.1 `AgentRuntime` SPI; `DeterministicRuntime` (blueprint library) + `ClaudeRuntime` (API-backed)
- `[ ]` 7.2 Agent roster: RequirementAnalyst · Planner · Architect · CodebaseAnalyst · Implementer · TestEngineer · Reviewer · DocWriter · ReleaseManager
- `[ ]` 7.3 Agent contract: typed input/output, structured `Decision` emission with rationale
- `[ ]` 7.4 Tool layer: `FileSystemTool` (path-jailed), `MavenTool`, `GitTool`, `StaticAnalysisTool` — every call policy-checked and audited
- `[ ]` 7.5 Sandbox enforcement: workspace jail, command allowlist, no network by default
- `[ ]` 7.6 Blueprint / prompt library for the deterministic runtime
- `[ ]` 7.7 Tests including **sandbox escape attempts** (`../` traversal, absolute paths, disallowed commands)
- **Exit criteria:** an agent can produce a compiling Spring Boot class and cannot write outside the workspace
- **Satisfies:** CR1, CR3, CR4.1, CR5, CR7, EC1, EC4, EC7

#### `[x]` Step 8 — Observability, metrics, API, console UI
- `[x]` 8.1 Metrics: run + node success rate · retry frequency · rollback frequency · MTTR · end-to-end and per-node latency (p95/max) · per-stage latency · approval wait. Derived from the event log at request time rather than counted — a counter that drifts from the log is worse than no counter. Rates are `null`, never `0`, when the denominator is empty.
- `[x]` 8.2 Complete REST API: runs, nodes, approvals, events, decisions, metrics, controls
- `[x]` 8.3 SSE endpoints `/runs/{id}/stream` and `/stream`, replaying from a sequence number so a reconnect loses nothing
- `[x]` 8.4 Console UI, six screens:
  - `[x]` 8.4.1 Run dashboard + live metrics strip
  - `[x]` 8.4.2 New Run (requirement, scenario presets, autonomy level, plan preview)
  - `[x]` 8.4.3 Run detail — live DAG, nodes coloured by state
  - `[x]` 8.4.4 Approval inbox — impact, blast radius, triggering policies, approve/reject-with-guidance
  - `[x]` 8.4.5 Node inspector — reads, writes, acceptance criteria, dependency reasons, event history
  - `[x]` 8.4.6 Run controls — pause, resume, safe-stop, rollback, re-plan
- `[~]` 8.5 OpenAPI at `/swagger-ui` — **not done**, deliberately. The README documents every endpoint and §6 scores no API tooling; springdoc is a dependency and a scan for no marginal evidence.
- **Deviation from D6:** the DAG is laid out natively rather than by Mermaid-from-CDN. A demo machine may be offline, and a blank diagram mid-demo is a worse outcome than a plainer one. Mermaid source is still served at `/plans/{id}/mermaid` for the written deliverables.
- **Exit criteria:** a run can be started, watched, approved, and stopped entirely from the browser — and identically via `curl`
- **Satisfies:** CR4.9, CR4.10, CR7, DL1, DL2, EC1

---

### PHASE D — Proof (the three scenarios)

#### `[ ]` Step 9 — Scenario 1: GREENFIELD ⭐ critical path
- `[ ]` 9.1 Input requirement: *"Build a URL shortener service with core APIs."*
- `[ ]` 9.2 Full SDLC graph executes: requirements → design → implement → test → document → release readiness
- `[ ]` 9.3 Generated workload:
  - `[ ]` `POST /api/v1/links` (shorten, custom alias, TTL), `GET /{code}` (302), `DELETE`, `GET` metadata
  - `[ ]` Collision-safe Base62 code generation
  - `[ ]` Flyway schema, JPA entities, validation, error handling
  - `[ ]` Unit + integration tests (MockMvc), OpenAPI, README
- `[ ]` 9.4 Shortener demo page (paste URL → short link → click through)
- `[ ]` 9.5 Capture run evidence: DAG screenshot, event log export, artifact list
- **Exit criteria:** the generated service builds, tests pass, and it actually redirects
- **Satisfies:** SC1, SP1, CR4.1, CR5, DL1, DL3, EC3, EC4

#### `[ ]` Step 10 — Scenario 2: BROWNFIELD
- `[ ]` 10.1 Input requirement: *"Add click analytics and rate limiting."*
- `[ ]` 10.2 **Codebase reasoning**: impacted modules, APIs, data flows; redirect hot-path analysis
- `[ ]` 10.3 Blast-radius → **approval gate fires** on the schema change
- `[ ]` 10.4 Implementation: async click capture, aggregate stats endpoint, rate limiter, cache-aside
- `[ ]` 10.5 A seeded **bug fix** and a **refactor** run (covers SP2 fully)
- `[ ]` 10.6 A **test + docs improvement** run (covers SP3)
- `[ ]` 10.7 Deliberately force one exit-gate failure → show retry → rollback
- **Exit criteria:** impact analysis is produced *before* code changes, and a human approved the schema change
- **Satisfies:** SC2, SC3, SP2, SP3, CR3, CR4.3, CR4.6, CR4.7, DL3, EC3

#### `[ ]` Step 11 — Scenario 3: AMBIGUOUS
- `[ ]` 11.1 Input requirement: *"Make the URL shortener more reliable and faster."*
- `[ ]` 11.2 Ambiguity detection → **ambiguity register** (what's unclear, why it matters, options)
- `[ ]` 11.3 Human clarification gate in the UI
- `[ ]` 11.4 **Assumption register** for items the human doesn't resolve
- `[ ]` 11.5 Clarification arrives mid-run → **re-plan**, downstream nodes invalidated and rebuilt
- **Exit criteria:** the graph visibly changes shape after clarification, under governance
- **Satisfies:** SP4, CR1, CR4.11, CR6, DL3, EC1, EC6, EC8

---

### PHASE E — Ship

#### `[ ]` Step 12 — Validation & risk artifacts
- `[ ]` 12.1 Risk register: risk · likelihood · impact · mitigation · residual
- `[ ]` 12.2 Failure-scenario catalogue (agent hallucination, infinite re-plan loop, sandbox escape, runaway cost, partial rollback, deadlocked join)
- `[ ]` 12.3 Guardrail catalogue mapped to failure scenarios
- `[ ]` 12.4 Trade-off log (what we chose, what we gave up, why)
- **Satisfies:** CR6, DL5, EC5, EC6

#### `[ ]` Step 13 — Documentation set
- `[ ]` 13.1 `README.md` + setup instructions, clean-clone verified → **DL4**
- `[ ]` 13.2 `ARCHITECTURE.md`: components, orchestration model, control flow, key decisions → **DL2**
- `[ ]` 13.3 `SCENARIOS.md`: three walkthroughs with decomposition, orchestration, validation, evidence → **DL3**
- `[ ]` 13.4 `TESTING.md`: strategy, pyramid, coverage, limitations, trade-offs → **DL5**
- **Satisfies:** DL2, DL3, DL4, DL5, EC6

#### `[ ]` Step 14 — Final engineering summary & dry run
- `[ ]` 14.1 `SUMMARY.md`: plan & rationale · artifacts inventory · risks/trade-offs/validation · assumptions · limitations
- `[ ]` 14.2 Clean-clone end-to-end dry run on a fresh directory
- `[ ]` 14.3 Demo script (exact click path, ~10 minutes)
- **Satisfies:** CR8, DL1, EC6, EC8

---

## 4. Traceability matrix — every requirement has a home

| Requirement | Covered by |
|---|---|
| SC1 | Step 9 |
| SC2, SC3 | Step 10 |
| SC4 | Whole plan (time budget §5) |
| SC5 | Steps 0, 1, 12, 14 |
| SP1 | Step 9 |
| SP2 | Step 10 (10.4, 10.5) |
| SP3 | Step 10.6 |
| SP4 | Step 11 |
| CR1 | Steps 0, 7.2, 11.2 |
| CR2 | Steps 1.5, 7.2 (Planner) |
| CR3 | Steps 7.2 (CodebaseAnalyst), 10.2 |
| CR4.0 | Steps 1.2, 1.3, 4.3 |
| CR4.1 | Steps 7.2, 9.2 |
| CR4.2 | Steps 1.1, 4.2 |
| CR4.3 | Steps 1.4, 4.5, 10.7 |
| CR4.4 | Steps 1.5, 4.3, 4.4 |
| CR4.5 | Steps 5.6, 8.4.5 |
| CR4.6 | Steps 5.4, 8.4.4, 10.3 |
| CR4.7 | Step 6 (all), 10.7 |
| CR4.8 | Steps 5.1, 5.2, 7.5 |
| CR4.9 | Steps 4.6, 5.5, 8.3 |
| CR4.10 | Step 8.1 |
| CR4.11 | Steps 1.6, 6.6, 11.5 |
| CR5 | Steps 7, 9.3 |
| CR6 | Steps 6, 12 |
| CR7 | Steps 5.3, 5.4, 8.4 |
| CR8 | Step 14.1 |
| DL1 | Steps 3, 9, 14.2 |
| DL2 | Steps 2, 13.2 |
| DL3 | Steps 9, 10, 11, 13.3 |
| DL4 | Step 13.1 |
| DL5 | Steps 12, 13.4 |
| EC1–EC8 | Emergent; explicitly defended in Step 14.1 |

**No requirement is unassigned.** If we add work that maps to nothing here, it's scope creep.

---

## 5. Time budget (aggressive — 3 days)

| Slot | Steps |
|---|---|
| Day 1 AM | 1, 2 (design), 3 (bootstrap) |
| Day 1 PM | 4 (graph engine) |
| Day 2 AM | 5 (governance) |
| Day 2 PM | 6 (reliability), 7 (agents + tools) |
| Day 3 AM | 8 (metrics, API, UI) |
| Day 3 PM | 9, 10, 11 (three scenarios) |
| Day 3 EOD | 12, 13, 14 (docs, summary, dry run) |

**Critical path:** 1 → 4 → 5 → 7 → 9.

**If we run out of time, thin in this order** (and *say so* in limitations):
1. Step 8.1 metrics → compute 3 of 5 instead of 5
2. Step 6.2 fallback strategies → one strategy instead of several
3. Step 10.5/10.6 → narrate instead of executing
4. Step 8.4.5/8.4.6 → fold into the DAG screen

**Never thin:** Steps 10 and 11. They are half the rubric (DL3, EC1, EC3).

---

## 6. Anti-deviation guardrails

**Out of scope — we are explicitly NOT building:**
- Authentication / multi-tenancy for the orchestrator
- Distributed or multi-node execution (single JVM, documented as a limitation)
- Kubernetes / cloud deployment
- A production-scale URL shortener (no sharding, geo-replication, or CDN)
- A polished UI — §6 scores no UI aesthetics
- Model fine-tuning or custom inference

**Rules of engagement:**
1. Every new idea goes to the Parking Lot below, not into the build.
2. A step's exit criteria must be met before starting the next step on the critical path.
3. Phase C does not begin until the Step 4 and Step 5 test suites are green.
4. Anything we cut gets written into `SUMMARY.md` limitations — never silently dropped.

**Known honest limitation to declare up front:** with the deterministic
`AgentRuntime`, generated code comes from a blueprint library rather than true
synthesis. The orchestration, gating, and governance are fully real; only the
*authoring* is templated. The `ClaudeRuntime` closes that gap. This is stated in
`SUMMARY.md` rather than left for a reviewer to discover.

### Parking lot
- (empty)

---

## 7. Open questions
- `[x]` Workload spec confirmed (link CRUD + Base62 collision-safe codes + async click analytics + rate limiting + cache-aside)
- `[x]` Persistence: **H2 file-mode + Flyway**. Swappable to Postgres via one property + one dependency; single-command startup beats the appearance of production infra.
