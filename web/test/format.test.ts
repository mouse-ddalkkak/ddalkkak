// 6 결과 "소요 N분" (utils/format.ts durationText, -256). cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { durationText } from '../src/utils/format.ts'

test('1분이 안 걸린 회차(서버가 0분을 준다)는 "1분 미만"', () => {
  assert.equal(durationText(0), '1분 미만')
})

test('1분 이상은 그대로 N분', () => {
  assert.equal(durationText(1), '1분')
  assert.equal(durationText(23), '23분')
})
