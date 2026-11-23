// 셀렉트 후보 거르기 (utils/selectFilter.ts, -257). cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { filterOptions } from '../src/utils/selectFilter.ts'

const apis = ['GET /api/products', 'GET /api/orders', 'POST /api/orders']

test('filterOptions: 입력이 비면 전부, 대소문자 없이 들어 있는 것만', () => {
  assert.deepEqual(filterOptions(apis, ''), apis)
  assert.deepEqual(filterOptions(apis, '  '), apis)
  assert.deepEqual(filterOptions(apis, 'orders'), ['GET /api/orders', 'POST /api/orders'])
  assert.deepEqual(filterOptions(apis, 'post'), ['POST /api/orders'])
  assert.deepEqual(filterOptions(apis, 'coupon'), [])
})

test('filterOptions: 후보와 똑같이 적었으면 목록을 다시 다 보여 준다 (고른 뒤 다른 것으로 바꿀 수 있게)', () => {
  assert.deepEqual(filterOptions(apis, 'GET /api/orders'), apis)
})
