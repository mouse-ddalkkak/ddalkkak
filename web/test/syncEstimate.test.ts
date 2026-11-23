// 화면 4 토글 뒤 예상 시간 갱신 (F-SCOPE-02 · F-SCOPE-06, -191) — cd web && npm test
import { test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { syncEstimate } from '../src/pages/scope/syncEstimate.ts'

type S = { id: number }
const est = (durationMin: number) => ({ scenarioCount: 1, requests: 0, durationMin, load: 'low', advice: '' })
const flush = () => new Promise((r) => setImmediate(r))

/** put마다 직접 resolve · reject할 수 있는 가짜 */
function fakePut() {
  const calls: { scope: S; resolve: (v: unknown) => void; reject: (e: unknown) => void }[] = []
  const put = (scope: S) => new Promise<{ estimate: ReturnType<typeof est> }>((resolve, reject) => calls.push({ scope, resolve: resolve as never, reject }))
  return { calls, put }
}

test('연달아 토글하면 400ms 뒤 마지막 선택으로 PUT 한 번, 응답 estimate를 넘긴다', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const { calls, put } = fakePut()
  const applied: unknown[] = []
  const sync = syncEstimate<S>({ put, apply: (e) => applied.push(e) })
  sync.schedule({ id: 1 })
  t.mock.timers.tick(300)
  sync.schedule({ id: 2 })
  t.mock.timers.tick(399)
  assert.equal(calls.length, 0)
  t.mock.timers.tick(1)
  assert.deepEqual(calls.map((c) => c.scope), [{ id: 2 }])
  calls[0].resolve({ estimate: est(7) })
  await flush()
  assert.deepEqual(applied, [est(7)])
})

test('늦게 온 옛 응답은 버린다', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const { calls, put } = fakePut()
  const applied: unknown[] = []
  const sync = syncEstimate<S>({ put, apply: (e) => applied.push(e) })
  sync.schedule({ id: 1 })
  t.mock.timers.tick(400)
  sync.schedule({ id: 2 })
  t.mock.timers.tick(400)
  calls[1].resolve({ estimate: est(2) })
  await flush()
  calls[0].resolve({ estimate: est(1) })
  await flush()
  assert.deepEqual(applied, [est(2)])
})

test('PUT이 실패하면 아무것도 바꾸지 않는다 (문구는 래퍼 몫)', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const { calls, put } = fakePut()
  const apply = mock.fn()
  const sync = syncEstimate<S>({ put, apply })
  sync.schedule({ id: 1 })
  t.mock.timers.tick(400)
  calls[0].reject(new Error('400'))
  await flush()
  assert.equal(apply.mock.callCount(), 0)
})

test('cancel하면 대기 중인 PUT은 나가지 않고, 이미 나간 응답도 버린다', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const { calls, put } = fakePut()
  const apply = mock.fn()
  const sync = syncEstimate<S>({ put, apply })
  sync.schedule({ id: 1 })
  t.mock.timers.tick(400)
  sync.schedule({ id: 2 })
  sync.cancel()
  t.mock.timers.tick(1000)
  assert.equal(calls.length, 1)
  calls[0].resolve({ estimate: est(1) })
  await flush()
  assert.equal(apply.mock.callCount(), 0)
})
