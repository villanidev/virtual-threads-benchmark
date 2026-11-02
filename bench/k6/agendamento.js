// k6 load test for the Agendamento API. One phase per run (driven by bench/server.sh).
//   [-e RATE=5000]  open-model arrival rate (req/s)
//   k6 run -e PHASE=write|read|mixed -e N=10000 -e VUS=50 [-e PORT=8080] [-e WRITTEN=0] bench/k6/agendamento.js
// N total requests are shared among VUS virtual users (shared-iterations); each VU keeps one keep-alive connection.
// Write i is unique: servico = 1 + i % 1000, day = floor(i / 1000), so N writes never conflict.
// read/mixed list days that already exist: WRITTEN = number of records written by earlier phases.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const PHASE = __ENV.PHASE || 'write';
const N = parseInt(__ENV.N || '1000');
const VUS = parseInt(__ENV.VUS || '50');
const WRITTEN = parseInt(__ENV.WRITTEN || '0');
const BASE = 1767225600; // 2026-01-01T00:00:00Z
const MIXED_WRITE_EVERY = 5;
const URL = `http://127.0.0.1:${__ENV.PORT || '8080'}/api/agendamentos`;
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

http.setResponseCallback(http.expectedStatuses(200, 201));

// RATE > 0: open model (constant-arrival-rate) at RATE req/s for N/RATE seconds; VUS = pre-allocated VUs, up to 4x.
// RATE = 0: closed model (shared-iterations), the generator sets the pace.
const RATE = parseInt(__ENV.RATE || '0');
const scenario = RATE > 0
  ? { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${Math.ceil(N / RATE)}s`,
      preAllocatedVUs: VUS, maxVUs: VUS * 4 }
  : { executor: 'shared-iterations', vus: VUS, iterations: N, maxDuration: '30m' };

export const options = {
  scenarios: { load: scenario },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'max'],
};

function write(n) {
  const body = JSON.stringify({
    nome: `Cliente ${n % 100}`,
    servicoId: 1 + (n % 1000),
    dataHora: BASE + Math.floor(n / 1000) * 86400 + 9 * 3600,
  });
  const res = http.post(URL, body, JSON_HEADERS);
  check(res, { 'created 201': (r) => r.status === 201 });
}

function read(i, written) {
  const days = Math.max(1, Math.ceil(written / 1000));
  const day = new Date((BASE + (i % days) * 86400) * 1000).toISOString().slice(0, 10);
  const res = http.get(`${URL}?data=${day}`);
  check(res, { 'listed 200': (r) => r.status === 200 });
}

export default function () {
  const i = exec.scenario.iterationInTest;
  if (PHASE === 'write') {
    write(WRITTEN + i);
  } else if (PHASE === 'read') {
    read(i, WRITTEN);
  } else if (i % MIXED_WRITE_EVERY === 0) {
    write(WRITTEN + Math.floor(i / MIXED_WRITE_EVERY));
  } else {
    read(i, WRITTEN);
  }
}

export function handleSummary(data) {
  const d = data.metrics.http_req_duration.values;
  const reqs = data.metrics.http_reqs.values;
  const failed = data.metrics.http_req_failed.values.passes;
  const line = `${PHASE.padEnd(6)} ${String(reqs.count).padStart(8)} ${String(VUS).padStart(5)} ` +
    `${data.state.testRunDurationMs.toFixed(0).padStart(9)} ${reqs.rate.toFixed(0).padStart(9)} ` +
    `${d.med.toFixed(2).padStart(8)} ${d['p(90)'].toFixed(2).padStart(8)} ${d['p(99)'].toFixed(2).padStart(8)} ` +
    `${d.max.toFixed(2).padStart(8)}  avg=${d.avg.toFixed(2)} failed=${failed}` +
    ` dropped=${data.metrics.dropped_iterations ? data.metrics.dropped_iterations.values.count : 0}\n`;
  return { stdout: line, [`bench/results/k6-${PHASE}-${N}${__ENV.TAG ? '-' + __ENV.TAG : ''}.json`]: JSON.stringify(data) };
}
