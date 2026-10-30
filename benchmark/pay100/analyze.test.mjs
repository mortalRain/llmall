import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync,spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
import test from 'node:test';
const script=fileURLToPath(new URL('./analyze.mjs',import.meta.url));
// Explicit retained result directory, never rewrite the original evidence.
const source=process.env.LLMALL_PAY100_EVIDENCE;
test('valid evidence is checked, corrupt rerun cannot retain a successful summary',{skip:!source},()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'llmall-pay100-check-'));
 try {
  for(const name of ['results.json','requests.csv'])fs.copyFileSync(path.join(source,name),path.join(dir,name));
  execFileSync(process.execPath,[script,dir]);assert.equal(JSON.parse(fs.readFileSync(path.join(dir,'checked-summary.json'))).verified,true);
  const csv=fs.readFileSync(path.join(dir,'requests.csv'),'utf8');
  fs.writeFileSync(path.join(dir,'requests.csv'),csv.replace(/,race0,(201),debit,400,/,',race0,$1,debit,200,'));
  assert.notEqual(spawnSync(process.execPath,[script,dir]).status,0);
  assert.equal(fs.existsSync(path.join(dir,'checked-summary.json')),false);
 } finally {fs.rmSync(dir,{recursive:true,force:true});}
});
