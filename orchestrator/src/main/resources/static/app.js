/*
 * Console for the orchestrator.
 *
 * A pure client of the REST API — there is no orchestration logic here, and there must never be.
 * Everything this page can do is something curl can do, which is what keeps the system testable
 * headlessly and stops the browser becoming a place where behaviour hides.
 *
 * No build step and no CDN: the DAG is laid out by hand rather than by a diagramming library, so a
 * demo machine with no network still renders the graph. A blank diagram during a demo is a worse
 * outcome than a plainer one.
 */
const App = (() => {

  const API = '/api/v1';
  const state = {
    view: 'dashboard',
    runId: null,
    plan: null,
    run: null,
    events: [],
    lastSeq: 0,
    sse: null,
    poll: null,
    decidedBy: 'operator'
  };

  // ─────────────────────────────────── plumbing ───────────────────────────────────

  async function api(path, options) {
    const response = await fetch(API + path, options);
    if (!response.ok) {
      let detail = response.statusText;
      try {
        const body = await response.json();
        detail = body.detail || body.message || body.error || detail;
      } catch (ignored) { /* Not every error body is JSON. */ }
      throw new Error(detail);
    }
    return response.status === 204 ? null : response.json();
  }

  const post = (path) => api(path, { method: 'POST' });

  function toast(message, kind) {
    const el = document.getElementById('toast');
    el.textContent = message;
    el.className = 'toast show ' + (kind || '');
    clearTimeout(el._timer);
    el._timer = setTimeout(() => { el.className = 'toast ' + (kind || ''); }, 4200);
  }

  const esc = (s) => String(s === null || s === undefined ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

  function ms(value) {
    if (value === null || value === undefined) return '—';
    if (value < 1000) return value + 'ms';
    if (value < 60000) return (value / 1000).toFixed(1) + 's';
    return Math.floor(value / 60000) + 'm ' + Math.round((value % 60000) / 1000) + 's';
  }

  const clock = (iso) => iso ? new Date(iso).toLocaleTimeString() : '—';

  function connection(live) {
    document.getElementById('connDot').className = 'dot ' + (live ? 'live' : 'down');
    document.getElementById('connText').textContent = live ? 'live' : 'disconnected';
  }

  // ─────────────────────────────────── routing ───────────────────────────────────

  function show(view) {
    state.view = view;
    document.querySelectorAll('.view').forEach(el => el.classList.remove('active'));
    document.getElementById('view-' + view).classList.add('active');
    document.querySelectorAll('.tab').forEach(tab =>
      tab.classList.toggle('active', tab.dataset.view === view));

    if (view !== 'run') { closeStream(); state.runId = null; }
    if (view === 'dashboard') refreshDashboard();
    if (view === 'newrun') refreshBaselines();
    if (view === 'approvals') refreshApprovals();
    if (view === 'policies') refreshPolicies();
  }

  // ─────────────────────────────────── dashboard ───────────────────────────────────

  async function refreshDashboard() {
    try {
      const [runs, metrics] = await Promise.all([api('/runs'), api('/metrics')]);
      connection(true);
      renderMetrics(metrics);
      renderRuns(runs);
    } catch (error) {
      connection(false);
    }
    refreshBadge();
  }

  function metric(label, value, note, tone) {
    const none = value === null || value === undefined;
    return `<div class="metric">
      <div class="label">${esc(label)}</div>
      <div class="value ${none ? 'none' : (tone || '')}">${none ? 'no data' : esc(value)}</div>
      <div class="note">${esc(note || '')}</div>
    </div>`;
  }

  function renderMetrics(m) {
    // A success rate is only meaningful once something has finished. Showing "no data" rather than
    // a red 0% is the difference between an honest dashboard and an alarming one.
    const runTone = m.runs.successRatePct === null ? ''
      : m.runs.successRatePct >= 80 ? 'good' : m.runs.successRatePct >= 50 ? 'warn' : 'bad';
    const nodeTone = m.nodes.successRatePct === null ? ''
      : m.nodes.successRatePct >= 90 ? 'good' : m.nodes.successRatePct >= 70 ? 'warn' : 'bad';

    document.getElementById('metricsStrip').innerHTML = [
      metric('Run success rate',
        m.runs.successRatePct === null ? null : m.runs.successRatePct + '%',
        `${m.runs.completed} of ${m.runs.completed + m.runs.failed} finished · ${m.runs.active} active`,
        runTone),
      metric('Node success rate',
        m.nodes.successRatePct === null ? null : m.nodes.successRatePct + '%',
        `${m.nodes.succeeded}/${m.nodes.attempted} attempted · ${m.nodes.blocked} never reached`,
        nodeTone),
      metric('Retry rate',
        m.reliability.retryRatePct === null ? null : m.reliability.retryRatePct + '%',
        `${m.reliability.retriesScheduled} retries · ${m.reliability.fallbacksAttempted} fallbacks`,
        m.reliability.retriesScheduled ? 'warn' : ''),
      metric('MTTR', ms(m.reliability.meanTimeToRecoveryMs),
        `${m.reliability.recoveredFailures} recovered · ${m.reliability.unrecoveredFailures} not`),
      metric('Rollbacks',
        m.reliability.nodesRolledBack,
        `${m.reliability.compensationFailures} compensation failures`,
        m.reliability.compensationFailures ? 'bad' : ''),
      metric('Run latency', ms(m.latency.meanRunMs),
        `p95 ${ms(m.latency.p95RunMs)} · max ${ms(m.latency.maxRunMs)}`),
      metric('Approvals', m.governance.approvalsRequested,
        `${m.governance.approved} granted · ${m.governance.rejected} rejected`),
      metric('Human wait', ms(m.governance.meanWaitMs),
        `max ${ms(m.governance.maxWaitMs)} · excluded from run latency`)
    ].join('');
  }

  function progressBar(c) {
    const seg = (n, colour) => n > 0
      ? `<span style="flex:${n};background:${colour}"></span>` : '';
    return `<div class="bar">
      ${seg(c.succeeded - c.degraded, 'var(--success)')}
      ${seg(c.degraded, 'var(--degraded)')}
      ${seg(c.running, 'var(--running)')}
      ${seg(c.awaitingApproval, 'var(--waiting)')}
      ${seg(c.retrying, 'var(--retrying)')}
      ${seg(c.failed, 'var(--failed)')}
      ${seg(c.blocked + c.cancelled, 'var(--blocked)')}
      ${seg(c.rolledBack, 'var(--rolled)')}
      ${seg(c.pending, 'var(--pending)')}
    </div>`;
  }

  function renderRuns(runs) {
    const body = document.querySelector('#runsTable tbody');
    if (!runs.length) {
      body.innerHTML = '<tr><td colspan="7" class="empty">No runs yet — start one from “New Run”.</td></tr>';
      return;
    }
    body.innerHTML = runs.slice().reverse().map(r => `
      <tr class="clickable" onclick="App.openRun('${r.id}')">
        <td class="mono">${esc(r.id)}</td>
        <td>${esc(r.scenarioType)}</td>
        <td>${esc(r.autonomyLevel.replace('_', ' '))}</td>
        <td><span class="status s-${esc(r.status)}">${esc(r.status)}</span></td>
        <td>${progressBar(r.counts)}
            <span class="note">${r.counts.succeeded}/${r.counts.total}</span></td>
        <td>${clock(r.startedAt)}</td>
        <td>${ms(r.durationMs)}</td>
      </tr>`).join('');
  }

  // ─────────────────────────────────── new run ───────────────────────────────────

  /**
   * Offers completed runs as baselines, newest first.
   *
   * <p>Only COMPLETED ones: seeding from a run that failed halfway copies a workspace whose state
   * nobody has vouched for, and the resulting compile errors would be blamed on the new change.
   */
  async function refreshBaselines() {
    try {
      const runs = await api('/runs');
      const select = document.getElementById('baseRun');
      const previous = select.value;

      const usable = runs.filter(r => r.status === 'COMPLETED').reverse();
      select.innerHTML = '<option value="">Start from nothing (greenfield)</option>' +
        usable.map(r => `<option value="${esc(r.id)}">${esc(r.id)} — ${esc(r.scenarioType)}, ${
          r.counts.succeeded} nodes</option>`).join('');

      // Keep the operator's selection across the periodic refresh.
      if (previous && usable.some(r => r.id === previous)) select.value = previous;
    } catch (ignored) { /* The baseline list is not worth surfacing an error for. */ }
  }

  async function startRun() {
    const requirement = document.getElementById('reqText').value.trim();
    if (!requirement) { toast('Enter a requirement first.', 'err'); return; }

    const button = document.getElementById('startBtn');
    button.disabled = true;
    try {
      const run = await api('/runs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          requirement,
          autonomyLevel: document.getElementById('autonomy').value,
          // Empty string means greenfield. Sent as null rather than "" so the server sees an
          // absent baseline rather than a blank one it would try to resolve.
          baseRunId: document.getElementById('baseRun').value || null
        })
      });
      toast('Run ' + run.id + ' started', 'ok');
      openRun(run.id);
    } catch (error) {
      toast('Could not start: ' + error.message, 'err');
    } finally {
      button.disabled = false;
    }
  }

  async function previewPlan() {
    const requirement = document.getElementById('reqText').value.trim();
    if (!requirement) { toast('Enter a requirement first.', 'err'); return; }
    try {
      const result = await api('/decompose', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requirement })
      });
      const s = result.summary;
      document.getElementById('newRunResult').innerHTML = `
        <div class="panel" style="margin-top:17px">
          <div class="panel-head"><h2>Plan preview — nothing executed</h2></div>
          <div class="kv" style="padding:15px">
            <div class="k">Scenario</div><div class="v">${esc(s.scenarioType)}</div>
            <div class="k">Tasks</div><div class="v">${s.taskCount}</div>
            <div class="k">Dependencies</div><div class="v">${s.dependencyCount}</div>
            <div class="k">Levels</div><div class="v">${s.levelCount}</div>
            <div class="k">Max parallelism</div><div class="v">${s.maxParallelism}</div>
            <div class="k">Needs a human</div>
            <div class="v">${s.humanApprovalTasks.join(', ') || 'none'}</div>
            <div class="k">High risk</div>
            <div class="v">${s.highRiskTasks.join(', ') || 'none'}</div>
            <div class="k">Ambiguities</div><div class="v">${s.ambiguityCount}</div>
          </div>
        </div>`;
    } catch (error) {
      toast('Decomposition failed: ' + error.message, 'err');
    }
  }

  // ─────────────────────────────────── run detail ───────────────────────────────────

  async function openRun(runId) {
    state.runId = runId;
    state.events = [];
    state.lastSeq = 0;
    show('run');
    document.getElementById('runTitle').textContent = runId;
    await loadRun();
    openStream(runId);
  }

  async function loadRun() {
    if (!state.runId) return;
    try {
      const run = await api('/runs/' + state.runId);
      state.run = run;
      connection(true);

      if (!state.plan || state.plan.plan.id !== run.planId) {
        state.plan = await api('/plans/' + run.planId);
      }
      renderRun(run);

      const [approvals, decisions] = await Promise.all([
        api('/approvals?runId=' + state.runId),
        api('/runs/' + state.runId + '/decisions')
      ]);
      renderInlineApprovals(approvals.filter(a => a.status === 'PENDING'));
      renderDecisions(decisions);
    } catch (error) {
      connection(false);
    }
    refreshBadge();
  }

  function renderRun(run) {
    document.getElementById('runStatus').className = 'status s-' + run.status;
    document.getElementById('runStatus').textContent = run.status;
    document.getElementById('runRequirement').textContent =
      state.plan ? '“' + state.plan.plan.requirement.rawText + '”' : '';

    const c = run.counts;
    const chip = (label, value, colour) => value > 0
      ? `<div class="count-chip"><b style="color:${colour}">${value}</b>${label}</div>` : '';
    document.getElementById('runCounts').innerHTML =
      `<div class="count-chip"><b>${c.total}</b>total</div>` +
      chip('succeeded', c.succeeded, 'var(--success)') +
      chip('degraded', c.degraded, 'var(--degraded)') +
      chip('running', c.running, 'var(--running)') +
      chip('awaiting approval', c.awaitingApproval, 'var(--waiting)') +
      chip('retrying', c.retrying, 'var(--retrying)') +
      chip('failed', c.failed, 'var(--failed)') +
      chip('blocked', c.blocked, 'var(--blocked)') +
      chip('rolled back', c.rolledBack, 'var(--rolled)') +
      chip('pending', c.pending, 'var(--pending)');

    renderDag(run);
    renderNodes(run);
  }

  /**
   * Lays the graph out in dependency levels computed here from the plan's edges.
   *
   * A node's level is one past its deepest predecessor, which is exactly the earliest point it could
   * start. That makes each column a set of genuinely parallel work rather than a cosmetic grouping.
   */
  function levelsOf(plan) {
    const tasks = plan.tasks.map(t => t.id);
    const incoming = {};
    tasks.forEach(id => { incoming[id] = []; });
    plan.dependencies.forEach(d => {
      if (incoming[d.to]) incoming[d.to].push(d.from);
    });

    const level = {};
    let settled = true;
    // Bounded rather than recursive: the planner rejects cycles, but a UI must not hang even if a
    // malformed plan ever reaches it.
    for (let pass = 0; pass < tasks.length + 1; pass++) {
      settled = true;
      tasks.forEach(id => {
        const computed = incoming[id].reduce((max, from) =>
          Math.max(max, (level[from] === undefined ? 0 : level[from]) + 1), 0);
        if (level[id] !== computed) { level[id] = computed; settled = false; }
      });
      if (settled) break;
    }

    const columns = [];
    tasks.forEach(id => {
      const depth = level[id] || 0;
      (columns[depth] = columns[depth] || []).push(id);
    });
    return columns;
  }

  function renderDag(run) {
    if (!state.plan) return;
    const plan = state.plan.plan;
    const byId = {};
    run.nodes.forEach(n => { byId[n.taskId] = n; });

    const columns = levelsOf(plan);
    document.getElementById('dag').innerHTML = columns.map((ids, index) => `
      <div class="dag-level">
        <div class="level-label">Level ${index + 1} · ${ids.length} task${ids.length > 1 ? 's' : ''}</div>
        ${ids.map(id => {
          const node = byId[id] || { state: 'PENDING', taskId: id };
          const cls = node.completedDegraded ? 'DEGRADED' : node.state;
          return `<div class="dag-node n-${esc(cls)}" onclick="App.inspect('${esc(id)}')">
            <div class="n-id">${esc(id)}</div>
            <div class="n-meta">${esc(node.state)}${node.durationMs ? ' · ' + ms(node.durationMs) : ''}</div>
          </div>`;
        }).join('')}
      </div>`).join('');

    const swatch = (colour, label) =>
      `<span><i style="background:${colour}"></i>${label}</span>`;
    document.getElementById('dagLegend').innerHTML =
      swatch('var(--pending)', 'pending') + swatch('var(--running)', 'running') +
      swatch('var(--waiting)', 'awaiting approval') + swatch('var(--success)', 'succeeded') +
      swatch('var(--failed)', 'failed') + swatch('var(--blocked)', 'blocked');
  }

  function renderNodes(run) {
    document.querySelector('#nodesTable tbody').innerHTML = run.nodes.map(n => `
      <tr class="clickable" onclick="App.inspect('${esc(n.taskId)}')">
        <td><span class="pill s-${esc(n.completedDegraded ? 'DEGRADED' : n.state)}">${esc(n.state)}</span></td>
        <td class="mono">${esc(n.taskId)}</td>
        <td>${esc(n.stage || '—')}</td>
        <td class="risk-${esc(n.blastRadius)}">${esc(n.blastRadius || '—')}</td>
        <td>${n.attempt}</td>
        <td>${n.durationMs === null ? '—' : n.durationMs}</td>
      </tr>`).join('');
  }

  function eventClass(type) {
    if (/FAILED|DENIED|EXHAUSTED|TIMED_OUT|REJECTED/.test(type)) return 'evt-fail';
    if (/AWAITING|RETRY|DEGRADED|FALLBACK|PAUSED|STOP/.test(type)) return 'evt-warn';
    if (/APPROVAL|POLICY|PLAN_|REPLAN/.test(type)) return 'evt-gov';
    if (/SUCCEEDED|COMPLETED|GRANTED/.test(type)) return 'evt-ok';
    return '';
  }

  function renderEvents() {
    const log = document.getElementById('eventLog');
    const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 60;

    log.innerHTML = state.events.map(e => `
      <div class="log-row ${eventClass(e.type)}">
        <span class="seq">${e.seq}</span>
        <span class="type">${esc(e.type)}</span>
        <span class="msg">${esc(e.taskId ? e.taskId + ' — ' : '')}${esc(e.message || '')}</span>
      </div>`).join('');

    document.getElementById('eventCount').textContent = state.events.length + ' entries';
    // Only auto-scroll if the reader was already at the bottom; yanking the view while someone is
    // reading an earlier entry is the fastest way to make a live log useless.
    if (atBottom) log.scrollTop = log.scrollHeight;
  }

  function renderDecisions(decisions) {
    const el = document.getElementById('decisionLog');
    if (!decisions.length) {
      el.innerHTML = '<p class="empty">No decisions recorded yet.</p>';
      return;
    }
    el.innerHTML = decisions.map(d => `
      <div class="decision">
        <span class="who">${esc(d.actor)}</span>
        ${d.taskId ? ' · <span class="mono">' + esc(d.taskId) + '</span>' : ''}
        · <strong>${esc(d.choice)}</strong>
        <div class="why">${esc(d.question || '')}${d.question ? ' → ' : ''}${esc(d.rationale || '')}</div>
        ${d.alternatives && d.alternatives.length
          ? '<div class="why">Not taken: ' + esc(d.alternatives.join(', ')) + '</div>' : ''}
      </div>`).join('');
  }

  // ─────────────────────────────────── approvals ───────────────────────────────────

  function approvalCard(a, compact) {
    return `<div class="approval">
      <div class="approval-head">
        <span class="task">${esc(a.taskId)}</span>
        <span class="pill risk-${esc(a.blastRadius)}">${esc(a.blastRadius)} blast radius</span>
        ${compact ? '' : `<span class="mono" style="color:var(--muted);font-size:12px">
           ${esc(a.runId)}</span>`}
        <span style="color:var(--muted);font-size:12px;margin-left:auto">
          asked ${clock(a.requestedAt)}</span>
      </div>
      <div style="font-size:13px;margin-top:5px">${esc(a.taskTitle || '')}</div>
      <ul class="reasons">${a.reasons.map(r => '<li>' + esc(r) + '</li>').join('')}</ul>
      <div class="decide">
        <button class="btn ok" onclick="App.decide('${esc(a.id)}','approve',this)">Approve</button>
        <button class="btn no" onclick="App.decide('${esc(a.id)}','reject',this)">Reject</button>
        <input type="text" id="g-${esc(a.id)}"
               placeholder="Guidance, recorded on the decision either way…">
      </div>
    </div>`;
  }

  function renderInlineApprovals(pending) {
    const el = document.getElementById('runApprovals');
    if (!pending.length) { el.innerHTML = ''; return; }
    el.innerHTML = `<div class="inline-approval">
      <div class="panel-head"><h2>⏸ Waiting on you — ${pending.length} decision${
        pending.length > 1 ? 's' : ''}</h2></div>
      ${pending.map(a => approvalCard(a, true)).join('')}
    </div>`;
  }

  async function refreshApprovals() {
    try {
      const approvals = await api('/approvals');
      const el = document.getElementById('approvalList');
      el.innerHTML = approvals.length
        ? approvals.map(a => approvalCard(a, false)).join('')
        : '<p class="empty">Nothing is waiting on you.</p>';
    } catch (error) {
      toast('Could not load approvals: ' + error.message, 'err');
    }
    refreshBadge();
  }

  async function refreshBadge() {
    try {
      const pending = await api('/approvals');
      const badge = document.getElementById('approvalBadge');
      badge.textContent = pending.length;
      badge.classList.toggle('hidden', pending.length === 0);
    } catch (ignored) { /* The badge is not worth surfacing an error for. */ }
  }

  async function decide(approvalId, verb, button) {
    const input = document.getElementById('g-' + approvalId);
    const text = input ? input.value.trim() : '';
    const param = verb === 'approve' ? 'note' : 'guidance';

    // Both buttons, so a double-click cannot approve and then reject the same node.
    const buttons = button.parentElement.querySelectorAll('button');
    buttons.forEach(b => { b.disabled = true; });
    try {
      await post(`/approvals/${approvalId}/${verb}?decidedBy=${
        encodeURIComponent(state.decidedBy)}${text ? '&' + param + '=' + encodeURIComponent(text) : ''}`);
      toast(verb === 'approve' ? 'Approved — the run continues' : 'Rejected — dependents will block',
        verb === 'approve' ? 'ok' : '');
      await Promise.all([loadRun(), refreshApprovals()]);
    } catch (error) {
      toast('Could not ' + verb + ': ' + error.message, 'err');
      buttons.forEach(b => { b.disabled = false; });
    }
  }

  // ─────────────────────────────────── run controls ───────────────────────────────────

  async function control(action) {
    if (!state.runId) return;
    if (action === 'rollback' &&
        !confirm('Undo every succeeded node, most recent first?\n\n' +
                 'Only works on a run that has stopped moving.')) return;
    try {
      const result = await post(`/runs/${state.runId}/${action}?reason=${
        encodeURIComponent('Requested from console')}`);
      toast(result.detail || (action + ' applied'), 'ok');
      loadRun();
    } catch (error) {
      toast(action + ' failed: ' + error.message, 'err');
    }
  }

  async function replan() {
    const guidance = prompt('Amend the requirement — what should change?\n\n' +
      'e.g. "Add monitoring and health checks"');
    if (!guidance) return;
    try {
      const outcome = await post(`/runs/${state.runId}/replan?guidance=${encodeURIComponent(guidance)}`);
      const explain = {
        ADMITTED: 'Plan revised and adopted.',
        AWAITING_PLAN_APPROVAL: 'The plan change itself needs approval — see the inbox.',
        NO_CHANGE: 'The amendment produced an identical graph.',
        OSCILLATION_DETECTED: 'This shape has been seen before; refusing to loop.',
        BOUND_REACHED: 'Re-plan limit reached for this run.',
        REJECTED: 'Refused.'
      };
      toast(outcome.status + ' — ' + (explain[outcome.status] || outcome.detail || ''), 'ok');
      state.plan = null;
      loadRun();
    } catch (error) {
      toast('Re-plan failed: ' + error.message, 'err');
    }
  }

  // ─────────────────────────────────── inspector ───────────────────────────────────

  function inspect(taskId) {
    if (!state.run || !state.plan) return;
    const node = state.run.nodes.find(n => n.taskId === taskId);
    const task = state.plan.plan.tasks.find(t => t.id === taskId);
    if (!node || !task) return;

    const deps = state.plan.plan.dependencies;
    const upstream = deps.filter(d => d.to === taskId);
    const downstream = deps.filter(d => d.from === taskId);
    const nodeEvents = state.events.filter(e => e.taskId === taskId);

    const list = (items) => items.length
      ? '<div class="tag-list">' + items.map(i => '<span class="tag">' + esc(i) + '</span>').join('') + '</div>'
      : '<span style="color:var(--muted)">none</span>';

    document.getElementById('inspTitle').textContent = taskId;
    document.getElementById('inspBody').innerHTML = `
      <div class="insp-section">
        <h4>State</h4>
        <div class="kv">
          <div class="k">State</div>
          <div class="v"><span class="pill s-${esc(node.state)}">${esc(node.state)}</span>
            ${node.completedDegraded ? ' <span class="pill s-DEGRADED">DEGRADED</span>' : ''}</div>
          <div class="k">Attempt</div><div class="v">${node.attempt}</div>
          <div class="k">Duration</div><div class="v">${ms(node.durationMs)}</div>
          <div class="k">Started</div><div class="v">${clock(node.startedAt)}</div>
          <div class="k">Ended</div><div class="v">${clock(node.endedAt)}</div>
          <div class="k">Message</div><div class="v">${esc(node.message || '—')}</div>
        </div>
      </div>
      <div class="insp-section">
        <h4>Task</h4>
        <div class="kv">
          <div class="k">Title</div><div class="v">${esc(task.title)}</div>
          <div class="k">Stage</div><div class="v">${esc(task.stage)}</div>
          <div class="k">Agent</div><div class="v">${esc(task.agentRole)}</div>
          <div class="k">Blast radius</div>
          <div class="v risk-${esc(task.blastRadius)}">${esc(task.blastRadius)}</div>
          <div class="k">Human task</div><div class="v">${task.requiresHumanApproval ? 'yes' : 'no'}</div>
          <div class="k">Description</div><div class="v">${esc(task.description || '—')}</div>
        </div>
      </div>
      <div class="insp-section">
        <h4>Acceptance criteria — what the exit gate checks</h4>
        ${(task.acceptanceCriteria || []).length
          ? '<ul style="margin:0;padding-left:19px;font-size:13px">' +
            task.acceptanceCriteria.map(c => '<li>' + esc(c) + '</li>').join('') + '</ul>'
          : '<span style="color:var(--muted)">none declared</span>'}
      </div>
      <div class="insp-section">
        <h4>Reads</h4>${list(task.reads || [])}
      </div>
      <div class="insp-section">
        <h4>Writes — the evidence the gate demands</h4>${list(task.writes || [])}
      </div>
      <div class="insp-section">
        <h4>Depends on</h4>
        ${upstream.length ? upstream.map(d => `<div style="font-size:12.5px;margin-bottom:7px">
            <span class="tag">${esc(d.from)}</span> <em style="color:var(--muted)">${esc(d.kind)}</em>
            <div style="color:var(--muted);margin-top:2px">${esc(d.reason)}</div></div>`).join('')
          : '<span style="color:var(--muted)">nothing — this is an entry point</span>'}
      </div>
      <div class="insp-section">
        <h4>Blocks</h4>${list(downstream.map(d => d.to))}
      </div>
      <div class="insp-section">
        <h4>Event history</h4>
        ${nodeEvents.length ? nodeEvents.map(e => `
          <div class="log-row ${eventClass(e.type)}" style="grid-template-columns:40px 1fr">
            <span class="seq">${e.seq}</span>
            <span><span class="type">${esc(e.type)}</span>
              <div class="msg">${esc(e.message || '')}</div></span>
          </div>`).join('')
          : '<span style="color:var(--muted)">nothing yet</span>'}
      </div>`;

    document.getElementById('inspector').classList.add('open');
    document.getElementById('scrim').classList.add('open');
  }

  function closeInspector() {
    document.getElementById('inspector').classList.remove('open');
    document.getElementById('scrim').classList.remove('open');
  }

  // ─────────────────────────────────── policies ───────────────────────────────────

  async function refreshPolicies() {
    try {
      const policies = await api('/policies');
      document.querySelector('#policyTable tbody').innerHTML = policies.map(p => `
        <tr>
          <td class="mono">${esc(p.id)}</td>
          <td><span class="pill ${p.effect === 'DENY' ? 's-FAILED'
            : p.effect === 'REQUIRE_APPROVAL' ? 's-AWAITING_APPROVAL' : 's-PENDING'}">
            ${esc(p.effect)}</span></td>
          <td>${esc(p.name)}<div style="color:var(--muted);font-size:11.5px">
            ${esc(p.category)}</div></td>
          <td style="color:var(--muted)">${esc(p.rationale)}</td>
        </tr>`).join('');
    } catch (error) {
      toast('Could not load policies: ' + error.message, 'err');
    }
  }

  // ─────────────────────────────────── live stream ───────────────────────────────────

  function openStream(runId) {
    closeStream();
    try {
      const sse = new EventSource(`${API}/runs/${runId}/stream?since=0`);
      state.sse = sse;

      sse.addEventListener('run-event', (message) => {
        const event = JSON.parse(message.data);
        if (event.seq <= state.lastSeq) return;   // Replay overlap after a reconnect.
        state.lastSeq = event.seq;
        state.events.push(event);
        renderEvents();

        // Any event that can change node state or the approval queue warrants a reload. Cheap, and
        // far more reliable than trying to mutate the local model from event types.
        loadRun();
      });

      sse.onopen = () => connection(true);
      sse.onerror = () => {
        connection(false);
        // EventSource reconnects on its own; the poll below is the safety net if it cannot.
      };
    } catch (error) {
      connection(false);
    }

    // Belt and braces: SSE carries the live feed, but a run that finished while the page was
    // backgrounded should still settle to its true final state.
    state.poll = setInterval(() => { if (state.runId) loadRun(); }, 5000);
  }

  function closeStream() {
    if (state.sse) { state.sse.close(); state.sse = null; }
    if (state.poll) { clearInterval(state.poll); state.poll = null; }
  }

  // ─────────────────────────────────── boot ───────────────────────────────────

  document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('.tab').forEach(tab =>
      tab.addEventListener('click', () => show(tab.dataset.view)));
    document.querySelectorAll('.chip').forEach(chip =>
      chip.addEventListener('click', () => {
        document.getElementById('reqText').value = chip.dataset.req;
      }));
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeInspector(); });

    refreshDashboard();
    refreshBaselines();
    setInterval(() => { if (state.view === 'dashboard') refreshDashboard(); }, 4000);
    setInterval(() => { if (state.view === 'approvals') refreshApprovals(); }, 4000);
  });

  return {
    show, startRun, previewPlan, openRun, refreshDashboard, refreshApprovals, refreshBaselines,
    decide, control, replan, inspect, closeInspector
  };
})();
