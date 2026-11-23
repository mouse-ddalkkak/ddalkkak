import { test } from 'node:test'
import assert from 'node:assert/strict'
import { markEnter, shouldEnter } from '../src/utils/screenFx.ts'

test('표시한 화면만 1초 안에 효과를 낸다', (t) => {
  let now = 5000
  t.mock.method(performance, 'now', () => now)
  markEnter('afterLogin')
  assert.equal(shouldEnter('afterLogin'), true)
  assert.equal(shouldEnter('login'), false)
  now += 999
  assert.equal(shouldEnter('afterLogin'), true)
  now += 1
  assert.equal(shouldEnter('afterLogin'), false)
})
