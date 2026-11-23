// 6 · 7 프롬프트 생성 중복 요청 막기 (utils/busy.ts, -216). cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { runOnce } from '../src/utils/busy.ts'

test('runOnce: 진행 중에 다시 불러도 fn은 한 번, 끝나면 다시 부를 수 있다', async () => {
  const flag = { current: false }
  const states: boolean[] = []
  let calls = 0
  let finish = () => {}
  const fn = () => new Promise<void>((r) => { calls++; finish = r })
  const first = runOnce(flag, (b) => states.push(b), fn)
  await runOnce(flag, (b) => states.push(b), fn) // 진행 중 → 바로 무시
  assert.equal(calls, 1)
  finish()
  await first
  assert.deepEqual(states, [true, false])
  const again = runOnce(flag, () => {}, fn)
  assert.equal(calls, 2)
  finish()
  await again
})

test('runOnce: fn이 실패해도 풀린다', async () => {
  const flag = { current: false }
  const states: boolean[] = []
  await assert.rejects(runOnce(flag, (b) => states.push(b), async () => { throw new Error('x') }))
  assert.equal(flag.current, false)
  assert.deepEqual(states, [true, false])
})
