// 오버레이 스크롤바 막대 크기 · 위치 — cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { thumbBox } from '../src/utils/scrollbar.ts'

test('스크롤이 필요 없으면 막대 없음', () => {
  assert.equal(thumbBox(800, 800, 0), null)
  assert.equal(thumbBox(800, 600, 0), null)
})

test('막대 높이 = max(40, 화면² / 문서), 위치는 끝에서 바닥에 닿는다', () => {
  assert.deepEqual(thumbBox(800, 1600, 0), { height: 400, top: 0 })
  assert.deepEqual(thumbBox(800, 1600, 800), { height: 400, top: 400 })
  assert.deepEqual(thumbBox(800, 1600, 400), { height: 400, top: 200 })
})

test('아주 긴 문서도 막대는 40px', () => {
  assert.deepEqual(thumbBox(800, 100000, 0), { height: 40, top: 0 })
  assert.deepEqual(thumbBox(800, 100000, 99200), { height: 40, top: 760 })
})
