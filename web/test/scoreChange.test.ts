// 8 이력 점수 변화 카드 증감 (utils/score.ts scoreChange, -256). runs는 GET /api/runs 처럼 최신순. cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { scoreChange } from '../src/utils/score.ts'

const r = (perf: number | null) => ({ scores: { perf, sec: null, code: null } })

test('앞 회차가 미검사면 마지막으로 잰 회차와 비교한다', () => {
  assert.equal(scoreChange([r(85), r(null), r(null), r(70)], 'perf'), 15)
})

test('최근 4번 밖에서 잰 회차도 찾는다', () => {
  assert.equal(scoreChange([r(60), r(null), r(null), r(null), r(null), r(72)], 'perf'), -12)
})

test('앞에 잰 회차가 없으면 첫 측정', () => {
  assert.equal(scoreChange([r(80), r(null)], 'perf'), '첫 측정')
})

test('최신 회차가 미검사거나 회차가 없으면 증감을 보이지 않는다', () => {
  assert.equal(scoreChange([r(null), r(70)], 'perf'), null)
  assert.equal(scoreChange([], 'perf'), null)
})
