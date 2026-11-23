// 0-0 햄스터 연출 타임라인 (Pixel Flow runHamScene) — cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pressDepth, sceneTime, SCENE_END } from '../src/utils/hamScene.ts'

test('버튼 눌림: 착지 전 0 → 첫 착지 7 → 둘째 17 → 콩 25', () => {
  assert.equal(pressDepth(0), 0)
  assert.equal(pressDepth(8), 0)
  assert.equal(pressDepth(8.5), 7)
  assert.equal(pressDepth(9.5), 17)
  assert.equal(pressDepth(SCENE_END), 25)
})

test('버튼 눌림 기준 시각 8.1 · 9.3 · 10.25의 바로 앞뒤', () => {
  assert.equal(pressDepth(8.1), 0)
  assert.equal(pressDepth(8.11), 7)
  assert.equal(pressDepth(9.3), 7)
  assert.equal(pressDepth(9.31), 17)
  assert.equal(pressDepth(10.25), 17)
  assert.equal(pressDepth(10.26), 25)
})

test('걷기 구간(실제 wd초)을 타임라인 2.4초에 맞추고, 그 뒤는 실제 시간 · 끝(10.6)에서 멈춘다', () => {
  assert.equal(sceneTime(0, 4.8), 0)
  assert.equal(sceneTime(2.4, 4.8), 1.2)
  assert.equal(sceneTime(4.8, 4.8), 2.4)
  assert.equal(sceneTime(5.8, 4.8), 3.4)
  assert.equal(sceneTime(100, 4.8), SCENE_END)
})
