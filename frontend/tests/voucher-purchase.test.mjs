import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../src/utils/voucherPurchase.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
const { createVoucherPurchase, isOrdinaryVoucher } = await import(`data:text/javascript;base64,${Buffer.from(compiled.outputText).toString('base64')}`)

function deterministicLocker() {
  const tails = new Map()
  return async (name, work) => {
    const previous = tails.get(name) ?? Promise.resolve()
    let release
    const gate = new Promise(resolve => { release = resolve })
    tails.set(name, gate)
    await previous
    try { return await work() } finally { release() }
  }
}

function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

function fixture() {
  const entries = new Map()
  const calls = []
  let user = 'alice'
  let state = 'NOT_FOUND'
  let issueFails = false
  let queryFails = false
  let sequence = 0
  const storage = {
    getItem: key => entries.get(key) ?? null,
    setItem: (key, value) => entries.set(key, value),
    removeItem: key => entries.delete(key),
  }
  const options = {
    lock: deterministicLocker(),
    storage: () => storage,
    currentUser: () => user,
    newRequestId: () => `request_0000000${++sequence}`,
    wait: async () => {},
    query: async requestId => {
      calls.push(['query', requestId])
      if (queryFails) throw Error('offline')
      return { requestId, state, voucherId: state === 'NOT_FOUND' ? null : 'v1', amount: state === 'NOT_FOUND' ? null : 100, userVoucherId: state === 'ISSUED' ? 'uv1' : null }
    },
    receive: async (id, pw, requestId) => {
      calls.push(['receive', id, pw, requestId, [...entries.values()]])
      if (issueFails) throw Error('lost response')
      return 'uv1'
    },
  }
  return { entries, calls, storage, options, controller: () => createVoucherPurchase(options), setUser: value => user = value, setState: value => state = value, failIssue: () => issueFails = true, failQuery: () => queryFails = true }
}

test('persists key before money request and never persists password', async () => {
  const f = fixture()
  const result = await f.controller().purchase('alice', 'v1', async () => 'secret')
  assert.equal(result.state, 'ISSUED')
  assert.deepEqual(f.calls[0], ['receive', 'v1', 'secret', 'request_00000001', ['request_00000001']])
  assert.equal(f.entries.size, 0)
})

test('refresh queries pending key and retry reuses it after NOT_FOUND', async () => {
  const f = fixture()
  f.failIssue(); f.failQuery()
  await f.controller().purchase('alice', 'v1', async () => 'secret')
  const key = [...f.entries.values()][0]
  f.options.query = async requestId => ({ requestId, state: 'NOT_FOUND', voucherId: null, amount: null, userVoucherId: null })
  assert.equal((await f.controller().restore('alice', 'v1')).state, 'NOT_FOUND')
  await f.controller().purchase('alice', 'v1', async () => 'new-secret')
  assert.equal(f.calls.filter(call => call[0] === 'receive')[1][3], key)
  assert.equal(f.entries.size, 1)
})

for (const state of ['ACTIVE', 'CANCELING']) {
  test(`${state} is polled finitely and never prompts or issues again`, async () => {
    const f = fixture(); f.failIssue(); f.setState(state)
    await f.controller().purchase('alice', 'v1', async () => 'secret')
    f.calls.length = 0
    const result = await f.controller().purchase('alice', 'v1', async () => assert.fail('must not ask password'))
    assert.equal(result.state, state)
    assert.ok(f.calls.length > 1 && f.calls.length <= 6)
    assert.ok(f.calls.every(call => call[0] === 'query'))
    assert.equal(f.entries.size, 1)
  })
}

for (const state of ['CANCELED', 'ISSUED']) {
  test(`${state} resolves stored attempt without automatic new charge`, async () => {
    const f = fixture(); f.failIssue(); f.failQuery()
    await f.controller().purchase('alice', 'v1', async () => 'secret')
    f.options.query = async requestId => ({ requestId, state, voucherId: 'v1', amount: 100, userVoucherId: state === 'ISSUED' ? 'uv1' : null })
    f.calls.length = 0
    assert.equal((await f.controller().purchase('alice', 'v1', async () => assert.fail('no new confirmation'))).state, state)
    assert.equal(f.entries.size, 0)
    assert.equal(f.calls.length, 0)
    if (state === 'CANCELED') {
      f.options.receive = async (id, pw, requestId) => { assert.equal(requestId, 'request_00000002'); return 'uv2' }
      assert.equal((await f.controller().purchase('alice', 'v1', async () => 'explicit-secret')).userVoucherId, 'uv2')
    }
  })
}

test('storage read/write failures block money requests', async () => {
  for (const method of ['getItem', 'setItem']) {
    const f = fixture(); f.storage[method] = () => { throw Error('blocked storage') }
    await assert.rejects(f.controller().purchase('alice', 'v1', async () => 'secret'))
    assert.equal(f.calls.length, 0)
  }
})

test('account switch during confirmation prevents charge and isolates old attempt', async () => {
  const f = fixture(); f.failIssue(); f.failQuery()
  await f.controller().purchase('alice', 'v1', async () => 'secret')
  f.setUser('bob'); f.calls.length = 0
  assert.equal((await f.controller().restore('bob', 'v1')).state, 'EMPTY')
  await assert.rejects(f.controller().purchase('bob', 'v1', async () => { f.setUser('alice'); return 'secret' }))
  assert.equal(f.calls.length, 0)
  assert.equal(f.entries.size, 1)
})

test('unknown or mismatched status cannot clear stored key or charge again', async () => {
  const f = fixture(); f.failIssue(); f.failQuery()
  await f.controller().purchase('alice', 'v1', async () => 'secret')
  for (const state of ['UNKNOWN', 'ISSUED']) {
    f.options.query = async requestId => ({ requestId, state, voucherId: 'other', amount: 100, userVoucherId: 'uv1' })
    assert.equal((await f.controller().restore('alice', 'v1')).state, 'UNKNOWN')
    assert.equal(f.entries.size, 1)
  }
})

test('ordinary eligibility follows seckill configuration rather than voucher type', () => {
  const plain = { type: 0, remainingStock: null, beginTime: null, endTime: null, inSeckillTime: null }
  assert.equal(isOrdinaryVoucher(plain), true)
  assert.equal(isOrdinaryVoucher({ ...plain, type: 1 }), true)
  for (const config of [{ remainingStock: 0 }, { beginTime: '2026-10-28' }, { endTime: '2026-10-29' }, { inSeckillTime: false }]) {
    assert.equal(isOrdinaryVoucher({ ...plain, ...config }), false)
  }
})

test('silent storage write failure blocks money requests', async () => {
  const f = fixture(); f.storage.setItem = () => {}
  await assert.rejects(f.controller().purchase('alice', 'v1', async () => 'secret'))
  assert.equal(f.calls.length, 0)
})

test('account switch while querying leaves previous user record untouched', async () => {
  const f = fixture(); f.failIssue(); f.failQuery()
  await f.controller().purchase('alice', 'v1', async () => 'secret')
  f.options.query = async requestId => {
    f.setUser('bob')
    return { requestId, state: 'ISSUED', voucherId: 'v1', amount: 100, userVoucherId: 'uv1' }
  }
  assert.equal((await f.controller().restore('alice', 'v1')).state, 'UNKNOWN')
  assert.equal(f.entries.size, 1)
})

test('two tabs cannot replace the first pending purchase key after concurrent confirmation', async () => {
  const f = fixture()
  const firstConfirmation = deferred()
  const firstSent = deferred()
  const releaseFirst = deferred()
  let firstPending = true
  let secondConfirmed = false
  let staleRead = true
  const secondStorage = {
    ...f.storage,
    // Models a read completed in B before A wrote, whose result arrives after A.
    getItem: key => {
      if (secondConfirmed && firstPending && staleRead) { staleRead = false; return null }
      return f.storage.getItem(key)
    },
  }
  f.options.receive = async () => { firstSent.resolve(); await releaseFirst.promise; throw Error('lost response') }
  f.failQuery()
  const first = f.controller().purchase('alice', 'v1', () => firstConfirmation.promise)
  const second = createVoucherPurchase({ ...f.options, storage: () => secondStorage,
    newRequestId: () => 'second_request_0001',
    receive: async () => { throw Error('backend rejects conflicting attempt') },
  }).purchase('alice', 'v1', async () => { await firstSent.promise; secondConfirmed = true; return 'second-secret' })
  firstConfirmation.resolve('first-secret')
  await firstSent.promise
  await new Promise(resolve => setImmediate(resolve))
  const pendingKey = [...f.entries.values()][0]
  const confirmedBeforeRelease = secondConfirmed
  firstPending = false
  releaseFirst.resolve()
  await Promise.all([first, second])
  assert.equal(pendingKey, 'request_00000001', 'the pending A key must not be overwritten by B')
  assert.equal(confirmedBeforeRelease, false, 'B must wait before asking for a new payment')
  assert.equal([...f.entries.values()][0], 'request_00000001')
  assert.equal(secondConfirmed, false)
})

test('restore waits for purchase lock instead of clearing the in-flight record', async () => {
  const f = fixture()
  const sent = deferred()
  const release = deferred()
  f.options.receive = async () => { sent.resolve(); await release.promise; throw Error('lost response') }
  f.setState('CANCELED')
  const purchase = f.controller().purchase('alice', 'v1', async () => 'secret')
  await sent.promise
  const restore = f.controller().restore('alice', 'v1')
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(f.entries.size, 1, 'restore cannot clear a key while purchase holds the lock')
  assert.equal(f.calls.length, 0, 'restore cannot query before purchase releases the lock')
  release.resolve()
  await Promise.all([purchase, restore])
  assert.equal(f.entries.size, 0)
})

test('without cross-tab lock support no confirmation or money request is allowed', async () => {
  const f = fixture()
  delete f.options.lock
  await assert.rejects(f.controller().purchase('alice', 'v1', async () => assert.fail('must not prompt')), /跨标签页锁/)
  assert.equal(f.entries.size, 0)
  assert.equal(f.calls.length, 0)
})
