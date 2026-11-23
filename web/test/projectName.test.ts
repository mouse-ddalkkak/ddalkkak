// 8 · 6 프로젝트 이름 고치기 입력 규칙 (utils/projectName.ts, F-HIST-05 · -232). cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { PROJECT_NAME_MAX, cleanProjectName } from '../src/utils/projectName.ts'

test('앞뒤 공백을 떼고 그 값을 돌려준다', () => {
  assert.equal(cleanProjectName('  주문 서비스 v2  '), '주문 서비스 v2')
  assert.equal(cleanProjectName('order-api'), 'order-api')
})

test('비었거나 공백뿐이면 null — 서버가 400으로 거절하는 값을 보내지 않는다', () => {
  assert.equal(cleanProjectName(''), null)
  assert.equal(cleanProjectName('   \t '), null)
})

test(`${PROJECT_NAME_MAX}자까지 되고 넘으면 null`, () => {
  assert.equal(PROJECT_NAME_MAX, 100)
  assert.equal(cleanProjectName('가'.repeat(100)), '가'.repeat(100))
  assert.equal(cleanProjectName('가'.repeat(101)), null)
  assert.equal(cleanProjectName(` ${'가'.repeat(100)} `), '가'.repeat(100), '공백을 뗀 뒤 길이로 센다')
})
