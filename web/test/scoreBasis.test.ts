// 6 결과 점수 근거 한 줄 (utils/score.ts scoreBasis, F-RPT-04 · -247). cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { scoreBasis } from '../src/utils/score.ts'

test('문제별 감점을 100에서 뺀 식으로 보인다', () => {
  assert.equal(scoreBasis([30, 10], 60), '100 − 30 − 10 = 60')
})

test('감점이 없으면 감점 없음', () => {
  assert.equal(scoreBasis([], 100), '감점 없음 = 100')
})

test('감점 합이 100을 넘으면 0점에서 멈춘다고 알린다', () => {
  assert.equal(scoreBasis([30, 30, 30, 30], 0), '100 − 30 − 30 − 30 − 30 = 0 (0점 아래로는 내려가지 않아요)')
})
