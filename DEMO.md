# Demo script — Agentic SDLC Orchestrator

**What you are demonstrating:** the orchestrator is the product. The URL shortener is the workload it
plans, generates, governs and can undo. Everything below is driven from a browser; the `curl`
equivalents are in [`orchestrator/README.md`](orchestrator/README.md) if you are asked to prove it is
API-first.

**Total time:** 12 minutes for the full script, 4 minutes for the short version (Acts 1–3).

---

## Before you start — 5 minutes, the day before

### 1. Do not run anything as root

```bash
whoami        # must NOT print "root"
```

If you have been running as root, fix the damage once:

```bash
sudo rm -rf /home/inpixon/learning/orchestrator/target
sudo chown -R inpixon:inpixon /home/inpixon/learning
```

Running the build as root leaves a root-owned `target/`, and the next build fails with a
`Permission denied` on `application.properties` that looks like a code problem and is not.

### 2. Warm the build

```bash
cd ~/learning/orchestrator
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw test          # 215 tests, ~2 minutes. Do this the day before, not live.
```

### 3. Start clean

```bash
rm -rf data workspace
```

A demo that opens on an empty dashboard tells a much clearer story than one opening on nine runs
from yesterday.

### 4. Two terminals, one browser tab

| Where | What |
|---|---|
| Terminal 1 | the server — leave it running, never type in it |
| Terminal 2 | only for Act 5 (`git log`, `mvn test`) |
| Browser | `http://localhost:8080` — the console. This is what the audience watches |

---

## Act 1 — Start the server (30 seconds)

**Terminal 1:**

```bash
cd ~/learning/orchestrator
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw spring-boot:run
```

Wait for `Started OrchestratorApplication`. Open **http://localhost:8080**.

> **Say this:** "This is an orchestrator for agentic software work. It takes a requirement in
> English, breaks it into a dependency graph, runs that graph with real parallelism, and stops for a
> human wherever policy says it must. The top-right dot is a live event stream — everything you see
> updates by itself."

Point at the metrics strip. Everything reads **no data**.

> **Say this:** "Note it says 'no data', not 0%. A success rate with nothing to divide is not zero —
> a dashboard that shows a red 0% for a system that has done nothing wrong trains people to ignore
> it."

---

## Act 2 — Decompose a requirement (1 minute)

**New Run** → click the **Greenfield** chip → **Preview plan only**.

> **Say this:** "Nothing has executed. This is just the decomposition: 13 tasks, 17 dependencies,
> 6 levels, and it has already worked out which tasks need a human and which are high risk — before
> running anything."

Now click **Start run**.

The console jumps to the run view. You get the live DAG immediately.

> **Say this:** "Columns are dependency levels. Everything in one column can run in parallel. Watch
> level 3 — two design tasks go blue at the same time. That is real concurrency, not a progress bar."

---

## Act 3 — The human checkpoint ⭐ (2 minutes)

**This is the centrepiece. Do not rush it.**

Within a few seconds the run parks. An amber panel appears: **⏸ Waiting on you**.

> **Say this:** "It stopped on its own. `SCHEMA_LINK_CREATION` — a database migration. Look at *why*
> it stopped, because three separate rules converged here."

Read the three reasons aloud from the panel:

1. Autonomy L2 requires approval for HIGH blast radius
2. **CHG-02** — schema migrations require approval; a migration is hard to reverse once data exists
3. **CHG-03** — hot-path change, flagged for review (a `WARN`, not a block)

> **Say this:** "The strictest outcome wins. A `WARN` never downgrades an `APPROVE`. And notice the
> run is not blocked — the amber node is waiting, but the other branch is still executing. Waiting is
> a *state* here, not a blocked thread."

Point at the DAG: one amber node, other nodes still moving.

Click **Approve**. The run continues immediately.

> **Say this:** "That decision is now on the permanent record — who, what, and why."

Scroll down to **Decision lineage** and point at `HUMAN:sravan · SCHEMA_LINK_CREATION · APPROVED`.

Two more approvals follow — `IMPL_REDIRECT` (hot path) and `RELEASE_READINESS`. Approve them. The
run goes **COMPLETED**, 13/13.

---

## Act 4 — Rejection, and why failure is local (2 minutes)

Go to **New Run** → **Greenfield** chip → **Start run**. When it parks:

Type into the guidance box: `Use a separate table for analytics`

Click **Reject**.

> **Say this:** "Watch what happens to the graph."

The DAG shows one **red** node and eight **grey** ones — but four remain **green**.

> **Say this:** "One node failed. Its eight transitive dependents are blocked — not failed, blocked;
> they never ran, and calling that a failure would turn one refused decision into an apparent
> collapse. The four that had already finished were allowed to finish. Failure is local."

Open the Dashboard.

> **Say this:** "The metrics know the difference. Node success rate counts *attempted* nodes.
> Blocked nodes are reported separately."

Click any grey node → the inspector opens.

> **Say this:** "Every node is inspectable — what it reads, what it writes, its acceptance criteria,
> why each dependency exists, and its full event history. The dependency reasons are recorded by the
> planner. Sequencing you cannot explain is sequencing you cannot defend in review."

---

## Act 5 — It writes real code ⭐ (5 minutes)

**Stop Terminal 1 with `Ctrl-C`, then restart in agent mode:**

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=\
"--orchestrator.executor=agent --orchestrator.node-timeout-ms=900000"
```

> **Say this:** "Same orchestrator, one property changed. Until now every node was simulated. Now
> each node writes real files, runs `mvn compile`, runs `mvn test`, and commits with git. The
> scheduler, the gates, the governance — all identical. Only the executor changed."

Refresh the browser, start a **Greenfield** run, approve the three checkpoints as they arrive.

**This takes several minutes.** Use the wait:

> **Say this while it runs:** "The exit gate is the load-bearing part. A node does not go green
> because the agent said it succeeded — it goes green because the gate found the evidence the task
> declared it would produce, plus a zero exit code from the compiler and zero failures from the test
> run. A testing node that ran zero tests *fails*, because a suite that ran nothing proves nothing."

When it completes, **Terminal 2:**

```bash
cd ~/learning/orchestrator/workspace/<runId>          # runId is the console's page heading
find . -path ./.git -prune -o -type f -print | sort   # ~23 files
git log --oneline                                     # one commit per node
```

> **Say this:** "One commit per node, in dependency order. That is what makes rollback a precise
> `git revert` of a specific node rather than a guess about which files belonged to it."

Now the moment that matters:

```bash
mvn test
```

> **Say this:** "Don't take the orchestrator's word for it. That's an independent build — 20 tests,
> zero failures."

If you have time, run it:

```bash
mvn spring-boot:run    # binds :8081; the orchestrator keeps :8080
```

```bash
curl -s localhost:8081/api/v1/links -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/a/very/long/link"}'

curl -si localhost:8081/<code> | head -3      # 302 to the original
```

---

## Act 6 — Durability (1 minute)

Start a run, let it park on an approval. **`Ctrl-C` the server.**

> **Say this:** "That was not a graceful shutdown."

Start it again. The startup log prints:

```
StartupRecovery : Recovered N plan(s) and N run(s); 1 still live
```

Refresh the browser. Same run, same node states, same approval still waiting. Approve it — it
carries on from exactly where it stopped.

> **Say this:** "State is written through on every transition, inside the same critical section that
> made the change, so what is on disk is always a state the run genuinely occupied. A run waiting on
> a human might wait a week. It cannot live in memory."

---

## If asked

**"Is the code actually generated by an LLM?"**
> No, and I have not claimed it is. The agent runtime emits pre-written blueprints. What is real is
> everything around it — when each piece is written, whether it compiles, whether its tests pass, and
> the gate refusing it if not. `AgentRuntime` is the single class an LLM-backed runtime replaces;
> nothing else in the system changes.

**"Does the UI do the orchestration?"**
> No. It is a pure REST client — every button is one API call. Everything in the browser can be done
> with `curl`, which is what keeps the system testable headlessly. See the README.

**"What about parallelism with real tools?"**
> The scheduler is genuinely parallel. Tool work serialises per run, because all nodes share one
> working tree and two concurrent Maven builds corrupt each other's `target/`. The correct fix is a
> git worktree per branch, merged at the join. It is documented, not hidden.

**"How do you stop an agent doing damage?"**
> Three layers. Policy denies outright — no autonomy level can write credential material. The
> sandbox is a path jail with symlink real-path checks, an executable allowlist, and no shell.
> Content is screened *before* the write, because a secret written and then deleted has still been on
> disk. Show the **Policies** tab.

**"What happens if an agent lies about succeeding?"**
> It fails. Demonstrate it:
> ```bash
> ./mvnw spring-boot:run -Dspring-boot.run.arguments=\
> "--orchestrator.simulation.omit-evidence-tasks=IMPL_LINK_CREATION"
> ```
> The executor reports success and produces nothing; the exit gate refuses it.

---

## Recovery — if something goes wrong live

| Symptom | Fix |
|---|---|
| Port 8080 in use | `lsof -ti:8080 \| xargs -r kill`, or add `--server.port=8099` |
| Build fails on `Permission denied` | `sudo rm -rf target` — a root-owned build directory |
| Dashboard shows "disconnected" | The server is down. Restart it; the browser reconnects on its own |
| Run stuck, nothing happening | Check the **Approvals** tab — it is almost certainly waiting on you |
| Agent-mode node times out | You forgot `--orchestrator.node-timeout-ms=900000` |
| Want a clean slate mid-demo | `rm -rf data workspace` with the server stopped |

**The one thing to remember:** if the run looks stuck, it is waiting for a human. That is the
feature, not a bug — and saying so out loud is the strongest moment in the demo.
