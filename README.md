# Agentic SDLC Orchestrator

**The orchestrator is the product.** The URL shortener is the workload it plans, generates, tests and
governs — proof that the engine does real work, not the deliverable itself.

Give it a requirement in English. It decomposes that into a dependency graph, runs the graph with
real parallelism, stops for a human where policy says it must, writes actual Java files, compiles
them, runs their tests, commits each node separately, and refuses to mark anything green that cannot
show evidence.

| Increment | Delivers |
|---|---|
| 1 | **Requirement decomposition** — requirement in, task graph with dependencies and sequencing out (CR2, CR4.2) |
| 2 | **Execution engine** — asynchronous run of the graph with real parallelism, join barriers, failure propagation, an append-only event log, safe-stop (CR4.0, CR4.4, CR4.9) |
| 3a | **Governance** — entry/exit gates, non-blocking human approvals, policy engine, autonomy levels L0–L3, decision log (CR4.3, CR4.5, CR4.6, CR4.8, CR7) |
| 3b | **Persistence** — H2 + Flyway, write-through on every transition, startup recovery; a run parked on a human survives a restart |
| 4a | **Reliability** — bounded retry with backoff, node timeouts, degraded fallback, compensating rollback, pause/resume (CR4.7) |
| 4b | **Dynamic re-planning** — content fingerprints, plan diffing, invalidation cascade, oscillation and churn bounds, governed plan admission (CR4.10) |
| 5 | **Agents + sandboxed tools** — path-jailed filesystem, allowlisted process execution, real `mvn` and `git`, content screening, evidence-backed exit gates, git-revert compensation |
| 6 | **Observability** — reliability metrics, SSE live feed, and a console UI that drives the whole system from a browser (CR4.9, CR4.10, CR7) |

Still to come: the three scenario write-ups and the engineering summary. See
[`../PROJECT-PLAN.md`](../PROJECT-PLAN.md).

## Fastest path

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw spring-boot:run
```

Open **http://localhost:8080** — start a run, watch the DAG, and approve from the browser. For a
demo, follow [`../DEMO.md`](../DEMO.md). For the `curl` equivalents, keep reading.

---

## Prerequisites

```bash
sudo apt update && sudo apt install -y openjdk-21-jdk git
```

Nothing else — Maven comes from the wrapper. `git` is needed only in agent mode, where the
orchestrator commits each node's work.

> **Never run `./mvnw` under `sudo`.** It leaves a root-owned `target/`, and every later build fails
> with something that looks unrelated:
>
> ```
> [ERROR] filtering .../src/main/resources/application.properties to
>         .../target/classes/application.properties failed with
>         FileNotFoundException: ... (Permission denied)
> ```
>
> Recover with `sudo rm -rf target`, then build normally as yourself.

## Build and test

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw test          # 186 tests
```

---

## Two modes

The orchestrator ships with two node executors, chosen by one property. Everything else — the
scheduler, gates, governance, retries, rollback, re-planning — is identical in both.

| Mode | Property | What a node does | Use it for |
|---|---|---|---|
| **Simulated** *(default)* | `orchestrator.executor=simulated` | Reports plausible outputs after a short delay; failures can be injected | Fast demos of scheduling, governance, failure handling. Whole run finishes in seconds |
| **Agent** | `orchestrator.executor=agent` | Writes real files, runs `mvn compile` / `mvn test`, commits with `git` | Proving the system actually builds software. Minutes, not seconds |

Start simulated to understand the machine; switch to agent to watch it produce a working service.

---

## The console

`http://localhost:8080` once the server is up. Six screens, all pure REST clients — every button is
one API call, so anything the browser can do, `curl` can do.

| Screen | What it is for |
|---|---|
| **Dashboard** | Live metrics strip (success rate, retry rate, MTTR, rollbacks, latency, approval wait) and every run |
| **New Run** | Requirement text, three scenario presets, autonomy level, and a plan preview that executes nothing |
| **Run detail** | Live dependency DAG coloured by node state, node table, streaming event log, decision lineage, and the pause / resume / stop / rollback / re-plan controls |
| **Node inspector** | Click any node: reads, writes, acceptance criteria, why each dependency exists, full event history |
| **Approvals** | The inbox — impact, blast radius, every triggering policy, and approve / reject with guidance |
| **Policies** | The guardrail catalogue with the rationale for each rule |

The DAG is laid out natively rather than by a diagramming CDN, so it renders on a machine with no
network. Live updates arrive over SSE (`/api/v1/runs/{id}/stream`), which replays from a sequence
number on reconnect — so a dropped connection loses nothing.

---

## Testing it end to end

Everything below is copy-pasteable with no placeholders to fill in. Use **two terminals**: one for
the server, one for the commands. **If you prefer clicking, use the console instead — approving from
the browser avoids copying `apr-…` ids between terminals entirely.**

### Step 0 — shell helpers (paste once, in the command terminal)

Every later step uses these, so paste this block first. It saves shuttling ids around by hand.

```bash
API=localhost:8080/api/v1

# start a run and remember its id
run()      { RUN=$(curl -s $API/runs -H 'Content-Type: application/json' \
                    -d "{\"requirement\":\"$1\"}" | jq -r .id); echo "RUN=$RUN"; }

status()   { curl -s $API/runs/$RUN | jq '{status, counts}'; }
nodes()    { curl -s $API/runs/$RUN | jq -r '.nodes[] | "\(.state)\t\(.taskId)"'; }
events()   { curl -s $API/runs/$RUN/events | jq -r '.[] | "\(.seq)\t\(.type)\t\(.taskId // "-")"'; }
decisions(){ curl -s $API/runs/$RUN/decisions \
               | jq -r '.[] | "\(.actor)\t\(.taskId // "-")\t\(.choice)\n  \(.rationale)"'; }

pending()  { curl -s "$API/approvals?runId=$RUN" \
               | jq -r '.[] | select(.status=="PENDING")
                        | "\(.id)\t\(.taskId)\t\(.blastRadius)\n  \(.reasons | join("\n  "))"'; }
approve()  { curl -s -X POST "$API/approvals/$1/approve?decidedBy=sravan" | jq -c .; }
reject()   { curl -s -X POST "$API/approvals/$1/reject?decidedBy=sravan&guidance=$2" | jq -c .; }
approveAll(){ for id in $(curl -s "$API/approvals?runId=$RUN" \
                | jq -r '.[] | select(.status=="PENDING") | .id'); do approve $id; done; }

# one screen showing where the run is and what it is waiting for
watchRun() { watch -n 5 "curl -s $API/runs/$RUN | jq -c '{status, counts}';
                         echo; curl -s '$API/approvals?runId=$RUN' \
                           | jq -r '.[] | select(.status==\"PENDING\") | \"WAITING \(.id) \(.taskId)\"'"; }
```

> `run()` reads `.id`, which is what the response actually contains. If you call `POST /runs` by hand,
> the field you want is `id` — an `id` beginning `run-`. Approval ids begin `apr-`; the two endpoints
> are not interchangeable.

---

### A. Smoke test — the whole machine in about a minute (simulated)

Start here. It proves scheduling, gates, governance and the audit trail without waiting for Maven.

**Terminal 1 — server**

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw spring-boot:run
```

Wait for `Started OrchestratorApplication`.

**Terminal 2 — drive it**

```bash
run "Build a URL shortener service with shorten and redirect APIs"
status
```

Expect `"status": "RUNNING"` and `"total": 13`. Within a couple of seconds:

```bash
status
# "status": "AWAITING_INPUT",  succeeded: 4,  awaitingApproval: 1,  pending: 8
```

**The run has parked, and that is the result, not a hang.** Something needed a human. Ask what:

```bash
pending
# apr-7404a358   SCHEMA_LINK_CREATION   HIGH
#   Autonomy L2_DELEGATED requires APPROVE for HIGH blast radius.
#   [CHG-02] Schema migrations require approval (REQUIRE_APPROVAL). ...
#   [CHG-03] Hot-path changes are flagged for review (WARN). ...
```

Approve it by pasting the `apr-…` id from the first column:

```bash
approve apr-7404a358
# {"approvalId":"apr-7404a358","applied":true,"outcome":"approved"}
```

The run resumes on its own. A greenfield shortener parks **three times** — `SCHEMA_LINK_CREATION`,
`IMPL_REDIRECT` (hot path), `RELEASE_READINESS` — so repeat `pending` / `approve` until:

```bash
status
# "status": "COMPLETED",  succeeded: 13,  failed: 0
```

Then read the evidence:

```bash
nodes        # every node and its final state
events       # the append-only log: dispatches, gate results, approvals, transitions
decisions    # who decided what, and the rationale recorded at the time
```

**Impatient variant:** `approveAll` clears everything pending in one go. Fine for a re-run, but read
`pending` at least once — being shown exactly what you are consenting to is the feature.

---

### B. Prove the human checkpoint actually stops things

Rejecting is the more interesting half, and it takes ten seconds:

```bash
run "Build a URL shortener service with shorten and redirect APIs"
sleep 3
pending                                    # note the apr- id
reject apr-XXXXXXXX "Use a separate table"
sleep 4
status
# "status": "FAILED",  succeeded: 4,  failed: 1,  blocked: 8

nodes
# SCHEMA_LINK_CREATION  FAILED     <- the one you rejected
# IMPL_LINK_CREATION    BLOCKED    <- its 8 transitive dependents
# ...
# ARCH_BASELINE         SUCCEEDED  <- the 4 that had already finished
```

Failure is local: only the rejected node's *transitive dependents* block, and everything already
running was allowed to finish. The guidance you typed is on the decision record, attributed to you:

```bash
decisions
# HUMAN:sravan   SCHEMA_LINK_CREATION   REJECTED
#   Use a separate table
```

---

### C. The full run — generating a real, compiling URL shortener

This is the one that produces software. Budget 5–15 minutes; real Maven builds are slow.

**1. Restart the server in agent mode.** Stop terminal 1 with `Ctrl-C` first. The 30-second default
node deadline is far too short for real builds, so raise it.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw spring-boot:run -Dspring-boot.run.arguments=\
"--orchestrator.executor=agent --orchestrator.node-timeout-ms=900000"
```

**2. Start the run** (re-paste the Step 0 helpers if this is a new terminal):

```bash
run "Build a URL shortener service with shorten and redirect APIs"
```

**3. Watch it, and notice when it parks:**

```bash
watchRun          # Ctrl-C to leave the watch; the run is unaffected
```

Every `WAITING apr-… TASK` line is a decision owed by you. In another terminal, or after leaving the
watch:

```bash
pending
approve apr-XXXXXXXX
```

Three times, as in the smoke test. Between them, nodes are genuinely writing files, running
`mvn compile`, running `mvn test`, and committing.

**4. When `status` is `COMPLETED`, look at what it built:**

```bash
cd workspace/$RUN
find . -path ./.git -prune -o -type f -print | sort      # ~22 files
git log --oneline                                        # one commit per node, dependency order
```

Expected tree:

```
pom.xml  .gitignore  README.md
src/main/java/com/example/urlshortener/{UrlShortenerApplication,Link,CodeGenerator,
    LinkRepository,LinkService,LinkController,RedirectController}.java
src/main/resources/{application.properties,db/migration/V1__links.sql}
src/test/java/com/example/urlshortener/{CodeGeneratorTest,LinkServiceValidationTest,
    LinkUsabilityTest,LinkApiIntegrationTest}.java
docs/*.md
```

**5. Verify independently.** Do not take the orchestrator's word for it — build it yourself:

```bash
mvn test
# Tests run: 19, Failures: 0, Errors: 0 -- BUILD SUCCESS
```

**6. Run the generated service.** Stop the orchestrator first, or the port collides:

```bash
mvn spring-boot:run

curl -s localhost:8080/api/v1/links -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/a/very/long/link"}'
# -> 201 {"code":"aB3xY7q","targetUrl":"https://example.com/...","createdAt":"...","active":true}

curl -si localhost:8080/aB3xY7q | head -3     # 302, Location: the original URL
```

`POST /api/v1/links` also accepts an optional `alias` (a custom code, `409` if taken) and
`expiresAt`. `GET /api/v1/links/{code}` inspects a link, `DELETE` deactivates it.

**Commits are the rollback mechanism.** Because each node commits separately, undoing one node is a
precise `git revert <sha>` rather than a guess about which files belonged to it.

---

### What "tested" should mean

If all of these hold, the system does what it claims:

| # | Claim | How you checked it |
|---|---|---|
| 1 | 186 unit/integration tests pass | `./mvnw test` |
| 2 | A requirement becomes a dependency graph | `status` shows 13 nodes with stages and blast radius |
| 3 | Independent nodes run in parallel | `events` shows several `NODE_STARTED` before any matching `NODE_SUCCEEDED` |
| 4 | Policy stops high-impact work | the run reaches `AWAITING_INPUT` on its own |
| 5 | Waiting costs no thread | other branches keep succeeding while one node waits |
| 6 | A human decision is recorded, not just applied | `decisions` names who, what and why |
| 7 | Rejection propagates correctly | scenario **B**: node `FAILED`, dependents `BLOCKED`, rest completed |
| 8 | It writes real, compiling code | `mvn test` in `workspace/$RUN` — 19 tests, BUILD SUCCESS |
| 9 | The generated service actually works | `curl` the shorten and redirect endpoints |
| 10 | Work is attributable node by node | `git log --oneline` — one commit per node |
| 11 | State survives a restart | scenario **D** below |
| 12 | Failures retry, degrade, roll back | *Exercising the hard parts*, below |
| 13 | Metrics distinguish "no data" from zero | `curl -s localhost:8080/api/v1/metrics` on a fresh start — rates are absent, not `0` |
| 14 | The whole system is drivable from a browser | Run the demo in [`../DEMO.md`](../DEMO.md) without touching a terminal after startup |

---

### D. Restart recovery

Proves that a run parked on a human is durable, not held in memory:

```bash
run "Build a URL shortener service with shorten and redirect APIs"
sleep 3
status                     # AWAITING_INPUT
```

`Ctrl-C` the server. Start it again with the same command — the startup log tells you what came back:

```
StartupRecovery : Recovered 3 plan(s) and 3 run(s); 1 still live
```

Then, without starting anything new (re-paste the Step 0 helpers and set `RUN=run-…` if it is a new
terminal):

```bash
status                     # same run, same counts, still AWAITING_INPUT
pending                    # the same apr- id is still waiting
approve apr-XXXXXXXX
status                     # succeeded climbs — it carried on from exactly where it stopped
```

Plans, runs, events, approvals and decisions all reload. To wipe everything and start clean:

```bash
rm -rf data workspace
```

---

## API

**Planning**

| Method | Path | Returns |
|---|---|---|
| `POST` | `/api/v1/decompose` | Task graph + summary |
| `GET` | `/api/v1/plans` | All plans |
| `GET` | `/api/v1/plans/{id}` | One plan |
| `GET` | `/api/v1/plans/{id}/mermaid` | Mermaid diagram source |
| `GET` | `/api/v1/plans/{id}/schedule` | Sequencing as plain text |

**Execution**

| Method | Path | Returns |
|---|---|---|
| `POST` | `/api/v1/runs` | `202` with a run id — the run is *scheduled*, not finished |
| `GET` | `/api/v1/runs` | All runs |
| `GET` | `/api/v1/runs/{id}` | Run status + per-node state |
| `GET` | `/api/v1/runs/{id}/events?since=N` | Append-only event log, pageable by sequence |
| `POST` | `/api/v1/runs/{id}/stop?reason=...` | Safe-stop |
| `POST` | `/api/v1/runs/{id}/pause?reason=...` | Hold; in-flight nodes finish |
| `POST` | `/api/v1/runs/{id}/resume` | Continue where it left off |
| `POST` | `/api/v1/runs/{id}/replan?guidance=...` | Rebuild the graph from an amended requirement |
| `POST` | `/api/v1/runs/{id}/rollback?reason=...` | Undo every succeeded node, most recent first |
| `GET` | `/api/v1/runs/{id}/decisions` | What was decided, by whom, and why |

**Oversight**

| Method | Path | Returns |
|---|---|---|
| `GET` | `/api/v1/approvals?status=&runId=` | The inbox — pending decisions with impact and triggering policies |
| `POST` | `/api/v1/approvals/{id}/approve?decidedBy=&note=` | Grant; the run resumes |
| `POST` | `/api/v1/approvals/{id}/reject?decidedBy=&guidance=` | Refuse; the node fails and dependents block |
| `GET` | `/api/v1/policies` | The guardrail catalogue |

**Observability**

| Method | Path | Returns |
|---|---|---|
| `GET` | `/api/v1/metrics` | Fleet-wide reliability metrics |
| `GET` | `/api/v1/metrics/runs/{id}` | The same, scoped to one run |
| `GET` | `/api/v1/runs/{id}/stream?since=N` | SSE feed — replays from `N`, then streams live |
| `GET` | `/api/v1/stream` | SSE feed for every run |

`POST /api/v1/runs` takes either `requirement` (decompose then run) or `planId` (run a plan you have
already inspected), plus an optional `autonomyLevel` and `scenarioHint`:

```json
{"requirement": "Add click analytics and rate limiting", "autonomyLevel": "L1_ASSISTED"}
```

Autonomy defaults to `L2_DELEGATED` — an unspecified level is an operator who has not thought about
it, and the safe reading of that is "still ask me about high-impact work".

---

## Exercising the hard parts

Each of these is a switch that makes one mechanism visible rather than merely asserted in a test.
All are simulated-mode flags.

**A node fails and the cascade blocks only its dependents**

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=\
"--orchestrator.simulation.fail-tasks=IMPL_RATE_LIMITING --orchestrator.simulation.node-delay-ms=250"

curl -s localhost:8080/api/v1/runs -H 'Content-Type: application/json' \
  -d '{"requirement":"Add click analytics and rate limiting to the existing service"}'
```

Independent branches still complete; the run quiesces to `FAILED` with every block reason recorded.

**The exit gate refuses a false claim of success**

```bash
--orchestrator.simulation.omit-evidence-tasks=IMPL_LINK_CREATION
```

The executor reports success and produces nothing. The node fails anyway. This is the single rule
that separates an orchestrator from a progress bar.

**Retry, then a degraded fallback**

```bash
--orchestrator.simulation.fail-tasks=TEST_REDIRECT --orchestrator.retry.max-attempts=3
```

Watch for `RETRY_SCHEDULED`, then `RETRIES_EXHAUSTED`, `FALLBACK_ATTEMPTED` and `NODE_DEGRADED` in
`events`. A degraded success
is *flagged*, never passed off as a normal one — `counts.degraded` is reported separately.

**A node that hangs is killed at its deadline**

```bash
--orchestrator.simulation.slow-tasks=IMPL_CACHING --orchestrator.node-timeout-ms=3000
```

**Rollback, including a compensation that itself fails**

```bash
--orchestrator.simulation.compensation-failures=SCHEMA_LINK_CREATION
```

```bash
curl -s -X POST "localhost:8080/api/v1/runs/$RUN/rollback?reason=demo"
```

A failed compensation **halts the rollback** and leaves that node `BLOCKED`. Compounding a
known-partial state with further automatic changes is exactly what should not happen.

**Re-planning mid-run**

```bash
curl -s -X POST "localhost:8080/api/v1/runs/$RUN/replan?guidance=Add+monitoring+and+health+checks"
```

The reply is an outcome, not a boolean: `ADMITTED`, `AWAITING_PLAN_APPROVAL`, `NO_CHANGE`,
`OSCILLATION_DETECTED`, `BOUND_REACHED` or `REJECTED`. Adding something that touches the hot path or
the schema will correctly return `AWAITING_PLAN_APPROVAL` — the *plan change itself* is governed, not
just the nodes.

**Restart recovery**

Start a run, let it park on an approval, `Ctrl-C` the process, start it again. The run, its events,
its pending approval and its decisions are all still there, and approving it resumes the work.

---

## Governance in one paragraph

An agent's claim of success is never accepted at face value: the **exit gate** checks it against the
evidence the task declared it would produce, plus the build's exit code and the test totals. Before a
node runs, the **entry gate** applies the **autonomy matrix** (how much you let agents do unattended)
and the **policy set** (what is not on the table at all). Autonomy tunes approval; policy is absolute
— a `DENY` halts an L3 run exactly as it halts an L0 one, and a task assigned to a human is never
executed by an agent at any level. A node needing approval sits in `AWAITING_APPROVAL` holding no
thread, while every other branch keeps running.

`GET /api/v1/policies` lists the guardrails: secrets denied outright, build-config and migration
changes requiring approval, human-owned tasks never automated, release gated, hot-path changes warned.

---

## The sandbox

In agent mode an agent can touch nothing outside its run's workspace.

- **Path jail.** Every path is normalised, checked for traversal, absolute paths, NUL bytes and
  overlong input — and then checked *again* against its real path, because a path can normalise
  cleanly inside the root and still resolve outside it through a symlink. For a file that does not
  exist yet, the parent's real path is checked, since a symlinked directory is enough.
- **No shell.** Arguments go straight to `execve`, so `; rm -rf .` is a nonsense argument, not a
  command substitution.
- **Executable allowlist** — `mvn`, `mvnw`, `git`, `java`. Confining an agent's files while letting it
  run arbitrary binaries confines nothing.
- **Both process streams drained on separate threads** before `waitFor`; a process that fills its pipe
  buffer would otherwise deadlock against a wait that is itself waiting on us. Output capture is
  bounded, draining continues past the cap, and a process past its deadline is forcibly killed.
- **Environment stripped** of anything matching `TOKEN`, `SECRET`, `PASSWORD`, `API_KEY`, `_KEY`,
  `CREDENTIAL`.
- **Content screened before the write**, not after. A secret written and then deleted has still been
  on disk — and if the node committed, is still in history. Patterns match credential *shapes* (PEM
  blocks, `AKIA…`, `ghp_…`, `xox…`, `sk-ant-…`, long assigned literals), not the words "password" or
  "token": a check that fires on every config class gets switched off, which is worse than not having
  one.

---

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `orchestrator.executor` | `simulated` | `simulated` or `agent` |
| `orchestrator.max-concurrency` | `4` | Max nodes dispatched at once |
| `orchestrator.node-timeout-ms` | `30000` | Deadline per node attempt. **Raise to ~900000 in agent mode** |
| `orchestrator.workspace.root` | `./workspace` | Parent of the per-run sandbox |
| `orchestrator.tools.maven-timeout-ms` | `600000` | Deadline for one Maven invocation |
| `orchestrator.tools.max-file-bytes` | `1048576` | Largest file an agent may write |
| `orchestrator.retry.max-attempts` | `3` | Attempts before the fallback |
| `orchestrator.retry.initial-backoff-ms` | `200` | First backoff; doubles per attempt, capped at 10s |
| `orchestrator.fallback.enabled` | `true` | Whether to make one degraded attempt after retries |
| `orchestrator.replan.max-per-run` | `3` | Re-plans allowed before the run must be decided by a human |
| `orchestrator.replan.churn-threshold` | `4` | Structural-hash repeats treated as oscillation |
| `orchestrator.simulation.node-delay-ms` | `40` | Simulated work duration |
| `orchestrator.simulation.fail-tasks` | *(empty)* | Task ids to fail, for demonstrating failure handling |
| `orchestrator.simulation.permanent-fail-tasks` | *(empty)* | Task ids that fail non-retryably |
| `orchestrator.simulation.omit-evidence-tasks` | *(empty)* | Task ids that report success while producing nothing |
| `orchestrator.simulation.slow-tasks` | *(empty)* | Task ids that hang, for demonstrating the node timeout |
| `orchestrator.simulation.slow-task-delay-ms` | `60000` | How long a slow task hangs |
| `orchestrator.simulation.compensation-failures` | *(empty)* | Task ids whose *undo* fails |

Pass any of them as `--property=value` after `-Dspring-boot.run.arguments=`, space-separated.

---

## How execution works

- **Event-driven, not polled.** The scheduler ticks when something changes — a run starts, a node
  finishes, a stop arrives. An idle run costs nothing.
- **Ready-set dispatch, not level-stepping.** A node runs the moment *its own* predecessors succeed,
  rather than waiting for a whole level. Levels are how the plan is *displayed*; they are not how it
  is executed.
- **One monitor per run.** All state transitions happen under the run's lock; executors run outside
  it, so node work never blocks scheduling.
- **Waiting is a state, never a blocked thread.** `AWAITING_APPROVAL` and `RETRYING` hold nothing.
- **Failure is local.** A failed node blocks only its transitive dependents.
- **Safe-stop cancels pending work but does not interrupt running nodes.** They finish first;
  interrupting mid-flight work would strand partial side effects.

### State

State lives in `./data/orchestrator.mv.db` (H2, file mode), written through on every transition
inside the same critical section that made the change — so what is on disk is always a state the run
genuinely occupied. On startup, plans, runs, events, approvals and decisions are reloaded and any
non-terminal run resumes where it left off.

Generated code lives in `./workspace/<runId>/`, one git repository per run.

To start completely clean: `rm -rf data workspace`.

**Nodes that were mid-flight when the process died are failed, not re-queued.** We cannot tell "the
node finished and we crashed before recording it" from "the node never ran", and re-running work that
may have had side effects is the more dangerous guess.

---

## How decomposition works

Capability-driven, not template-driven:

1. **Detect** capabilities by word-boundary keyword match. `"URL shortener"` is deliberately *not* a
   capability — it names the product, and reading it as a feature request is how an ambiguous
   requirement becomes a confidently wrong plan.
2. **Classify** the scenario. A requirement with no extractable capability is `AMBIGUOUS`, and
   planning halts at a human `CLARIFY` task rather than inventing work.
3. **Expand** each capability into `design → schema? → implement → test`.
4. **Wire** cross-capability dependencies from the catalogue's own structure (analytics needs
   redirect, redirect needs link creation).
5. **Resolve write conflicts.** Two tasks that could run concurrently and would edit the same
   component get a `CONTROL` edge that serialises them — with the reason recorded. A transitive
   reduction then drops edges another path already implies.
6. **Level** the graph into parallel batches.

The graph changes shape with the requirement:

| Requirement | Shape |
|---|---|
| Greenfield | no impact analysis; prerequisites built |
| Brownfield | impact analysis precedes design; prerequisites assumed |
| Ambiguous | two tasks, then stop |

### Try all three

```bash
curl -s localhost:8080/api/v1/decompose -H 'Content-Type: application/json' \
  -d '{"requirement":"Build a URL shortener service with shorten and redirect APIs"}' | jq .summary

curl -s localhost:8080/api/v1/decompose -H 'Content-Type: application/json' \
  -d '{"requirement":"Add click analytics and rate limiting to the existing service"}' | jq .summary

curl -s localhost:8080/api/v1/decompose -H 'Content-Type: application/json' \
  -d '{"requirement":"Make the URL shortener more reliable and faster"}' | jq .summary
```

Then view the graph — paste the output into any Mermaid renderer, or a GitHub ```mermaid fence:

```bash
curl -s localhost:8080/api/v1/plans/<planId>/mermaid
```

---

## Known limitations

Stated rather than hidden, because each is a real design boundary.

- **Tool-using work serialises per run.** All nodes share one working tree, so a per-run lock
  guards the tools. Two concurrent Maven builds corrupt each other's `target/`, and two `git`
  commands contend for `index.lock`. The *scheduler* is still genuinely parallel — ready sets, joins,
  governance — but the tools queue. The correct fix is a git worktree per parallel branch, merged at
  the join.
- **The agent runtime emits pre-written code; it does not synthesise it.** What is real is everything
  around it: when each piece is written, whether it compiles, whether its tests pass, and the gate
  refusing it if not. `AgentRuntime` is the single class an LLM-backed runtime would replace, and
  nothing else in the system would change.
- **`workspace/` accumulates.** One repository per run, deleted by nobody.

## Design notes

- **Every dependency carries a `reason`.** Sequencing you cannot explain is sequencing you cannot
  defend in review.
- **`reads` / `writes` are declared per task.** They give content fingerprints for re-planning, a
  least-privilege context boundary per agent, and the evidence the exit gate checks.
- **`planningNotes` is the audit trail for the decomposition itself** — including every ordering the
  planner imposed and why.
- **JDBC, not JPA.** Persistence stays out of the domain; the state machine has no idea it is stored.
- **A node may never declare its own success.** Rule one, and everything else follows from it.
