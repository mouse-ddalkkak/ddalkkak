import { test } from 'node:test'
import assert from 'node:assert/strict'
import { nextZoom } from '../src/utils/fitZoom.ts'

test('넘치지 않으면 zoom을 그대로 둔다', () => {
  assert.equal(nextZoom(0.9, 600, 0), 0.9)
  assert.equal(nextZoom(0.9, 600, -20), 0.9)
})

test('넘친 만큼 높이 비율로 줄인다', () => {
  assert.equal(nextZoom(0.9, 600, 60), 0.81)
})

test('최소 zoom 아래로는 줄이지 않는다', () => {
  assert.equal(nextZoom(0.9, 600, 500), 0.6)
})
