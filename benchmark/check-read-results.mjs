import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export const PLAN = [1, 2].flatMap(round => ['search', 'items'].flatMap(endpoint =>
  [1, 4, 8].map(concurrency => ({ round, endpoint, concurrency }))));
const key = x => `${x.round}:${x.endpoint}:${x.concurrency}`;
const integer = x => Number.isSafeInteger(x) && x >= 0;
function requireValid(value, message) { if (!value) throw new Error(message); }
const round = x => Number(x.toFixed(3));
const percentile = (a, p) => a.length ? a[Math.ceil(a.length * p) - 1] : null;

/** Fixed workload: 2 endpoints x 2 rounds x 1/4/8 concurrency. No network or business writes. */
export function checkRun(results, csv) {
  requireValid(Array.isArray(results.stages), 'Missing stages');
  const expected = new Set(PLAN.map(key)), stages = new Map();
  for (const s of results.stages) {
    const id = key(s);
    requireValid(expected.has(id) && !stages.has(id), 'Unknown or duplicate stage');
    requireValid(integer(s.requests) && integer(s.errors) && s.errors <= s.requests, 'Invalid stage counts');
    requireValid(Number.isFinite(s.elapsedSeconds) && s.elapsedSeconds > 0, 'Invalid stage duration');
    stages.set(id, { source: s, total: 0, failures: 0, success: [], statuses: {} });
  }
  const lines = csv.trimEnd().split(/\r?\n/);
  requireValid(lines.shift() === 'round,endpoint,concurrency,sequence,ms,status,valid,bytes', 'Invalid CSV header');
  for (const line of lines) {
    const c = line.split(',');
    requireValid(c.length === 8 && c.every(x => x.length > 0), 'Invalid CSV row');
    const [r, endpoint, concurrency, sequence, ms, status, valid, bytes] = c;
    const g = stages.get(`${r}:${endpoint}:${concurrency}`);
    requireValid(g, 'Raw samples contain an undeclared stage');
    requireValid(integer(Number(sequence)) && Number(sequence) === g.total + 1, 'Invalid sample sequence');
    requireValid(Number.isFinite(Number(ms)) && Number(ms) >= 0, 'Invalid sample latency');
    requireValid(integer(Number(status)) && (Number(status) === 0 || Number(status) >= 100 && Number(status) <= 599), 'Invalid HTTP status');
    requireValid(['true', 'false'].includes(valid) && (valid !== 'true' || status === '200'), 'Invalid success flag');
    requireValid(integer(Number(bytes)), 'Invalid response byte count');
    g.total++; g.statuses[status] = (g.statuses[status] || 0) + 1;
    if (valid === 'true') g.success.push(Number(ms)); else g.failures++;
  }
  const rows = [];
  for (const g of stages.values()) {
    const s = g.source;
    requireValid(g.total === s.requests && g.failures === s.errors, 'Raw sample/error count mismatch');
    if (s.statuses) {
      const codes = new Set([...Object.keys(s.statuses), ...Object.keys(g.statuses)]);
      requireValid([...codes].every(code => (s.statuses[code] || 0) === (g.statuses[code] || 0)), 'Raw HTTP status count mismatch');
    }
    g.success.sort((a, b) => a - b);
    rows.push({ round: s.round, endpoint: s.endpoint, concurrency: s.concurrency, requests: g.total,
      errors: g.failures, errorPct: g.total ? round(100 * g.failures / g.total) : null,
      successes: g.success.length, successRps: round(g.success.length / s.elapsedSeconds),
      successAvgMs: g.success.length ? round(g.success.reduce((a, b) => a + b, 0) / g.success.length) : null,
      successP95Ms: percentile(g.success, .95), successP99Ms: percentile(g.success, .99) });
  }
  const reasons = [];
  if (results.completed !== true) reasons.push('runner未确认完成');
  if (results.abortReason) reasons.push(`守卫中止：${results.abortReason}`);
  if (results.failure) reasons.push(`运行失败：${results.failure}`);
  if (stages.size !== expected.size || rows.some(r => r.requests === 0)) reasons.push('12档计划不完整或存在空档');
  if (!Number.isFinite(Date.parse(results.finishedAt))) reasons.push('缺少有效结束时间');
  return { completed: reasons.length === 0, completionReasons: reasons, errorFree: rows.every(r => r.errors === 0),
    totalRequests: rows.reduce((a, r) => a + r.requests, 0), totalErrors: rows.reduce((a, r) => a + r.errors, 0), rows };
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const dir = path.resolve(process.argv[2] || '.');
  try {
    const result = checkRun(JSON.parse(fs.readFileSync(path.join(dir, 'results.json'), 'utf8')),
      fs.readFileSync(path.join(dir, 'requests.csv'), 'utf8'));
    const lines = ['# 读接口结果完整性校验', '',
      `运行状态：${result.completed ? '已完成' : '未完成'}；零错误：${result.errorFree ? '是' : '否'}。完成不代表满足性能目标。`, '',
      ...result.completionReasons.map(r => `- ${r}`), '',
      '|轮次|接口|并发|有效RPS|成功avg ms|成功P95 ms|成功P99 ms|失败数|',
      '|---|---|---:|---:|---:|---:|---:|---:|',
      ...result.rows.map(r => `|${r.round}|${r.endpoint}|${r.concurrency}|${r.successRps}|${r.successAvgMs ?? '无成功样本'}|${r.successP95Ms ?? '无成功样本'}|${r.successP99Ms ?? '无成功样本'}|${r.errors}|`), '',
      '仅校验固定读负载的样本与汇总一致性。成功样本为空时延迟为null，不能当作0ms。资源、环境、镜像和业务正确性须结合原报告核对。'];
    fs.writeFileSync(path.join(dir, 'checked-analysis.json'), JSON.stringify(result, null, 2));
    fs.writeFileSync(path.join(dir, 'checked-report.md'), lines.join('\n') + '\n');
    console.log(JSON.stringify({ completed: result.completed, errorFree: result.errorFree, requests: result.totalRequests, errors: result.totalErrors }));
    if (!result.completed) process.exitCode = 2;
  } catch (e) {
    // A failed rerun must not leave last run's successful result as the current output.
    fs.writeFileSync(path.join(dir, 'checked-analysis.json'), JSON.stringify({ completed:false, invalid:true, error:e.message }, null, 2));
    fs.writeFileSync(path.join(dir, 'checked-report.md'), `# 读接口结果完整性校验\n\n校验失败，旧成功结果已失效：${e.message}\n`);
    console.error(`INVALID_RESULTS: ${e.message}`); process.exitCode = 1;
  }
}
