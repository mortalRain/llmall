import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

function moduleUrl(source) {
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
  return `data:text/javascript;base64,${Buffer.from(js).toString('base64')}`
}
const session = moduleUrl(await readFile(new URL('../src/utils/session.ts', import.meta.url), 'utf8'))
const source = (await readFile(new URL('../src/api/http.ts', import.meta.url), 'utf8'))
  .replaceAll("from 'axios'", `from '${import.meta.resolve('axios')}'`)
  .replaceAll("from 'json-bigint'", `from '${import.meta.resolve('json-bigint')}'`)
  .replaceAll("from '@/utils/session'", `from '${session}'`)
  .replaceAll('import.meta.env', '{}')
const { default: http, post } = await import(moduleUrl(source))

test('account switch before axios dispatch cannot send previous account purchase key', async () => {
  const entries = new Map([['llmall.user', JSON.stringify({ userId: 'alice' })], ['llmall.accessToken', 'alice-token']])
  globalThis.window = { localStorage: { getItem: key => entries.get(key) ?? null } }
  let sent = false
  http.defaults.adapter = async config => {
    sent = true
    return { data: 'uv1', status: 200, statusText: 'OK', headers: {}, config }
  }
  const pending = post('/vouchers/v1/receive', { pw: 'secret', requestId: 'request_00000001' }, { expectedUserId: 'alice', skipAuthRefresh: true })
  entries.set('llmall.user', JSON.stringify({ userId: 'bob' }))
  entries.set('llmall.accessToken', 'bob-token')
  await assert.rejects(pending, /账号已变化/)
  assert.equal(sent, false)
})

test('late purchase status response cannot replace the next account token', async () => {
  const entries = new Map([['llmall.user', JSON.stringify({ userId: 'alice' })], ['llmall.accessToken', 'alice-token']])
  globalThis.window = { localStorage: { getItem: key => entries.get(key) ?? null, setItem: (key, value) => entries.set(key, value) } }
  http.defaults.adapter = async config => {
    entries.set('llmall.user', JSON.stringify({ userId: 'bob' }))
    entries.set('llmall.accessToken', 'bob-token')
    return { data: {}, status: 200, statusText: 'OK', headers: { 'x-access-token': 'renewed-alice-token' }, config }
  }
  await assert.rejects(http.get('/vouchers/purchases/request_00000001', { expectedUserId: 'alice' }), /账号已变化/)
  assert.equal(entries.get('llmall.accessToken'), 'bob-token')
})
