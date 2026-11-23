// 화면 3 "API 목록 받기" 아래 바뀐 API 목록 (F-API-04) — cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { diffLines } from '../src/pages/prepare/apiDiff.ts'

test('재검사에서 API 하나를 더하면 그 API가 "새로 생김"으로 나온다', () => {
  const apiDiff = { added: 1, removed: 0, changed: 0, apis: [{ api: 'POST /api/coupons', change: 'new' as const }] }
  assert.deepEqual(diffLines(apiDiff), [{ api: 'POST /api/coupons', text: '새로 생김' }])
})

test('없어짐 · 바뀜도 문구로 바꾼다', () => {
  const apiDiff = {
    added: 0,
    removed: 1,
    changed: 1,
    apis: [
      { api: 'DELETE /api/carts/{id}', change: 'removed' as const },
      { api: 'GET /api/orders', change: 'changed' as const },
    ],
  }
  assert.deepEqual(diffLines(apiDiff), [
    { api: 'DELETE /api/carts/{id}', text: '없어짐' },
    { api: 'GET /api/orders', text: '바뀜' },
  ])
})

test('첫 검사(apiDiff null)면 목록이 없다', () => {
  assert.deepEqual(diffLines(null), [])
})
