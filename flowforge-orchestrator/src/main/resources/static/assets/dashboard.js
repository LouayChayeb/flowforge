// FlowForge dashboard: polls the orchestrator's read-only endpoints and explains what it sees.
import {
  COMPONENTS, RUN_STATES, STEP_STATES, ATTEMPT_STATES, TIMELINE_WHY, JOURNEY, FAILURES, GUARANTEES, GLOSSARY, CODE_MAP,
} from './knowledge.js';

const WORKFLOW = 'order-processing';
// Same as docs/examples/order-processing.json: reserve-inventory and check-fraud run in parallel.
const ORDER_PROCESSING = {
  name: WORKFLOW,
  steps: [
    { name: 'validate-order', type: 'VALIDATE_ORDER', maxAttempts: 1 },
    { name: 'reserve-inventory', type: 'RESERVE_INVENTORY', compensateWith: 'RELEASE_INVENTORY', dependsOn: ['validate-order'] },
    { name: 'check-fraud', type: 'CHECK_FRAUD', dependsOn: ['validate-order'] },
    { name: 'charge-payment', type: 'CHARGE_PAYMENT', compensateWith: 'REFUND_PAYMENT', maxAttempts: 5, dependsOn: ['reserve-inventory', 'check-fraud'] },
    { name: 'create-shipment', type: 'CREATE_SHIPMENT' },
    { name: 'send-confirmation', type: 'SEND_CONFIRMATION' },
  ],
};

// The same order with a 20 s cooling-off period after the charge: a durable WAIT step, during which it can be cancelled.
const COOLING_OFF = {
  name: 'order-with-cooling-off',
  steps: [
    ...ORDER_PROCESSING.steps.slice(0, 4),
    { name: 'cooling-off', type: 'WAIT', waitFor: 'PT20S', dependsOn: ['charge-payment'] },
    { name: 'create-shipment', type: 'CREATE_SHIPMENT', dependsOn: ['cooling-off'] },
    { name: 'send-confirmation', type: 'SEND_CONFIRMATION' },
  ],
};

const SCENARIOS = [
  { id: 'normal', title: 'Normal order', expect: 'COMPLETED',
    text: 'All six steps succeed, one attempt each; reserve-inventory and check-fraud run at the same time. The baseline.',
    input: {} },
  { id: 'fraud', title: 'Fraud detected', expect: 'COMPENSATED',
    text: 'check-fraud fails while its parallel sibling reserve-inventory succeeds: the engine waits for the sibling, then undoes it.',
    input: { fraud: true, fraudCheckDelayMs: 1500 } },
  { id: 'flaky', title: 'Flaky carrier', expect: 'COMPLETED',
    text: 'Shipping times out twice (retryable), waits 1 s then 2 s of backoff, and succeeds on attempt 3.',
    input: { shipmentFailures: 2 } },
  { id: 'down', title: 'Carrier down', expect: 'COMPENSATED',
    text: 'Shipping keeps timing out until its 3 attempts are used up. Then payment is refunded and inventory released.',
    input: { shipmentFailures: 99 } },
  { id: 'declined', title: 'Payment declined', expect: 'COMPENSATED',
    text: 'Amount above 10 000 is a permanent failure. Only the inventory reservation needs undoing.',
    input: { amount: 50000 } },
  { id: 'xx', title: 'Undeliverable address', expect: 'COMPENSATED',
    text: 'Country XX cannot be shipped to. Undo in reverse order: refund the payment, then release inventory.',
    input: { country: 'XX' } },
  { id: 'invalid', title: 'Invalid order', expect: 'FAILED',
    text: 'No customer id: the very first step fails permanently. Nothing succeeded, so nothing to undo.',
    input: { customerId: null } },
  { id: 'refund', title: 'Refund fails too', expect: 'COMPENSATION_FAILED',
    text: 'Shipping is refused and then the refund is rejected: the one case the engine cannot fix alone.',
    input: { country: 'XX', refundFails: true } },
  { id: 'cooling', title: 'Cooling-off order (cancel it!)', expect: 'CANCELLED', workflow: COOLING_OFF.name,
    text: 'After the charge, a durable 20 s timer runs before shipping. Open the run and press Cancel run during the wait: the charge is refunded and the stock released.',
    input: {} },
  { id: 'slow', title: 'Slow shipment (kill a worker now)', expect: 'COMPLETED',
    text: 'Shipping takes 8 s. Watch the lease count down on the Engine tab — or kill the worker running it (see Kafka tab).',
    input: { shipmentDelayMs: 8000 } },
];

const RUN_STATUS_COLOR = {
  RUNNING: 'var(--s-running)', COMPLETED: 'var(--s-completed)', FAILED: 'var(--s-failed)',
  COMPENSATING: 'var(--s-compensating)', COMPENSATED: 'var(--s-compensated)', COMPENSATION_FAILED: 'var(--s-compfailed)', CANCELLED: 'var(--s-pending)',
  PENDING: 'var(--s-pending)', RETRY_WAIT: 'var(--s-retry)', SUCCEEDED: 'var(--s-completed)',
  RETRYABLE_FAILURE: 'var(--s-retry)', PERMANENT_FAILURE: 'var(--s-failed)', TIMED_OUT: 'var(--s-timedout)',
};
const MEMBER_COLORS = ['#3b5bdb', '#2b8a3e', '#e8890c', '#7048e8', '#c2255c', '#0b7285', '#5f3dc4', '#d9480f', '#495057'];

const state = {
  config: null, stats: null, prevStats: null, rates: {}, kafka: null, runs: [], invariants: [], consistency: null,
  runId: null, run: null, commands: [], stepId: null, selectedComponent: 'dispatcher', clockOffset: 0,
  connected: false, view: 'live',
};

// ---------------------------------------------------------------- helpers
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const now = () => Date.now() + state.clockOffset;
const ms = (iso) => (iso ? new Date(iso).getTime() : null);
const pill = (status, extra = '') => `<span class="pill c-${esc(status)} ${extra}">${esc(status)}</span>`;
const livePill = (status) => pill(status, ['RUNNING', 'COMPENSATING', 'RETRY_WAIT'].includes(status) ? 'pulse' : '');

function fmtDur(millis) {
  if (millis == null || Number.isNaN(millis)) return '—';
  const m = Math.abs(millis);
  if (m < 1000) return `${Math.round(millis)} ms`;
  if (m < 60_000) {
    const seconds = millis / 1000;
    return Number.isInteger(seconds) ? `${seconds} s` : `${seconds.toFixed(m < 10_000 ? 2 : 1)} s`;
  }
  const min = Math.floor(m / 60_000);
  const sec = Math.round((m % 60_000) / 1000);
  return sec ? `${min} min ${sec} s` : `${min} min`;
}
function parseIsoDuration(text) { // "PT1M", "PT0.5S", "PT60S"
  if (!text) return null;
  const match = /PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?/.exec(text);
  if (!match) return null;
  return ((+match[1] || 0) * 3600 + (+match[2] || 0) * 60 + (+match[3] || 0)) * 1000;
}
/** RetryPolicy.backoffAfter(): initial × multiplier^(attempt−1), capped at max. */
function retryBackoff(failedAttempt) {
  const c = state.config;
  if (!c) return null;
  const init = parseIsoDuration(c.initialBackoff); const max = parseIsoDuration(c.maxBackoff);
  return Math.min(init * c.backoffMultiplier ** (failedAttempt - 1), max);
}
function fmtClock(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleTimeString([], { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0');
}
function ago(iso) {
  const diff = now() - ms(iso);
  if (diff < 5000) return 'just now';
  if (diff < 60_000) return `${Math.round(diff / 1000)} s ago`;
  if (diff < 3_600_000) return `${Math.round(diff / 60_000)} min ago`;
  return new Date(iso).toLocaleString();
}
function toast(html, timeout = 4500) {
  const t = $('#toast');
  t.innerHTML = html;
  t.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.remove('show'), timeout);
}
function fillConfig(root = document) {
  if (!state.config) return;
  const c = state.config;
  const values = {
    dispatchIntervalMs: `${c.dispatchIntervalMs} ms`, outboxIntervalMs: `${c.outboxIntervalMs} ms`,
    recoveryIntervalMs: `${c.recoveryIntervalMs} ms`, partitions: String(c.partitions),
    lease: fmtDur(parseIsoDuration(c.leaseDuration)),
  };
  $$('[data-config]', root).forEach((e) => { e.textContent = values[e.dataset.config] ?? '?'; });
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options, headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...options.headers },
  });
  if (!response.ok) {
    let detail = `${response.status}`;
    try { detail = (await response.json()).detail || detail; } catch { /* not JSON */ }
    const error = new Error(detail);
    error.status = response.status;
    throw error;
  }
  return response.status === 204 ? null : response.json();
}

// ---------------------------------------------------------------- scenarios
function orderInput(extra) {
  const n = Math.floor(1000 + Math.random() * 9000);
  const input = { orderId: `ORD-${n}`, customerId: `CUS-${n % 97}`, amount: 120, country: 'FR', ...extra };
  Object.keys(input).forEach((k) => input[k] === null && delete input[k]);
  return input;
}

function scenarioOf(input = {}, workflow) {
  if (workflow === 'order-with-cooling-off') return 'Cooling-off order';
  if (input.refundFails === true || input.refundFails === 'true') return 'Refund fails too';
  if (input.fraud === true || input.fraud === 'true') return 'Fraud detected';
  if (+input.shipmentFailures >= 3) return 'Carrier down';
  if (+input.shipmentFailures > 0) return 'Flaky carrier';
  if (+input.shipmentDelayMs > 0) return 'Slow shipment';
  if (input.country === 'XX') return 'Undeliverable address';
  if (+input.amount > 10000) return 'Payment declined';
  if (!input.customerId || !input.orderId || !(+input.amount > 0)) return 'Invalid order';
  return 'Normal order';
}

/** Registers (a new version of) a workflow if it is missing or older than this page's definition. */
async function ensureWorkflow(definition = ORDER_PROCESSING) {
  let latest = null;
  try {
    latest = await api(`/api/workflows/${definition.name}`);
  } catch (e) {
    if (e.status !== 404) throw e;
  }
  const upToDate = latest && definition.steps.every((d) => latest.steps.some((s) => s.name === d.name && s.type === d.type));
  if (!upToDate) {
    const created = await api('/api/workflows', { method: 'POST', body: JSON.stringify(definition) });
    toast(`Registered <b>${esc(definition.name)}</b> version ${created.version}. Runs already started keep the version they began with.`, 6000);
  }
}

async function startOrder(input, { quiet = false, workflow = WORKFLOW } = {}) {
  await ensureWorkflow(workflow === COOLING_OFF.name ? COOLING_OFF : ORDER_PROCESSING);
  const created = await api(`/api/workflows/${workflow}/runs`, { method: 'POST', body: JSON.stringify({ input }) });
  if (!quiet) {
    toast(`202 Accepted — run <b>${esc(input.orderId ?? created.runId.slice(0, 8))}</b> stored as RUNNING.
      <a href="#" data-open-run="${created.runId}" style="color:inherit;text-decoration:underline">Open it</a>`, 6000);
  }
  pollFast();
  return created;
}

function renderScenarios() {
  $('#scenarios').innerHTML = SCENARIOS.map((s) => `
    <button class="scenario" data-scenario="${s.id}">
      <div class="scenario-top"><span class="scenario-title">${esc(s.title)}</span>${pill(s.expect)}</div>
      <div class="scenario-text">${esc(s.text)}</div>
    </button>`).join('');
  $('#custom-input').value = JSON.stringify(orderInput({ shipmentFailures: 1 }), null, 2);
}

// ---------------------------------------------------------------- architecture diagram
const NODES = [
  { id: 'api', x: 20, y: 183, title: 'REST API', sub: 'records runs, answers 202', cls: '' },
  { id: 'dispatcher', x: 250, y: 25, title: 'Dispatcher', sub: 'claims ready steps', cls: '' },
  { id: 'postgres', x: 250, y: 168, w: 350, h: 94, title: 'PostgreSQL — source of truth', sub: 'runs · steps · attempts · outbox', cls: 'store' },
  { id: 'outbox', x: 470, y: 25, title: 'Outbox publisher', sub: 'sends committed commands', cls: '' },
  { id: 'commands', x: 700, y: 25, w: 190, title: 'Kafka', sub: 'flowforge.step-commands', cls: 'kafka' },
  { id: 'workers', x: 790, y: 168, w: 190, h: 94, title: 'Workers', sub: 'run the step handlers', cls: 'worker' },
  { id: 'results', x: 700, y: 335, w: 190, title: 'Kafka', sub: 'flowforge.step-results', cls: 'kafka' },
  { id: 'listener', x: 470, y: 335, title: 'Result listener', sub: 'applies outcomes', cls: '' },
  { id: 'recovery', x: 250, y: 335, title: 'Recovery sweeper', sub: 'expires lost attempts', cls: '' },
  { id: 'checker', x: 20, y: 335, title: 'Consistency checker', sub: '10 invariants', cls: 'store' },
];
const EDGES = [
  { id: 'e-api', d: 'M190 215 L250 215', label: 'insert', lx: 196, ly: 205, rate: 'runs' },
  { id: 'e-claim', d: 'M335 89 L335 168', label: 'claim', lx: 343, ly: 132, rate: 'claimed' },
  { id: 'e-pending', d: 'M555 168 L555 89', label: 'pending cmds', lx: 563, ly: 132, rate: 'published' },
  { id: 'e-publish', d: 'M640 57 L700 57', label: 'publish', lx: 648, ly: 47, rate: 'published' },
  { id: 'e-consume', d: 'M860 89 L880 168', label: 'consume', lx: 885, ly: 132, rate: 'published' },
  { id: 'e-report', d: 'M880 262 L860 335', label: 'report', lx: 885, ly: 305, rate: 'applied' },
  { id: 'e-results', d: 'M700 367 L640 367', label: 'deliver', lx: 646, ly: 357, rate: 'applied' },
  { id: 'e-apply', d: 'M555 335 L555 262', label: 'apply', lx: 563, ly: 305, rate: 'applied' },
  { id: 'e-recover', d: 'M335 335 L335 262', label: 'expire', lx: 343, ly: 305, rate: 'expired' },
  { id: 'e-check', d: 'M105 335 L105 300 Q105 290 120 290 L250 290 L250 262', label: 'reads', lx: 130, ly: 282, rate: null },
];

function renderDiagram() {
  const svg = $('#diagram');
  svg.innerHTML = `
    <defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
      <path d="M0 0 L10 5 L0 10 z"/></marker></defs>
    ${EDGES.map((e) => `<path class="edge" id="${e.id}" d="${e.d}" marker-end="url(#arrow)"/>
      <text class="edge-label" id="${e.id}-l" x="${e.lx}" y="${e.ly}">${e.label}</text>`).join('')}
    ${NODES.map((n) => {
      const w = n.w || 170; const h = n.h || 64;
      return `<g class="node ${n.cls}" data-component="${n.id}" transform="translate(${n.x},${n.y})" tabindex="0">
        <rect class="box" width="${w}" height="${h}" rx="9"/>
        <rect class="stripe" width="4" height="${h - 16}" x="0" y="8" rx="2"/>
        <text class="title" x="14" y="22">${esc(n.title)}</text>
        <text class="sub" x="14" y="39">${esc(n.sub)}</text>
        <text class="metric" id="m-${n.id}" x="14" y="${h - 11}"></text>
      </g>`;
    }).join('')}`;
}

function updateDiagram() {
  const r = state.rates; const s = state.stats; const k = state.kafka;
  const rateText = (v) => (v > 0.05 ? `${v.toFixed(1)}/s` : '');
  EDGES.forEach((e) => {
    const v = e.rate ? r[e.rate] || 0 : 0;
    const active = v > 0.05 || (e.id === 'e-check' && state.consistency);
    $(`#${e.id}`)?.classList.toggle('active', v > 0.05);
    const label = $(`#${e.id}-l`);
    if (label) { label.textContent = e.label + (rateText(v) ? ` ${rateText(v)}` : ''); label.classList.toggle('active', active && v > 0.05); }
  });
  if (!s) return;
  const db = s.database;
  const running = (db.runs.RUNNING || 0) + (db.runs.COMPENSATING || 0);
  const set = (id, text) => { const e = $(`#m-${id}`); if (e) e.textContent = text; };
  set('api', `${running} run${running === 1 ? '' : 's'} in progress`);
  set('dispatcher', `${s.counters.stepsClaimed} claimed · every ${state.config?.dispatchIntervalMs ?? '?'} ms`);
  set('postgres', `${sum(db.runs)} runs · ${sum(db.steps)} steps · ${sum(db.attempts)} attempts · ${db.outboxPending} pending`);
  set('outbox', `${s.counters.outboxPublished} sent · ${s.counters.outboxFailures} failed`);
  set('listener', `${s.counters.resultsApplied} applied · ${s.counters.resultsStale} stale`);
  set('recovery', `${s.counters.leasesExpired} expired · ${db.leased.length} leased now`);
  const workerGroup = k?.groups?.find((g) => g.topic === 'flowforge.step-commands');
  const commandsGroupLag = workerGroup ? workerGroup.lag : null;
  set('commands', k?.reachable ? `${k.topics.find((t) => t.name === 'flowforge.step-commands')?.messages ?? 0} msgs · lag ${commandsGroupLag ?? '?'}` : 'unreachable');
  const resultsGroup = k?.groups?.find((g) => g.topic === 'flowforge.step-results');
  set('results', k?.reachable ? `${k.topics.find((t) => t.name === 'flowforge.step-results')?.messages ?? 0} msgs · lag ${resultsGroup?.lag ?? '?'}` : 'unreachable');
  // Each worker process runs several consumer threads; their client ids end in -0, -1, -2…
  const processes = workerGroup ? new Set(workerGroup.members.map((m) => m.clientId.replace(/-\d+$/, ''))).size : 0;
  set('workers', workerGroup
    ? `${processes} worker${processes === 1 ? '' : 's'} · ${workerGroup.members.length} threads · ${workerGroup.state.toLowerCase()}` : '…');
  set('checker', state.consistency ? (state.consistency.consistent ? '✓ all 10 hold' : `✗ ${state.consistency.violations.length} broken`) : '…');
}
const sum = (o) => Object.values(o || {}).reduce((a, b) => a + b, 0);

function renderComponent(id) {
  state.selectedComponent = id;
  $$('#diagram .node').forEach((n) => n.classList.toggle('selected', n.dataset.component === id));
  const c = COMPONENTS[id];
  $('#component-drawer').innerHTML = `
    <div class="row"><h3>${esc(c.title)}</h3><span class="muted small">${c.role}</span></div>
    ${c.how.map((p) => `<p>${p}</p>`).join('')}
    <h4 style="margin-top:10px">In the code</h4>
    <ul class="code-list">${c.code.map(([name, note]) => `<li><code>${esc(name)}</code> <span class="muted small">— ${esc(note)}</span></li>`).join('')}</ul>
    <p class="small" style="margin-top:8px"><b>If it fails:</b> ${c.failure}</p>`;
  fillConfig($('#component-drawer'));
}

// ---------------------------------------------------------------- runs list
/** Status chips over the run list: counts, success rate, and a one-click filter. */
function renderRunChips() {
  const counts = {};
  state.runs.forEach((r) => { counts[r.status] = (counts[r.status] || 0) + 1; });
  const finished = state.runs.filter((r) => r.completedAt);
  const ok = finished.filter((r) => r.status === 'COMPLETED').length;
  const active = $('#run-filter').value;
  const order = ['RUNNING', 'COMPENSATING', 'COMPLETED', 'COMPENSATED', 'FAILED', 'COMPENSATION_FAILED', 'CANCELLED'];
  $('#run-chips').innerHTML = (active ? '<button class="chip on" data-chip="">show all ✕</button>' : '')
    + order.filter((s) => counts[s] || s === active).map((s) => `<button class="chip ${s === active ? 'on' : ''}" data-chip="${s}" style="--c:${RUN_STATUS_COLOR[s]}">
      <span class="wf-dot"></span>${s.toLowerCase().replaceAll('_', ' ')} <b>${counts[s] || 0}</b></button>`).join('')
    + (finished.length && !active ? `<span class="spacer"></span><span class="small muted" title="of the finished runs in this list">${Math.round((ok / finished.length) * 100)}% completed</span>` : '');
}

function renderRuns() {
  renderRunChips();
  const body = $('#runs-body');
  if (!state.runs.length) {
    body.innerHTML = '<tr><td colspan="6" class="empty">No runs yet. Start an order on the left.</td></tr>';
    return;
  }
  body.innerHTML = state.runs.map((r) => {
    const end = r.completedAt ? ms(r.completedAt) : now();
    const at = r.currentStep
      ? `${r.currentStepKind === 'COMPENSATION' ? 'undo ' : ''}${esc(r.currentStep)}${r.activeSteps > 1
        ? ` <span class="small muted" title="steps running in parallel">+${r.activeSteps - 1}</span>` : ''}`
      : '<span class="faint">—</span>';
    return `<tr class="clickable ${r.runId === state.runId ? 'selected' : ''}" data-run="${r.runId}">
      <td><b>${esc(r.input?.orderId ?? r.runId.slice(0, 8))}</b><div class="small muted">${esc(scenarioOf(r.input, r.workflow))}</div></td>
      <td>${livePill(r.status)}</td>
      <td>${at}</td>
      <td class="nowrap">${r.attempts}${r.failedAttempts ? ` <span class="small" style="color:var(--s-failed)">(${r.failedAttempts} failed)</span>` : ''}</td>
      <td class="nowrap">${fmtDur(end - ms(r.createdAt))}</td>
      <td class="nowrap small muted">${ago(r.createdAt)}</td>
    </tr>`;
  }).join('');
}

// ---------------------------------------------------------------- run detail
async function openRun(runId, { switchView = true } = {}) {
  if (state.runId !== runId) { state.stepId = null; }
  state.runId = runId;
  if (switchView) showView('run');
  await refreshRun();
}

async function refreshRun() {
  if (!state.runId) return;
  try {
    const [run, commands] = await Promise.all([api(`/api/runs/${state.runId}`), api(`/api/runs/${state.runId}/commands`)]);
    state.run = run; state.commands = commands;
    await loadRunPayments(run);
    renderRun();
  } catch (e) {
    if (e.status === 404) { state.runId = null; state.run = null; renderRun(); }
  }
}

function renderRun() {
  const run = state.run;
  $('#run-empty').hidden = !!run;
  $('#run-detail').hidden = !run;
  if (!run) return;
  const input = run.input || {};
  $('#run-title').textContent = `${input.orderId ?? 'Run'} · ${scenarioOf(input, run.workflow)}`;
  $('#run-status').innerHTML = pill(run.status, `big ${['RUNNING', 'COMPENSATING'].includes(run.status) ? 'pulse' : ''}`);
  $('#run-json').href = `/api/runs/${run.runId}`;
  $('#run-cancel').hidden = !(run.status === 'RUNNING' && !run.cancelRequestedAt);
  $('#run-retry').hidden = run.status !== 'COMPENSATION_FAILED';
  const traceLink = $('#run-trace');
  if (run.traceId && state.config?.traceUrl) {
    traceLink.hidden = false;
    traceLink.href = state.config.traceUrl.replace('{traceId}', run.traceId);
  } else {
    traceLink.hidden = true;
  }
  const end = run.completedAt ? ms(run.completedAt) : now();
  $('#run-kv').innerHTML = `
    <dt>Run id</dt><dd class="mono">${esc(run.runId)}</dd>
    <dt>Workflow</dt><dd>${esc(run.workflow)} v${run.version}</dd>
    <dt>Started</dt><dd>${fmtClock(run.createdAt)} <span class="muted small">(${ago(run.createdAt)})</span></dd>
    <dt>Finished</dt><dd>${run.completedAt ? fmtClock(run.completedAt) : '<span class="muted">not yet</span>'}</dd>
    <dt>Duration</dt><dd>${fmtDur(end - ms(run.createdAt))}</dd>
    <dt>Current step</dt><dd>${run.currentStep ? esc(run.currentStep) : '<span class="muted">none — the run is finished</span>'}</dd>
    <dt>Payment provider</dt><dd>${runPaymentsText()}</dd>
    ${run.cancelRequestedAt ? `<dt>Cancel requested</dt><dd>${fmtClock(run.cancelRequestedAt)}</dd>` : ''}
    ${(run.operatorActions ?? []).length ? `<dt>Operator actions</dt><dd>${run.operatorActions.map((a) =>
      `<div><b>${esc(a.action)}</b> by ${esc(a.actor)} at ${fmtClock(a.performedAt)} <span class="muted">(was ${esc(a.runStatusBefore)})</span>${a.reason ? `<div class="small muted">"${esc(a.reason)}"</div>` : ''}</div>`).join('')}</dd>` : ''}
    <dt>Input</dt><dd><pre>${esc(JSON.stringify(input, null, 2))}</pre></dd>`;
  $('#run-state-text').innerHTML = `<b>${esc(run.status)}</b>: ${esc(RUN_STATES[run.status] || '')}`;
  renderRunStateMachine(run);
  renderLanes(run);
  renderStepDrawer();
  renderWaterfall(run);
  renderTimeline(run);
}

/** Every attempt of every step on one time axis: where the run spent its time, and what ran in parallel. */
function renderWaterfall(run) {
  const start = ms(run.createdAt);
  const end = Math.max(run.completedAt ? ms(run.completedAt) : now(),
    ...run.steps.map((s) => (s.status === 'PENDING' || s.status === 'RETRY_WAIT') && s.nextAttemptAt ? ms(s.nextAttemptAt) : 0));
  const span = Math.max(end - start, 1);
  const x = (t) => `${Math.min(100, Math.max(0, ((t - start) / span) * 100)).toFixed(3)}%`;
  const w = (a, b) => `${Math.max(0.4, ((Math.min(b, end) - Math.max(a, start)) / span) * 100).toFixed(3)}%`;
  const done = (s) => ms(s?.attempts.find((a) => a.status === 'SUCCEEDED')?.finishedAt);
  const forward = new Map(run.steps.filter((s) => s.kind !== 'COMPENSATION').map((s) => [s.name, s]));
  const rows = run.steps.map((step) => {
    const segs = [];
    const label = step.kind === 'COMPENSATION' ? `undo ${step.name}` : step.name;
    if (step.waitFor) {
      // a timer: hatched from the moment it was armed until it fired (or until now)
      const wait = parseIsoDuration(step.waitFor);
      const deps = (step.dependsOn ?? []).map((n) => forward.get(n));
      const armed = step.nextAttemptAt ? ms(step.nextAttemptAt) - wait : deps.length ? Math.max(...deps.map(done)) : start;
      const fired = ms(step.attempts[0]?.startedAt);
      const stop = fired || (step.nextAttemptAt ? Math.min(ms(step.nextAttemptAt), now()) : null);
      if (armed && stop) segs.push(`<div class="wf-seg wait" style="left:${x(armed)};width:${w(armed, stop)}" title="durable timer, ${fmtDur(wait)}"></div>`);
      if (step.nextAttemptAt && !fired) segs.push(`<div class="wf-seg mark" style="left:${x(ms(step.nextAttemptAt))}" title="due ${fmtClock(step.nextAttemptAt)}">⏱</div>`);
      if (fired) segs.push(`<div class="wf-seg mark" style="left:${x(fired)}" title="fired ${fmtClock(step.attempts[0].startedAt)}">⏱</div>`);
    }
    step.attempts.forEach((a, i) => {
      if (step.waitFor) return;
      const s0 = ms(a.startedAt); const s1 = ms(a.finishedAt) || now();
      const prev = step.attempts[i - 1];
      if (prev?.finishedAt) segs.push(`<div class="wf-seg wait" style="left:${x(ms(prev.finishedAt))};width:${w(ms(prev.finishedAt), s0)}" title="backoff ${fmtDur(s0 - ms(prev.finishedAt))}"></div>`);
      segs.push(`<div class="wf-seg ${a.finishedAt ? '' : 'live'}" style="left:${x(s0)};width:${w(s0, s1)};--c:${RUN_STATUS_COLOR[a.status]}"
        title="attempt ${a.attempt}: ${a.status} · ${fmtDur(s1 - s0)}${a.errorMessage ? ' · ' + esc(a.errorMessage) : ''}"></div>`);
    });
    if (step.status === 'RETRY_WAIT' && step.nextAttemptAt) {
      const last = ms(step.attempts.at(-1)?.finishedAt);
      if (last) segs.push(`<div class="wf-seg wait" style="left:${x(last)};width:${w(last, Math.min(now(), ms(step.nextAttemptAt)))}" title="backoff until ${fmtClock(step.nextAttemptAt)}"></div>`);
    }
    const busy = step.attempts.reduce((t, a) => t + ((ms(a.finishedAt) || now()) - ms(a.startedAt)), 0);
    const dur = step.waitFor ? fmtDur(parseIsoDuration(step.waitFor)) : step.attempts.length ? fmtDur(busy) : '—';
    return `<div class="wf-row ${state.stepId === step.stepExecutionId ? 'selected' : ''} ${step.kind === 'COMPENSATION' ? 'undo' : ''}" data-wf-step="${step.stepExecutionId}">
      <div class="wf-label" title="${esc(step.type)}"><span class="wf-dot" style="--c:${RUN_STATUS_COLOR[step.status]}"></span>${esc(label)}</div>
      <div class="wf-track">${segs.join('')}</div>
      <div class="wf-dur small muted">${dur}</div>
    </div>`;
  });
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => `<span style="left:${f * 100}%">+${fmtDur(span * f)}</span>`).join('');
  const nowAt = Math.min(1, (now() - start) / span);
  const nowLine = run.completedAt ? '' : `<div class="wf-track wf-now-track"><div class="wf-now" style="left:${(nowAt * 100).toFixed(3)}%" title="now"></div></div>`;
  $('#waterfall').innerHTML = `${rows.join('')}<div class="wf-row wf-axis"><div></div><div class="wf-track ticks">${ticks}</div><div></div></div>
    <div class="wf-overlay"><div></div>${nowLine}<div></div></div>`;
}

function renderRunStateMachine(run) {
  const S = {
    RUNNING: [20, 65], COMPLETED: [200, 10], FAILED: [200, 120], COMPENSATING: [200, 65], CANCELLED: [380, 150],
    COMPENSATED: [380, 30], COMPENSATION_FAILED: [380, 100],
  };
  const T = [
    ['RUNNING', 'COMPLETED', 'last step done'], ['RUNNING', 'COMPENSATING', 'step failed'], ['RUNNING', 'FAILED', 'failed, nothing to undo'],
    ['COMPENSATING', 'COMPENSATED', 'all undone'], ['COMPENSATING', 'COMPENSATION_FAILED', 'undo failed'],
    ['RUNNING', 'CANCELLED', 'cancelled, nothing to undo'], ['COMPENSATING', 'CANCELLED', 'cancelled, all undone'],
  ];
  const compensated = run.steps.some((s) => s.kind === 'COMPENSATION');
  const visited = new Set(['RUNNING', run.status]);
  if (compensated) visited.add('COMPENSATING');
  const taken = new Set();
  if (run.status === 'COMPLETED') taken.add('RUNNING>COMPLETED');
  if (run.status === 'FAILED') taken.add('RUNNING>FAILED');
  if (compensated) taken.add('RUNNING>COMPENSATING');
  if (run.status === 'COMPENSATED') taken.add('COMPENSATING>COMPENSATED');
  if (run.status === 'COMPENSATION_FAILED') taken.add('COMPENSATING>COMPENSATION_FAILED');
  if (run.status === 'CANCELLED') taken.add(compensated ? 'COMPENSATING>CANCELLED' : 'RUNNING>CANCELLED');
  const W = 130; const H = 30;
  const edge = ([a, b, label]) => {
    const [x1, y1] = S[a]; const [x2, y2] = S[b];
    const sx = x1 + W; const sy = y1 + H / 2; const ex = x2; const ey = y2 + H / 2;
    const mx = (sx + ex) / 2;
    return `<path class="tr ${taken.has(`${a}>${b}`) ? 'taken' : ''}" d="M${sx} ${sy} C${mx} ${sy} ${mx} ${ey} ${ex - 2} ${ey}" marker-end="url(#sm-arrow)"/>
      <title>${esc(label)}</title>`;
  };
  $('#run-sm').innerHTML = `
    <defs><marker id="sm-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M0 0 L10 5 L0 10 z"/></marker></defs>
    ${T.map(edge).join('')}
    ${Object.entries(S).map(([name, [x, y]]) => `
      <g class="st ${visited.has(name) ? 'visited' : ''} ${run.status === name ? 'now' : ''}" style="--c:${RUN_STATUS_COLOR[name]}">
        <rect x="${x}" y="${y}" width="${W}" height="${H}" rx="15"/><text x="${x + W / 2}" y="${y + 19}" text-anchor="middle">${name}</text>
        <title>${esc(RUN_STATES[name])}</title></g>`).join('')}`;
}

function stepCard(step, run) {
  const current = run.currentStep === step.name && run.steps.find((s) => s.name === run.currentStep && s.status !== 'COMPLETED' && s.status !== 'FAILED') === step;
  const dots = step.attempts.map((a) => `<span title="attempt ${a.attempt}: ${a.status}" style="--c:${RUN_STATUS_COLOR[a.status]}"></span>`).join('');
  let bar = '';
  if (step.status === 'RUNNING' && step.leaseExpiresAt && state.config) {
    const lease = parseIsoDuration(state.config.leaseDuration) || 60000;
    const left = Math.max(0, ms(step.leaseExpiresAt) - now());
    bar = `<div class="lease-bar" title="lease: ${fmtDur(left)} left"><div style="width:${(left / lease) * 100}%"></div></div>
      <div class="small muted">lease ${fmtDur(left)} left</div>`;
  } else if (step.status === 'RETRY_WAIT' && step.nextAttemptAt) {
    const left = Math.max(0, ms(step.nextAttemptAt) - now());
    bar = `<div class="small" style="color:var(--s-retry)">retry in ${fmtDur(left)}</div>`;
  } else if (step.waitFor && step.status === 'PENDING' && step.nextAttemptAt) {
    const left = Math.max(0, ms(step.nextAttemptAt) - now());
    bar = `<div class="small" style="color:var(--s-retry)">⏱ fires in ${fmtDur(left)}</div>`;
  } else if (step.waitFor && step.attemptCount === 0) {
    bar = `<div class="small muted">⏱ waits ${esc(step.waitFor)}</div>`;
  }
  const out = Object.keys(step.output ?? {}).length
    ? `<div class="output" title="output passed to later steps">→ ${esc(Object.entries(step.output).map(([k, v]) => `${k}=${v}`).join(', '))}</div>` : '';
  const label = step.kind === 'COMPENSATION' ? `undo ${step.name}` : step.name;
  return `<div class="step-card ${state.stepId === step.stepExecutionId ? 'selected' : ''} ${current ? 'current' : ''} ${step.waitFor ? 'timer' : ''}"
      style="--c:${RUN_STATUS_COLOR[step.status]}" data-step="${step.stepExecutionId}">
    <div class="name">${esc(label)}</div>
    <div class="type">${esc(step.type)}</div>
    <div class="meta">${pill(step.status, step.status === 'RUNNING' ? 'pulse' : '')}
      <span class="attempt-dots">${dots}</span><span class="small muted">${step.attemptCount}/${step.maxAttempts}</span></div>
    ${bar}${out}
  </div>`;
}

/** Forward steps grouped by dependency depth: every step in a column can run at the same time. */
function forwardColumns(run) {
  const forward = run.steps.filter((s) => s.kind !== 'COMPENSATION');
  const byName = new Map(forward.map((s) => [s.name, s]));
  const depth = new Map();
  const depthOf = (s) => {
    if (!depth.has(s.name)) {
      const deps = (s.dependsOn ?? []).map((n) => byName.get(n)).filter(Boolean);
      depth.set(s.name, deps.length ? 1 + Math.max(...deps.map(depthOf)) : 0);
    }
    return depth.get(s.name);
  };
  const columns = [];
  forward.forEach((s) => { const d = depthOf(s); (columns[d] ||= []).push(s); });
  return columns.filter(Boolean);
}

function renderLanes(run) {
  const comp = run.steps.filter((s) => s.kind === 'COMPENSATION');
  const columns = forwardColumns(run);
  const forwardLane = `<div class="lane"><div class="lane-label">forward</div>
    ${columns.map((col, i) => (i ? '<div class="arrow">→</div>' : '')
      + `<div style="display:flex;flex-direction:column;gap:8px;justify-content:center">${col.map((s) => stepCard(s, run)).join('')}</div>`).join('')}</div>`;
  const undoLane = comp.length ? `<div class="lane"><div class="lane-label">undo</div>
    ${comp.map((s, i) => (i ? '<div class="arrow">→</div>' : '') + stepCard(s, run)).join('')}</div>` : '';
  $('#lanes').innerHTML = forwardLane + undoLane;
  if (!state.stepId) {
    const pick = run.steps.find((s) => s.status === 'RUNNING' || s.status === 'RETRY_WAIT')
      || [...run.steps].reverse().find((s) => s.attemptCount > 0) || run.steps[0];
    state.stepId = pick?.stepExecutionId;
    $$('.step-card').forEach((c) => c.classList.toggle('selected', c.dataset.step === state.stepId));
  }
}

function renderStepDrawer() {
  const drawer = $('#step-drawer');
  const step = state.run?.steps.find((s) => s.stepExecutionId === state.stepId);
  drawer.hidden = !step;
  if (!step) return;
  const visited = new Set(['PENDING', step.status]);
  step.attempts.forEach((a) => {
    visited.add('RUNNING');
    if (a.status === 'RETRYABLE_FAILURE' || a.status === 'TIMED_OUT') visited.add('RETRY_WAIT');
  });
  const S = { PENDING: [10, 50], RUNNING: [150, 50], RETRY_WAIT: [150, 120], COMPLETED: [300, 15], FAILED: [300, 85] };
  const T = [['PENDING', 'RUNNING'], ['RUNNING', 'COMPLETED'], ['RUNNING', 'FAILED'], ['RUNNING', 'RETRY_WAIT'], ['RETRY_WAIT', 'RUNNING']];
  const W = 110; const H = 28;
  const pos = (n) => S[n];
  const edges = T.map(([a, b]) => {
    const [x1, y1] = pos(a); const [x2, y2] = pos(b);
    let d;
    if (a === 'RUNNING' && b === 'RETRY_WAIT') d = `M${x1 + 40} ${y1 + H} L${x2 + 40} ${y2}`;
    else if (a === 'RETRY_WAIT' && b === 'RUNNING') d = `M${x1 + 70} ${y1} L${x2 + 70} ${y2 + H}`;
    else d = `M${x1 + W} ${y1 + H / 2} C${(x1 + W + x2) / 2} ${y1 + H / 2} ${(x1 + W + x2) / 2} ${y2 + H / 2} ${x2 - 2} ${y2 + H / 2}`;
    const takenEdge = visited.has(a) && visited.has(b) && !(a === 'RUNNING' && b === 'FAILED' && step.status !== 'FAILED')
      && !(a === 'RUNNING' && b === 'COMPLETED' && step.status !== 'COMPLETED');
    return `<path class="tr ${takenEdge ? 'taken' : ''}" d="${d}" marker-end="url(#st-arrow)"/>`;
  }).join('');
  const attempts = step.attempts.map((a) => {
    const cmd = state.commands.find((c) => c.stepExecutionId === step.stepExecutionId && c.attempt === a.attempt);
    return `<tr><td>${a.attempt}</td><td>${pill(a.status)}</td><td class="mono small">${fmtClock(a.startedAt)}</td>
      <td class="mono small">${cmd?.publishedAt ? `+${fmtDur(ms(cmd.publishedAt) - ms(a.startedAt))}` : '—'}</td>
      <td class="mono small">${a.finishedAt ? fmtDur(ms(a.finishedAt) - ms(a.startedAt)) : '<span class="muted">open</span>'}</td>
      <td class="small">${esc(a.errorMessage || '')}<div class="faint">${esc(ATTEMPT_STATES[a.status] || '')}</div></td></tr>`;
  }).join('');
  drawer.innerHTML = `
    <div class="grid two">
      <div>
        <div class="row"><h3>${esc(step.kind === 'COMPENSATION' ? `undo ${step.name}` : step.name)}</h3>${pill(step.status)}
          <span class="small muted mono">${esc(step.type)}</span></div>
        <p class="small">${esc(STEP_STATES[step.status] || '')}</p>
        ${step.waitFor ? `<p class="small">A durable timer of <b>${fmtDur(parseIsoDuration(step.waitFor))}</b>: due ${step.nextAttemptAt ? fmtClock(step.nextAttemptAt) : 'once its dependencies complete'}. The orchestrator completes it itself — no worker, no Kafka message — and because the due time is stored, it survives restarts.</p>` : ''}
        ${Object.keys(step.output ?? {}).length ? `<h4>Output (handed to later steps)</h4><pre>${esc(JSON.stringify(step.output, null, 2))}</pre>` : ''}
        <p class="small muted">Step execution id <code>${esc(step.stepExecutionId)}</code> — the same for every attempt, which is why
          a handler can pass it to an external service as an idempotency key. Attempts used: ${step.attemptCount} of ${step.maxAttempts}.</p>
      </div>
      <div>
        <h4>Step state machine</h4>
        <svg class="statemachine" viewBox="0 0 420 160">
          <defs><marker id="st-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M0 0 L10 5 L0 10 z"/></marker></defs>
          ${edges}
          ${Object.entries(S).map(([n, [x, y]]) => `<g class="st ${visited.has(n) ? 'visited' : ''} ${step.status === n ? 'now' : ''}" style="--c:${RUN_STATUS_COLOR[n]}">
            <rect x="${x}" y="${y}" width="${W}" height="${H}" rx="14"/><text x="${x + W / 2}" y="${y + 18}" text-anchor="middle">${n}</text>
            <title>${esc(STEP_STATES[n])}</title></g>`).join('')}
        </svg>
      </div>
    </div>
    <div class="table-wrap" style="margin-top:8px">
      <table><thead><tr><th>#</th><th>Outcome</th><th>Claimed at</th><th>Sent after</th><th>Took</th><th>Error / meaning</th></tr></thead>
      <tbody>${attempts || '<tr><td colspan="6" class="empty">Not attempted yet — waiting for its turn.</td></tr>'}</tbody></table>
    </div>`;
}

function buildTimeline(run) {
  const events = [];
  const add = (t, kind, title, detail, why, color) => events.push({ t: ms(t), iso: t, kind, title, detail, why, color });
  add(run.createdAt, 'created', 'Run created, first step made eligible', `input: ${scenarioOf(run.input, run.workflow)}`, TIMELINE_WHY.created, 'var(--accent)');
  const firstComp = run.steps.find((s) => s.kind === 'COMPENSATION');
  const forwardByName = new Map(run.steps.filter((s) => s.kind !== 'COMPENSATION').map((s) => [s.name, s]));
  const succeededAt = (s) => ms(s?.attempts.find((x) => x.status === 'SUCCEEDED')?.finishedAt);
  // When this attempt became claimable: its dependencies' successes, the previous attempt, or the previous undo step.
  const readyAt = (step, a) => {
    if (a.attempt > 1) return ms(step.attempts.find((p) => p.attempt === a.attempt - 1)?.finishedAt);
    const index = run.steps.indexOf(step);
    if (step.kind === 'COMPENSATION') {
      const prev = run.steps[index - 1];
      if (prev?.kind === 'COMPENSATION') return succeededAt(prev);
      return Math.max(...run.steps.filter((s) => s.kind !== 'COMPENSATION')
        .flatMap((s) => s.attempts.map((x) => ms(x.finishedAt) || 0)));
    }
    const deps = (step.dependsOn ?? []).map((n) => forwardByName.get(n));
    return deps.length ? Math.max(...deps.map(succeededAt)) : ms(run.createdAt);
  };
  const steps = [...run.steps].sort((a, b) => {
    const at = a.attempts[0]?.startedAt; const bt = b.attempts[0]?.startedAt;
    return (at ? ms(at) : Infinity) - (bt ? ms(bt) : Infinity);
  });
  let compensationMarked = false;
  for (const step of steps) {
    const label = step.kind === 'COMPENSATION' ? `undo ${step.name}` : step.name;
    if (step.kind === 'COMPENSATION' && !compensationMarked && firstComp) {
      compensationMarked = true;
      const failedFwd = run.steps.find((s) => s.kind !== 'COMPENSATION' && s.status === 'FAILED');
      const at = failedFwd?.attempts.at(-1)?.finishedAt || step.attempts[0]?.startedAt;
      if (at) {
        const undos = run.steps.filter((s) => s.kind === 'COMPENSATION').map((s) => s.type).join(' → ');
        add(at, 'compensating', 'Run starts compensating', `undo steps appended, most recent first: ${undos}`, TIMELINE_WHY.compensating, 'var(--s-compensating)');
      }
    }
    for (const a of step.attempts) {
      const cmd = state.commands.find((c) => c.stepExecutionId === step.stepExecutionId && c.attempt === a.attempt);
      const waited = ms(a.startedAt) - readyAt(step, a);
      const previous = step.attempts.find((p) => p.attempt === a.attempt - 1);
      const backoff = previous && ['RETRYABLE_FAILURE', 'TIMED_OUT'].includes(previous.status) ? retryBackoff(previous.attempt) : null;
      const waitText = backoff != null
        ? `waited ${fmtDur(waited)}: ${fmtDur(backoff)} retry backoff after attempt ${previous.attempt}, then the next dispatch tick`
        : `ready → claimed in ${fmtDur(waited)}${state.config?.wakeup === false ? ` (the dispatcher polls every ${state.config?.dispatchIntervalMs ?? '?'} ms)` : ''}`;
      add(a.startedAt, 'claimed', `Claimed ${label} — attempt ${a.attempt} of ${step.maxAttempts}`,
        `${waitText}${cmd?.deadline ? ` · lease until ${fmtClock(cmd.deadline)}` : ''}`,
        TIMELINE_WHY.claimed.replace('{attempt}', a.attempt), 'var(--s-running)');
      if (step.waitFor) continue; // a timer is completed by the orchestrator itself: no command, no worker
      if (cmd?.publishedAt) {
        add(cmd.publishedAt, 'published', `Command for ${label} sent to Kafka`,
          `${fmtDur(ms(cmd.publishedAt) - ms(a.startedAt))} after the claim${cmd.publishAttempts > 1 ? ` · ${cmd.publishAttempts} send attempts` : ''}`,
          TIMELINE_WHY.published, 'var(--s-retry)');
      }
      if (a.finishedAt) {
        let kind = { SUCCEEDED: 'succeeded', RETRYABLE_FAILURE: 'retryable', PERMANENT_FAILURE: 'permanent', TIMED_OUT: 'timedout' }[a.status];
        const lastAllowed = a.attempt >= step.maxAttempts && step.status === 'FAILED';
        if (kind === 'retryable' && lastAllowed) kind = 'exhausted';
        const titles = {
          succeeded: `${label} succeeded`,
          retryable: `${label} failed — will retry after ${fmtDur(retryBackoff(a.attempt))}`,
          exhausted: `${label} failed — out of attempts (${a.attempt}/${step.maxAttempts}), step FAILED`,
          permanent: `${label} failed permanently`,
          timedout: `${label} timed out (lease expired)${lastAllowed ? ' — out of attempts, step FAILED' : ''}`,
        };
        const sincePublish = cmd?.publishedAt ? ` · worker + Kafka round trip ${fmtDur(ms(a.finishedAt) - ms(cmd.publishedAt))}` : '';
        add(a.finishedAt, kind, titles[kind], `${a.errorMessage ? `“${a.errorMessage}”` : 'result applied'}${sincePublish}`,
          TIMELINE_WHY[kind], RUN_STATUS_COLOR[a.status]);
      } else {
        add(new Date(now()).toISOString(), 'inflight', `${label} attempt ${a.attempt} is with a worker`, 'waiting for its result…', TIMELINE_WHY.inflight, 'var(--s-running)');
      }
    }
    if (step.status === 'RETRY_WAIT' && step.nextAttemptAt) {
      add(step.nextAttemptAt, 'waiting', `${label} eligible again`, `backoff ends ${ms(step.nextAttemptAt) > now() ? `in ${fmtDur(ms(step.nextAttemptAt) - now())}` : 'now'}`,
        TIMELINE_WHY.waiting, 'var(--s-retry)');
    }
  }
  for (const action of run.operatorActions ?? []) {
    add(action.performedAt, 'operator', `Operator ${action.action === 'CANCEL' ? 'cancelled the run' : 'retried the failed compensation'} (${action.actor})`,
      action.reason ? `"${action.reason}"` : `run was ${action.runStatusBefore}`, TIMELINE_WHY[action.action === 'CANCEL' ? 'cancelled' : 'retried'] ?? '', 'var(--accent)');
  }
  if (run.completedAt) add(run.completedAt, 'finished', `Run ${run.status}`, RUN_STATES[run.status], TIMELINE_WHY.finished, RUN_STATUS_COLOR[run.status]);
  return events.sort((a, b) => a.t - b.t);
}

function renderTimeline(run) {
  const open = new Set($$('#timeline .tl-item.open').map((e) => e.dataset.key));
  const events = buildTimeline(run);
  const start = ms(run.createdAt);
  $('#timeline').innerHTML = events.map((e, i) => {
    const key = `${e.kind}-${e.title}`;
    return `<li class="tl-item ${open.has(key) ? 'open' : ''}" data-key="${esc(key)}">
      <div class="tl-time" title="${fmtClock(e.iso)}">+${fmtDur(e.t - start)}</div>
      <div class="tl-dot" style="--c:${e.color}"></div>
      <div class="tl-main">
        <div class="tl-title">${esc(e.title)} <button class="tl-toggle" data-tl="${i}">why?</button></div>
        ${e.detail ? `<div class="tl-gap">${esc(e.detail)}</div>` : ''}
        <div class="tl-why">${e.why}</div>
      </div></li>`;
  }).join('');
}

// ---------------------------------------------------------------- engine view
function renderEngine() {
  const s = state.stats; const r = state.rates;
  if (!s) return;
  $('#counter-instance').textContent = `instance ${s.instance}`;
  const counters = [
    ['Steps claimed', s.counters.stepsClaimed, r.claimed, 'StepStateService.claim()'],
    ['Commands published', s.counters.outboxPublished, r.published, 'OutboxPublisher'],
    ['Results applied', s.counters.resultsApplied, r.applied, 'StepResultListener'],
    ['Stale results rejected', s.counters.resultsStale, r.stale, 'duplicates / late results'],
    ['Leases expired', s.counters.leasesExpired, r.expired, 'recovery sweeper'],
    ['Publish failures', s.counters.outboxFailures, r.failures, 'retried next tick'],
  ];
  $('#counters').innerHTML = counters.map(([label, v, rate, note]) => `
    <div class="stat" title="${esc(note)}"><div class="label">${label}</div><div class="value">${v}</div>
      <div class="rate">${rate > 0.05 ? `${rate.toFixed(1)}/s` : '&nbsp;'}</div></div>`).join('');

  const lease = parseIsoDuration(state.config?.leaseDuration) || 60000;
  const db = s.database;
  $('#leases').innerHTML = db.leased.length ? db.leased.map((x) => {
    const left = Math.max(0, ms(x.leaseExpiresAt) - now());
    return `<div class="bar-row"><a href="#" data-open-run="${x.runId}">${esc(x.kind === 'COMPENSATION' ? 'undo ' : '')}${esc(x.stepName)} #${x.attempt}</a>
      <div class="bar-track"><div class="bar-fill" style="--c:var(--s-running);width:${(left / lease) * 100}%"></div></div>
      <span class="mono small">${fmtDur(left)}</span></div>`;
  }).join('') : '<p class="small muted">Nothing running right now.</p>';
  $('#waiting').innerHTML = db.waiting.length ? db.waiting.map((x) => {
    const left = ms(x.nextAttemptAt) - now();
    return `<div class="bar-row"><a href="#" data-open-run="${x.runId}">${esc(x.kind === 'COMPENSATION' ? 'undo ' : '')}${esc(x.stepName)}</a>
      <span class="small">${pill(x.status)} <span class="muted">attempt ${x.attempt}/${x.maxAttempts}</span></span>
      <span class="mono small">${left > 0 ? fmtDur(left) : 'now'}</span></div>`;
  }).join('') : '<p class="small muted">No step is waiting to be claimed.</p>';

  const bars = (title, counts, colorOf, order) => {
    const keys = order.filter((k) => counts[k] != null).concat(Object.keys(counts).filter((k) => !order.includes(k)));
    const max = Math.max(1, ...Object.values(counts));
    return `<h4 style="margin-top:6px">${title}</h4><div class="bars">${keys.length ? keys.map((k) => `
      <div class="bar-row"><span>${pill(k)}</span><div class="bar-track"><div class="bar-fill" style="--c:${colorOf(k)};width:${(counts[k] / max) * 100}%"></div></div>
      <span class="mono small">${counts[k]}</span></div>`).join('') : '<p class="small muted">none</p>'}</div>`;
  };
  const color = (k) => RUN_STATUS_COLOR[k] || 'var(--accent)';
  $('#db-stats').innerHTML = `
    <p class="explain">Live counts straight from the tables, across all orchestrator instances.
      Outbox: <b>${db.outboxPending}</b> pending, ${db.outboxPublished} published${db.oldestPendingCommand ? `, oldest waiting ${fmtDur(now() - ms(db.oldestPendingCommand))}` : ''}.</p>
    ${bars('Runs (workflow_run)', db.runs, color, ['RUNNING', 'COMPENSATING', 'COMPLETED', 'COMPENSATED', 'FAILED', 'COMPENSATION_FAILED'])}
    ${bars('Steps (step_execution)', db.steps, color, ['PENDING', 'RUNNING', 'RETRY_WAIT', 'COMPLETED', 'FAILED'])}
    ${bars('Attempts (step_attempt)', db.attempts, color, ['RUNNING', 'SUCCEEDED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE', 'TIMED_OUT'])}`;
}

function renderInvariants() {
  const report = state.consistency;
  const broken = new Map((report?.violations || []).map((v) => [v.invariant, v]));
  $('#consistency-badge').innerHTML = report
    ? (report.consistent ? '<span class="pill c-COMPLETED">ALL HOLD</span>' : `<span class="pill c-FAILED">${broken.size} BROKEN</span>`)
    : '';
  $('#invariants').innerHTML = state.invariants.map((inv) => {
    const v = broken.get(inv.name);
    return `<div class="invariant"><div class="check ${v ? 'bad' : 'ok'}">${v ? '✗' : '✓'}</div>
      <div><code>${esc(inv.name)}</code><div class="small">${esc(inv.description)}</div>
      ${v ? `<div class="small" style="color:var(--s-failed)">${v.count} offending rows, e.g. ${v.sample.map(esc).join(', ')}</div>` : ''}</div></div>`;
  }).join('') + (report ? `<p class="small faint" style="margin-top:8px">checked ${ago(report.checkedAt)}</p>` : '');
}

function renderConfig() {
  const c = state.config;
  if (!c) return;
  const lease = parseIsoDuration(c.leaseDuration);
  const init = parseIsoDuration(c.initialBackoff); const max = parseIsoDuration(c.maxBackoff);
  const schedule = [1, 2, 3, 4, 5].map((n) => fmtDur(Math.min(init * c.backoffMultiplier ** (n - 1), max))).join(', ');
  const items = [
    ['Instant dispatch (wake-up)', c.wakeup ? 'on' : 'off', 'Claim a step the moment it becomes ready (after the transaction that made it ready commits), and send its command as soon as it is claimed, instead of waiting for the next tick. EngineWakeup.'],
    ['Dispatch interval', `${c.dispatchIntervalMs} ms`, c.wakeup ? 'The safety-net poll: catches anything a wake-up missed, e.g. after a crash between commit and wake-up.' : 'How often each orchestrator looks for ready steps — most of a step\'s waiting time at low load.'],
    ['Outbox interval', `${c.outboxIntervalMs} ms`, 'How often pending commands are sent to Kafka.'],
    ['Recovery interval', `${c.recoveryIntervalMs} ms`, 'How often expired leases are looked for.'],
    ['Batch size', c.batchSize, 'Steps claimed (and outbox rows sent) per transaction.'],
    ['Lease duration', fmtDur(lease), 'How long an attempt may take before it is presumed lost. Must exceed the slowest step plus time queued in Kafka.'],
    ['Retry backoff', `${fmtDur(init)} × ${c.backoffMultiplier}, max ${fmtDur(max)}`, `Waits after failures 1–5: ${schedule}.`],
    ['Kafka partitions', c.partitions, 'Upper bound on parallel workers: one thread per partition at a time.'],
    ['Result listener threads', c.resultListenerThreads, 'Consumers of step-results in this instance.'],
    ['Background loops', c.schedulerEnabled ? 'enabled' : 'disabled', 'Off in the integration tests, which drive the loops themselves.'],
  ];
  $('#config').innerHTML = items.map(([k, v, text]) => `<div class="config-item"><div class="row"><span class="k">${k}</span><span class="spacer"></span><span class="v">${esc(v)}</span></div>
    <div class="small muted">${text}</div></div>`).join('');
}

// ---------------------------------------------------------------- payments
function renderPayments() {
  const p = state.payments;
  const box = $('#payments');
  if (!p) { box.innerHTML = '<p class="small muted">Loading…</p>'; return; }
  if (!p.configured) {
    box.innerHTML = `<p class="small muted">Not connected: start <code>flowforge-payments</code> and give the orchestrator
      <code>--flowforge.insight.payments-url=http://localhost:8084</code> (and the workers <code>--flowforge.worker.payments-url=…</code>).
      Without it, workers simulate payments in memory.</p>`;
    return;
  }
  if (!p.reachable) { box.innerHTML = `<p class="small" style="color:var(--s-failed)">Provider at ${esc(p.url)} unreachable: ${esc(p.error)}</p>`; return; }
  const s = p.summary;
  const stats = [
    ['Charges', s.charges, ''], ['Captured', s.captured, ''], ['Declined', s.declined, ''], ['Refunded', s.refunded, ''],
    ['Duplicates absorbed', s.duplicateRequestsAbsorbed, 'requests that repeated a key and changed nothing'],
    ['Orders charged twice', s.ordersChargedMoreThanOnce, 'must be 0'],
  ];
  box.innerHTML = `<div class="stat-grid">${stats.map(([label, v, note]) => `<div class="stat" title="${esc(note)}">
      <div class="label">${label}</div><div class="value" style="${label === 'Orders charged twice' ? `color:${v ? 'var(--s-failed)' : 'var(--s-completed)'}` : ''}">${v}</div></div>`).join('')}</div>
    <p class="small muted" style="margin-top:8px">Captured total ${esc(s.capturedAmount)} · provider at <code>${esc(p.url)}</code></p>`;
}
async function pollPayments() {
  try { state.payments = await api('/api/admin/payments'); } catch { state.payments = null; }
  if (state.view === 'engine') renderPayments();
}
async function loadRunPayments(run) {
  const orderId = run.input?.orderId;
  if (!orderId) { state.runPayments = null; return; }
  try { state.runPayments = await api(`/api/admin/payments?orderId=${encodeURIComponent(orderId)}`); } catch { state.runPayments = null; }
}
function runPaymentsText() {
  const p = state.runPayments;
  if (!p) return '<span class="muted">—</span>';
  if (!p.configured) return '<span class="muted">simulated in the worker (provider not connected)</span>';
  if (!p.reachable) return '<span class="muted">provider unreachable</span>';
  const charges = p.charges || [];
  if (!charges.length) return '<span class="muted">no charge for this order</span>';
  return charges.map((c) => `${pill(c.status)} ${esc(c.amount)} · ${c.requests} request${c.requests === 1 ? '' : 's'}${c.requests > 1
    ? ` <span style="color:var(--s-completed)">(${c.requests - 1} duplicate${c.requests > 2 ? 's' : ''} absorbed — charged once)</span>` : ''}
    <div class="small faint mono">key ${esc(c.idempotencyKey)}</div>`).join('');
}

// ---------------------------------------------------------------- kafka view
function renderKafka() {
  const k = state.kafka;
  $('#kafka-error').innerHTML = k && !k.reachable
    ? `<p style="color:var(--s-failed)"><b>Kafka is unreachable:</b> ${esc(k.error)}. Commands stay safely in the outbox until it is back.</p>` : '';
  if (!k?.reachable) { $('#kafka-groups').innerHTML = ''; return; }
  $('#kafka-groups').innerHTML = k.groups.map((g) => {
    const topic = k.topics.find((t) => t.name === g.topic);
    const colorOf = new Map(g.members.map((m, i) => [m.clientId, MEMBER_COLORS[i % MEMBER_COLORS.length]]));
    const who = g.topic === 'flowforge.step-commands' ? 'workers' : 'orchestrators';
    return `<div class="panel"><div class="panel-head"><h2>${esc(g.topic)}</h2>
        <span class="hint">read by group <code>${esc(g.groupId)}</code> (${who}) · ${esc(g.state)}</span></div>
      <div class="panel-body">
        <div class="row small muted" style="margin-bottom:8px">${topic?.messages ?? 0} messages written · total lag <b style="color:${g.lag > 0 ? 'var(--s-retry)' : 'inherit'}">${g.lag}</b></div>
        <h4>Members</h4>
        ${g.members.length ? g.members.map((m) => `<div class="member"><span class="swatch" style="--c:${colorOf.get(m.clientId)}"></span>
          <code>${esc(m.clientId)}</code><span class="small muted">${esc(m.host)}</span><span class="spacer"></span>
          <span class="small">partitions ${m.partitions.join(', ') || '—'}</span></div>`).join('')
          : `<p class="small" style="color:var(--s-failed)">No live member: nobody is reading this topic. ${who === 'workers' ? 'Start a worker.' : ''}</p>`}
        <h4 style="margin-top:10px">Partitions</h4>
        <div class="partitions">${g.partitions.map((p) => `<div class="partition" style="--c:${p.owner ? colorOf.get(p.owner) : 'var(--line)'}">
          <div class="p">#${p.partition}</div><div class="o">committed ${p.committed} / ${p.endOffset}</div>
          <div class="lag ${p.lag > 0 ? 'hot' : ''}">lag ${p.lag}</div></div>`).join('')}</div>
      </div></div>`;
  }).join('');
}

function renderCrashGuide() {
  $('#crash-guide').innerHTML = `
    <ol>
      <li>Click <b>Slow shipment</b> on the Live tab. Its <i>create-shipment</i> step will take 8 s.</li>
      <li>Within those 8 s, kill the worker that runs it. Locally (PowerShell, in the project folder):
        <pre>Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object CommandLine -match 'flowforge-worker' |
  Select-Object -First 1 | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }</pre>
        On Kubernetes: <code>kubectl delete pod -n flowforge -l app.kubernetes.io/name=worker --force --grace-period=0</code> (one pod is enough).</li>
      <li>Watch this tab: within ~10 s the dead member disappears and its partitions are reassigned. The command it never acknowledged is
        delivered again, and <i>create-shipment</i> runs a second time on another worker — same attempt number, same idempotency key.</li>
      <li>If no worker is left at all, nothing happens until the lease (${fmtDur(parseIsoDuration(state.config?.leaseDuration) || 60000)}) expires; then the recovery sweeper times
        the attempt out and retries it as soon as a worker returns. Start a worker again with:
        <pre>java -jar flowforge-worker/target/flowforge-worker-0.1.0-SNAPSHOT.jar --server.port=8082</pre></li>
      <li>Open the run: its timeline shows the gap, and the consistency check still passes.</li>
    </ol>`;
}

// ---------------------------------------------------------------- learn view
function renderLearn() {
  $('#journey').innerHTML = JOURNEY.map(([title, text]) => `<li><b>${title}</b><div class="small">${text}</div></li>`).join('');
  $('#failures').innerHTML = FAILURES.map(([f, m, t]) => `<tr><td><b>${f}</b></td><td class="small">${m}</td><td class="small muted">${t}</td></tr>`).join('');
  $('#guarantees').innerHTML = `
    <h4>Guaranteed</h4><ul>${GUARANTEES.yes.map((g) => `<li class="small">${g}</li>`).join('')}</ul>
    <h4 style="margin-top:10px">Not guaranteed (and why that is honest)</h4><ul>${GUARANTEES.no.map((g) => `<li class="small">${g}</li>`).join('')}</ul>`;
  $('#glossary').innerHTML = GLOSSARY.map(([t, d]) => `<div class="term"><b>${t}</b><span class="small muted">${d}</span></div>`).join('');
  renderCodeMap('');
}

function renderCodeMap(query) {
  const q = query.trim().toLowerCase();
  const hit = (text) => !q || text.toLowerCase().includes(q);
  const mark = (text) => (q ? esc(text).replace(new RegExp(q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi'), (m) => `<mark>${m}</mark>`) : esc(text));
  const html = CODE_MAP.map((p) => {
    const classes = p.classes.filter(([name, kind, does, methods]) => hit([name, kind, does, ...methods.flat()].join(' ')) || hit(p.pkg) || hit(p.module));
    if (!classes.length) return '';
    return `<div class="pkg"><div class="pkg-head"><h3>${esc(p.module)} / <code>${esc(p.pkg)}</code></h3><span class="small muted">${esc(p.text)}</span></div>
      <div class="class-grid">${classes.map(([name, kind, does, methods]) => `<div class="class-card">
        <span class="cname">${mark(name)}</span><span class="kind">${esc(kind)}</span>
        <div class="small">${mark(does)}</div>
        ${methods.length ? `<ul>${methods.map(([sig, text]) => `<li><code>${mark(sig)}</code>${text ? ` <span class="muted">— ${mark(text)}</span>` : ''}</li>`).join('')}</ul>` : ''}
      </div>`).join('')}</div></div>`;
  }).join('');
  $('#code-map').innerHTML = html || '<p class="muted">Nothing matches.</p>';
}

// ---------------------------------------------------------------- tour
const TOUR = [
  { view: 'live', target: '#tour-scenarios', title: 'Start an order', text: 'Each button is a different input to the same workflow. Start with <b>Normal order</b>, then try the failures. The API answers immediately; the work happens in the background.' },
  { view: 'live', target: '#tour-diagram', title: 'How a step travels', text: 'Every step attempt goes round this loop. Edges light up with live rates while work flows. <b>Click any box</b> for what it does and the exact classes and methods behind it.' },
  { view: 'live', target: '#tour-runs', title: 'Recent runs', text: 'Every order, newest first, with its status and where it is now. Click one to open it.' },
  { view: 'run', target: '#tour-pipeline', title: 'A run, step by step', text: 'Forward steps on top, undo steps below if the run compensated. Dots are attempts; a running step shows its lease counting down. Click a step for its attempts and state machine.' },
  { view: 'run', target: '#tour-timeline', title: 'What happened, and why', text: 'The run\'s history rebuilt from the database: claimed, sent, result applied, retried, compensated. Each event explains which code did it.' },
  { view: 'engine', target: '#tour-leases', title: 'Leases and retries', text: 'What the engine is waiting on right now: attempts that hold a lease, and steps backing off before a retry. Counters, database totals, the 10 invariants and the live configuration are on this tab too.' },
  { view: 'kafka', target: '#tour-crash', title: 'Break something', text: 'Kafka partitions, which worker owns which, and lag. Follow these steps to kill a worker mid-step and watch the system recover.' },
  { view: 'learn', target: '#journey', title: 'Learn', text: 'The life of one order, every failure mode, the guarantees, a map of every class, and a glossary. Guide mode (top right) shows explanation boxes everywhere; switch it off once you know your way around.' },
];
let tourIndex = -1;

function tourShow(i) {
  tourEnd(false);
  tourIndex = i;
  const step = TOUR[i];
  if (step.view === 'run' && !state.run && state.runs[0]) openRun(state.runs[0].runId, { switchView: false });
  showView(step.view);
  // A timer rather than requestAnimationFrame, which never fires while the tab is in the background.
  setTimeout(() => {
    let target = $(step.target);
    if (!target || target.offsetParent === null) target = $(`#view-${step.view}`);
    target.scrollIntoView({ block: 'center', behavior: 'instant' in window ? 'instant' : 'auto' });
    const r = target.getBoundingClientRect();
    const ring = document.createElement('div');
    ring.className = 'tour-ring';
    Object.assign(ring.style, { left: `${r.left + scrollX - 6}px`, top: `${r.top + scrollY - 6}px`, width: `${r.width + 12}px`, height: `${Math.min(r.height, innerHeight - 40) + 12}px` });
    const card = document.createElement('div');
    card.className = 'tour-card';
    card.innerHTML = `<div class="step-no">${i + 1} / ${TOUR.length}</div><h3>${step.title}</h3><div class="small">${step.text}</div>
      <div class="row"><button class="btn" data-tour="end">Close</button><span class="spacer"></span>
      ${i ? '<button class="btn" data-tour="prev">Back</button>' : ''}
      <button class="btn primary" data-tour="${i === TOUR.length - 1 ? 'end' : 'next'}">${i === TOUR.length - 1 ? 'Done' : 'Next'}</button></div>`;
    document.body.append(ring, card);
    const below = r.top + Math.min(r.height, innerHeight - 40) + 20;
    const top = below + 200 < innerHeight ? below : Math.max(12, r.top - 220);
    Object.assign(card.style, { left: `${Math.min(Math.max(12, r.left), innerWidth - 380) + scrollX}px`, top: `${top + scrollY}px` });
  }, 30);
}
function tourEnd() {
  $$('.tour-ring, .tour-card').forEach((e) => e.remove());
}

// ---------------------------------------------------------------- views and polling
function showView(view) {
  state.view = view;
  $$('.view').forEach((v) => v.classList.toggle('active', v.id === `view-${view}`));
  $$('.tab').forEach((t) => t.classList.toggle('active', t.dataset.view === view));
  if (view === 'run') refreshRun();
  if (view === 'kafka') { renderKafka(); renderCrashGuide(); }
  if (view === 'engine') { renderEngine(); renderInvariants(); renderConfig(); renderPayments(); }
}

function setConnected(ok, text) {
  state.connected = ok;
  $('#conn-dot').className = `dot ${ok ? 'ok' : 'bad'}`;
  $('#conn-text').textContent = text;
}

async function pollStats() {
  try {
    const [stats, runs] = await Promise.all([
      api('/api/admin/stats'), api(`/api/runs?limit=40${$('#run-filter').value ? `&status=${$('#run-filter').value}` : ''}`)]);
    state.clockOffset = ms(stats.now) - Date.now();
    if (state.stats) {
      const dt = Math.max(0.2, (ms(stats.now) - ms(state.stats.now)) / 1000);
      const d = (k) => Math.max(0, stats.counters[k] - state.stats.counters[k]) / dt;
      const newRuns = runs.filter((r) => !state.runs.some((o) => o.runId === r.runId)).length;
      state.rates = {
        claimed: d('stepsClaimed'), published: d('outboxPublished'), applied: d('resultsApplied'),
        stale: d('resultsStale'), expired: d('leasesExpired'), failures: d('outboxFailures'), runs: newRuns / dt,
      };
    }
    state.stats = stats; state.runs = runs;
    if (!state.config) { state.config = await api('/api/admin/config'); renderConfig(); fillConfig(); renderComponent(state.selectedComponent); }
    setConnected(true, `connected · ${stats.instance}`);
    renderRuns(); updateDiagram();
    if (state.view === 'engine') renderEngine();
    if (state.runId && (state.view === 'run' || !state.run)) {
      const summary = runs.find((r) => r.runId === state.runId);
      if (!state.run || ['RUNNING', 'COMPENSATING'].includes(state.run.status) || (summary && summary.status !== state.run.status)) await refreshRun();
      else if (state.view === 'run') renderRun(); // keep countdowns moving
    }
  } catch (e) {
    setConnected(false, 'orchestrator unreachable');
  }
}
async function pollKafka() {
  try { state.kafka = await api('/api/admin/kafka'); } catch { state.kafka = null; }
  updateDiagram();
  if (state.view === 'kafka') renderKafka();
}
async function pollConsistency() {
  try {
    if (!state.invariants.length) state.invariants = await api('/api/admin/consistency/invariants');
    state.consistency = await api('/api/admin/consistency');
  } catch { /* shown as stale */ }
  updateDiagram();
  if (state.view === 'engine') renderInvariants();
}
function pollFast() { setTimeout(pollStats, 150); setTimeout(pollStats, 600); }

// ---------------------------------------------------------------- events
function wire() {
  $('#tabs').addEventListener('click', (e) => { const t = e.target.closest('.tab'); if (t) showView(t.dataset.view); });
  document.addEventListener('click', async (e) => {
    const run = e.target.closest('[data-open-run]');
    if (run) { e.preventDefault(); openRun(run.dataset.openRun); return; }
    const go = e.target.closest('[data-goto]');
    if (go) { e.preventDefault(); showView(go.dataset.goto); return; }
    const tour = e.target.closest('[data-tour]');
    if (tour) {
      const action = tour.dataset.tour;
      if (action === 'next') tourShow(tourIndex + 1); else if (action === 'prev') tourShow(tourIndex - 1); else tourEnd();
      return;
    }
    const scenario = e.target.closest('[data-scenario]');
    if (scenario) {
      const s = SCENARIOS.find((x) => x.id === scenario.dataset.scenario);
      try {
        const created = await startOrder(orderInput(s.input), { workflow: s.workflow ?? WORKFLOW });
        if (s.workflow) openRun(created.runId);
      } catch (err) { toast(`Could not start: ${esc(err.message)}`); }
      return;
    }
    const row = e.target.closest('tr[data-run]');
    if (row) { openRun(row.dataset.run); return; }
    const chip = e.target.closest('[data-chip]');
    if (chip) { $('#run-filter').value = chip.dataset.chip; pollStats(); return; }
    const wf = e.target.closest('[data-wf-step]');
    if (wf) { state.stepId = wf.dataset.wfStep; renderRun(); $('#step-drawer').scrollIntoView({ behavior: 'smooth', block: 'nearest' }); return; }
    if (e.target.closest('[data-close-dialog]')) { e.target.closest('dialog').close(); return; }
    const card = e.target.closest('.step-card');
    if (card) { state.stepId = card.dataset.step; $$('.step-card').forEach((c) => c.classList.toggle('selected', c === card)); renderStepDrawer(); return; }
    const toggle = e.target.closest('.tl-toggle');
    if (toggle) { toggle.closest('.tl-item').classList.toggle('open'); return; }
    const node = e.target.closest('#diagram .node');
    if (node) renderComponent(node.dataset.component);
  });
  $('#diagram').addEventListener('keydown', (e) => { const n = e.target.closest('.node'); if (n && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); renderComponent(n.dataset.component); } });
  $('#custom-send').addEventListener('click', async () => {
    try { await startOrder(JSON.parse($('#custom-input').value)); } catch (err) { toast(`Invalid input: ${esc(err.message)}`); }
  });
  $('#burst-btn').addEventListener('click', async () => {
    const mix = ['normal', 'normal', 'normal', 'normal', 'normal', 'normal', 'flaky', 'flaky', 'declined', 'xx', 'down', 'normal'];
    await ensureWorkflow();
    await Promise.all(Array.from({ length: 20 }, (_, i) => startOrder(orderInput(SCENARIOS.find((s) => s.id === mix[i % mix.length]).input), { quiet: true })));
    toast('Sent 20 orders: watch the edges light up and the Engine tab counters climb.');
  });
  $('#run-filter').addEventListener('change', pollStats);
  $('#code-search').addEventListener('input', (e) => renderCodeMap(e.target.value));
  const guide = $('#guide-toggle');
  try { guide.checked = localStorage.getItem('ff-guide') !== 'off'; } catch { /* storage unavailable */ }
  document.body.classList.toggle('guide', guide.checked);
  guide.addEventListener('change', () => {
    document.body.classList.toggle('guide', guide.checked);
    try { localStorage.setItem('ff-guide', guide.checked ? 'on' : 'off'); } catch { /* ignore */ }
  });
  $('#theme-btn').addEventListener('click', () => {
    const dark = document.documentElement.dataset.theme
      ? document.documentElement.dataset.theme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
    document.documentElement.dataset.theme = dark ? 'light' : 'dark';
  });
  $('#tour-btn').addEventListener('click', () => tourShow(0));
  $('#keys-btn').addEventListener('click', () => $('#keys-dialog').showModal());
  addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { tourEnd(); return; }
    if (e.ctrlKey || e.metaKey || e.altKey || e.target.closest('input, textarea, select, dialog')) return;
    const views = ['live', 'run', 'engine', 'kafka', 'learn'];
    if (e.key >= '1' && e.key <= '5') showView(views[+e.key - 1]);
    else if (e.key === '?') $('#keys-dialog').showModal();
    else if (e.key === '/') { e.preventDefault(); showView('learn'); $('#code-search').focus(); }
    else if (e.key === 'g') $('#guide-toggle').click();
    else if (e.key === 't') $('#theme-btn').click();
    else if ((e.key === 'j' || e.key === 'k') && state.runs.length) {
      const i = state.runs.findIndex((r) => r.runId === state.runId);
      const next = state.runs[Math.min(state.runs.length - 1, Math.max(0, i + (e.key === 'j' ? 1 : -1)))] ?? state.runs[0];
      openRun(next.runId, { switchView: state.view === 'run' }).then(renderRuns);
    }
  });
  addEventListener('resize', () => { if ($('.tour-card')) tourShow(tourIndex); });
}

renderScenarios();
renderDiagram();
renderComponent('dispatcher');
renderLearn();
wire();
pollStats(); pollKafka(); pollConsistency(); pollPayments();
setInterval(pollStats, 1000);
setInterval(pollPayments, 3000);
setInterval(pollKafka, 2000);
setInterval(pollConsistency, 5000);

// Operator actions: cancel a running run, or retry a compensation that failed for good.
function operatorAction(kind) {
  if (!state.runId) return;
  const dialog = $('#op-dialog');
  $('#op-title').textContent = kind === 'cancel' ? 'Cancel this run?' : 'Retry the failed compensation?';
  $('#op-text').textContent = kind === 'cancel'
    ? 'Nothing new will start. Steps already running finish, then every completed step is undone (the charge refunded, the stock released) and the run ends CANCELLED.'
    : 'The undo step that failed is reopened with 3 more attempts; its earlier attempts stay in the history. Do this once the cause (say, the payment provider) is fixed.';
  try { $('#op-actor').value = localStorage.getItem('ff-actor') || ''; } catch { /* storage unavailable */ }
  $('#op-reason').value = '';
  dialog.dataset.kind = kind;
  dialog.showModal();
  $('#op-reason').focus();
}

async function sendOperatorAction(kind) {
  const actor = $('#op-actor').value.trim() || 'anonymous';
  try { localStorage.setItem('ff-actor', actor); } catch { /* ignore */ }
  try {
    await api(`/api/runs/${state.runId}/${kind}`, {
      method: 'POST', headers: { 'X-Actor': actor }, body: JSON.stringify({ reason: $('#op-reason').value.trim() || null }),
    });
    toast(kind === 'cancel' ? 'Cancel accepted: nothing new starts, completed steps are undone.' : 'Compensation reopened.');
  } catch (e) { toast(`Refused: ${esc(e.message)}`); }
  refreshRun();
}
$('#op-dialog form').addEventListener('submit', (e) => {
  if (e.submitter?.value === 'confirm') sendOperatorAction($('#op-dialog').dataset.kind);
});
$('#run-cancel').addEventListener('click', () => operatorAction('cancel'));
$('#run-retry').addEventListener('click', () => operatorAction('retry'));
