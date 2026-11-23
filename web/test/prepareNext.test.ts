// 화면 3 준비 응답 → 다음 행동 (폴링 · "기본 범위로 바로 가기" 공통) — cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { prepareNext } from '../src/pages/prepare/prepareNext.ts'

const base = { rows: [], pct: 30, done: false, apiCount: null, apiDiff: null, aiMode: null, error: null }

test('use-default가 done: true로 오면 AI 답을 기다리지 않고 4로 간다', () => {
  assert.equal(prepareNext({ ...base, pct: 100, done: true, aiMode: 'default' }), 'scope')
})

test('done: false(API 목록 받는 중)면 폴링을 이어 간다', () => {
  assert.equal(prepareNext(base), 'poll')
})

test('error가 있으면 멈춘다 (done이어도)', () => {
  assert.equal(prepareNext({ ...base, done: true, error: { code: 'X', message: 'm' } }), 'stop')
})
