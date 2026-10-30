import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../src/utils/feed.ts', import.meta.url), 'utf8')
const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText
const { feedHasMore } = await import(`data:text/javascript;base64,${Buffer.from(js).toString('base64')}`)
const cursor = { nextCt: '2026-01-01T12:00:00', nextCi: '9007199254740999' }

test('short or empty visible page can continue when backend says more', () => {
  assert.equal(feedHasMore({ items: [], ...cursor, hasMore: true }, 10), true)
  assert.equal(feedHasMore({ items: [{}], ...cursor, hasMore: true }, 10), true)
})
test('explicit exhausted full page does not fetch again', () => {
  assert.equal(feedHasMore({ items: Array(10), ...cursor, hasMore: false }, 10), false)
})
test('missing composite cursor cannot restart the first page endlessly', () => {
  assert.equal(feedHasMore({ items: [], hasMore: true }, 10), false)
  assert.equal(feedHasMore({ items: [], nextCi: '1', hasMore: true }, 10), false)
})
test('old backend still supports length and cursor fallback', () => {
  assert.equal(feedHasMore({ items: Array(10), nextLi: '100' }, 10), true)
  assert.equal(feedHasMore({ items: [{}], ...cursor }, 10), false)
})
