import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkRun, PLAN } from './check-read-results.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const HEADER = 'round,endpoint,concurrency,sequence,ms,status,valid,bytes\n';
function fixture() {
  return { results: { completed:true, finishedAt:'2026-10-28T00:00:10Z', stages:PLAN.map(s => ({...s, requests:2, errors:0, elapsedSeconds:10})) },
    csv:HEADER+PLAN.flatMap(s => [1,2].map(i => `${s.round},${s.endpoint},${s.concurrency},${i},${i*10},200,true,8`)).join('\n')+'\n' };
}
test('complete plan has success-only averages and nearest-rank P99',()=>{
  const f=fixture(), r=checkRun(f.results,f.csv);
  assert.equal(r.completed,true); assert.equal(r.totalRequests,24); assert.equal(r.rows[0].successAvgMs,15); assert.equal(r.rows[0].successP99Ms,20);
});
test('guard, failure, incomplete flag or missing end time prevents completion',()=>{
  for(const override of [{abortReason:'memory'},{failure:'timeout'},{completed:false},{finishedAt:null}]) {
    const f=fixture(); assert.equal(checkRun({...f.results,...override},f.csv).completed,false);
  }
});
test('one declared stage is not a complete plan',()=>{
  const f=fixture();f.results.stages=f.results.stages.slice(0,1);
  assert.equal(checkRun(f.results,HEADER+'1,search,1,1,10,200,true,8\n1,search,1,2,20,200,true,8\n').completed,false);
});
test('raw error mismatch is rejected',()=>{
  const f=fixture(); f.results.stages[0].errors=1; assert.throws(()=>checkRun(f.results,f.csv),/count mismatch/);
});
test('failed fast responses do not improve success latency or throughput',()=>{
  const f=fixture(); f.results.stages[0].errors=1;
  f.csv=f.csv.replace('1,search,1,1,10,200,true,8','1,search,1,1,1,400,false,8');
  const r=checkRun(f.results,f.csv); assert.equal(r.completed,true); assert.equal(r.errorFree,false);
  assert.equal(r.rows[0].successAvgMs,20); assert.equal(r.rows[0].successRps,.1);
});
test('no successful samples yields null latency rather than zero',()=>{
  const f=fixture(); f.results.stages[0].errors=2;
  f.csv=f.csv.replace('1,search,1,1,10,200,true,8','1,search,1,1,10,400,false,8').replace('1,search,1,2,20,200,true,8','1,search,1,2,20,400,false,8');
  const r=checkRun(f.results,f.csv).rows[0];assert.equal(r.successAvgMs,null);assert.equal(r.successP99Ms,null);assert.equal(r.successRps,0);
});
test('nonfinite, negative or missing numeric sample fields rejected',()=>{
  for(const value of ['NaN','Infinity','-1','']) {const f=fixture();assert.throws(()=>checkRun(f.results,f.csv.replace(',1,10,200',`,1,${value},200`)));}
});
test('HTTP error cannot carry success flag',()=>{
  const f=fixture();assert.throws(()=>checkRun(f.results,f.csv.replace(',200,true,',',400,true,')),/success flag/);
});
test('unknown or duplicate stages rejected',()=>{
  const f=fixture();f.results.stages.push({...f.results.stages[0]});assert.throws(()=>checkRun(f.results,f.csv),/duplicate/);
  const g=fixture();assert.throws(()=>checkRun(g.results,g.csv+'3,search,1,1,5,200,true,8\n'),/undeclared/);
});
test('zero duration, truncated rows and duplicate sequence rejected',()=>{
  const f=fixture();f.results.stages[0].elapsedSeconds=0;assert.throws(()=>checkRun(f.results,f.csv),/duration/);
  const g=fixture();assert.throws(()=>checkRun(g.results,g.csv.replace('1,search,1,2,','1,search,1,1,')),/sequence/);
  assert.throws(()=>checkRun(g.results,g.csv+'1,search,1,3\n'),/CSV row/);
});
test('declared HTTP status counts must match raw samples',()=>{
  const f=fixture();f.results.stages[0].statuses={'200':1,'400':1};assert.throws(()=>checkRun(f.results,f.csv),/HTTP status count/);
});
test('invalid rerun invalidates stale successful output',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'llmall-check-cli-'));
  const f=fixture();f.results.stages[0].errors=1;
  fs.writeFileSync(path.join(dir,'results.json'),JSON.stringify(f.results));
  fs.writeFileSync(path.join(dir,'requests.csv'),f.csv);
  fs.writeFileSync(path.join(dir,'checked-analysis.json'),JSON.stringify({completed:true}));
  const child=spawnSync(process.execPath,['--max-old-space-size=24',fileURLToPath(new URL('./check-read-results.mjs',import.meta.url)),dir],{encoding:'utf8'});
  assert.equal(child.status,1);
  const checked=JSON.parse(fs.readFileSync(path.join(dir,'checked-analysis.json'),'utf8'));
  assert.equal(checked.completed,false);assert.equal(checked.invalid,true);
});
