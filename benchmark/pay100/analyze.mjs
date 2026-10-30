import fs from 'node:fs';
import assert from 'node:assert/strict';
const dir=process.argv[2];
fs.rmSync(dir+'/checked-summary.json',{force:true});
const r=JSON.parse(fs.readFileSync(dir+'/results.json','utf8'));
assert.equal(r.completed,true);assert.equal(r.cleanupPassed,true);
assert.equal(r.httpPortClosed,true);assert.equal(r.dbPoolClosed,true);assert.equal(r.clientPoolStopped,true);
const lines=fs.readFileSync(dir+'/requests.csv','utf8').trim().split(/\r?\n/);
assert.equal(lines.shift(),'sequence,phase,user,operation,status,startNs,endNs,latencyNs');
const rows=lines.map(line=>{
 const a=line.split(',');assert.equal(a.length,8);
 const [sequence,phase,user,operation,status,start,end,latency]=a;
 assert.match(sequence,/^[1-9]\d*$/);assert.match(user,/^[1-9]\d*$/);
 assert.ok(['order','apply','debit'].includes(operation));assert.ok(['200','400'].includes(status));
 const s=BigInt(start),e=BigInt(end),l=BigInt(latency);assert.ok(e>s);assert.equal(e-s,l);
 return {sequence:Number(sequence),phase,user:Number(user),operation,status:Number(status),start:s,end:e};
});
assert.equal(rows.length,4815);assert.equal(r.totalRecordedHttpRequests,4815);
assert.deepEqual(rows.map(x=>x.sequence).sort((a,b)=>a-b),Array.from({length:4815},(_,i)=>i+1));
const loop=rows.filter(x=>x.phase==='loop');assert.equal(loop.length,4500);
assert.ok(loop.every(x=>x.status===200));
assert.equal(new Set(loop.map(x=>x.user)).size,100);
for(let user=1;user<=100;user++){
 const own=loop.filter(x=>x.user===user).sort((a,b)=>a.start<b.start?-1:1);
 assert.equal(own.length,45);
 for(let i=0;i<45;i++){assert.equal(own[i].operation,['order','apply','debit'][i%3]);if(i)assert.ok(own[i].start>=own[i-1].end);}
}
function overlap(phase){
 const edges=phase.flatMap(x=>[{t:x.start,d:1},{t:x.end,d:-1}]).sort((a,b)=>a.t<b.t?-1:a.t>b.t?1:a.d-b.d);
 let active=0,peak=0;for(const e of edges){active+=e.d;peak=Math.max(peak,active);}assert.equal(active,0);return peak;
}
const loopOverlap=overlap(loop);assert.equal(loopOverlap,100);assert.equal(r.loop.serverPeakInFlight,100);
assert.equal(r.loop.readyWorkers,100);assert.equal(r.loop.balanceBeforeCents-r.loop.balanceAfterCents,450000);
const races=[];
for(let i=0;i<3;i++){
 const race=rows.filter(x=>x.phase==='race'+i);assert.equal(race.length,100);
 assert.ok(race.every(x=>x.operation==='debit'&&x.user===201+i));
 assert.equal(race.filter(x=>x.status===200).length,1);assert.equal(race.filter(x=>x.status===400).length,99);
 assert.equal(r.races[i].accepted,1);assert.equal(r.races[i].rejected,99);assert.equal(r.races[i].debits,1);
 assert.equal(r.races[i].serverPeakInFlight,100);assert.equal(r.races[i].callbackPaidVerified,true);
 races.push({phase:'race'+i,accepted:1,rejected:99,clientPeakInFlight:overlap(race),serverPeakInFlight:r.races[i].serverPeakInFlight});
}
assert.equal(rows.filter(x=>x.phase==='warmup').length,9);assert.equal(rows.filter(x=>x.phase==='setup').length,6);
assert.ok(rows.every(x=>['loop','warmup','setup','race0','race1','race2'].includes(x.phase)));
const summary={verified:true,requests:rows.length,loopRequests:4500,flows:1500,loopWorkers:100,loopClientPeakInFlight:loopOverlap,loopServerPeakInFlight:r.loop.serverPeakInFlight,races,
 expectedBusinessRejections:297,unexpectedHttpFailures:0,scope:r.scope,schema:r.schema};
fs.writeFileSync(dir+'/checked-summary.json',JSON.stringify(summary,null,2));
console.log(JSON.stringify(summary));
