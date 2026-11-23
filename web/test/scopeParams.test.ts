// 화면 4 설정 팝업 · 직접 추가 값 (F-SCOPE-03~06) — cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { PRESETS, TOOL_DEFAULT, estimate, keepOffChoices, scopeBody, setPartOn, toggleCard } from '../src/utils/scopeParams.ts'
import type { Scope } from '../src/api/runs.ts'

test('세기 4단계는 functional-spec F-SCOPE-03 숫자', () => {
  assert.deepEqual(
    PRESETS.map((p) => [p.label, p.usersFrom, p.usersTo]),
    [
      ['가볍게', 10, 50],
      ['보통', 50, 200],
      ['세게', 100, 500],
      ['극한', 200, 1000],
    ],
  )
})

test('도구 기본값은 run-flow.md 직접 추가 기본값', () => {
  assert.deepEqual(TOOL_DEFAULT.k6, { usersFrom: 50, usersTo: 200, durationMin: 3, shape: 'ramp', failP95Ms: 500, failErrorRate: 0.01 })
  assert.deepEqual(TOOL_DEFAULT.zap, { range: 'default' })
  assert.deepEqual(TOOL_DEFAULT.semgrep, { severity: 'major' })
})

test('예상 요청 수 · 부담 · 안내는 서버 Estimate와 같은 식', () => {
  assert.deepEqual(estimate({ usersFrom: 50, usersTo: 200, durationMin: 3 }), { requests: 36000, load: 'medium', advice: '개발 서버 권장' })
  assert.equal(estimate({ usersFrom: 10, usersTo: 80, durationMin: 1 }).load, 'low')
  assert.equal(estimate({ usersFrom: 10, usersTo: 80, durationMin: 1 }).advice, '운영 서버도 괜찮아요')
  assert.equal(estimate({ usersFrom: 100, usersTo: 700, durationMin: 1 }).load, 'high')
  assert.equal(estimate({ usersFrom: 200, usersTo: 1000, durationMin: 1 }).load, 'very_high')
  assert.equal(estimate({ usersFrom: 200, usersTo: 1000, durationMin: 1 }).advice, '운영 서버에서는 피하세요')
})

const item = (key: string, area: 'perf' | 'sec', included: boolean, params: Record<string, unknown>) => ({
  key,
  area,
  tool: area === 'perf' ? 'k6' : 'zap',
  title: key,
  why: '',
  params,
  recommended: true,
  excluded: false,
  included,
})

const scope: Scope = {
  parts: [
    { area: 'perf', on: true, offReason: null, items: [item('perf-1', 'perf', true, { usersFrom: 100, usersTo: 500 })] },
    { area: 'sec', on: false, offReason: null, items: [item('sec-1', 'sec', true, { range: 'wide' })] },
    { area: 'code', on: false, offReason: '코드 없음', items: [] },
  ],
  estimate: { scenarioCount: 1, requests: 0, durationMin: 3, load: 'medium', advice: '' },
}

test('PUT 본문: 영역 스위치 · 카드 params · 꺼진 영역 카드는 included false', () => {
  assert.deepEqual(scopeBody(scope), {
    areas: { perf: true, sec: false, code: false },
    items: [
      { key: 'perf-1', included: true, params: { usersFrom: 100, usersTo: 500 } },
      { key: 'sec-1', included: false, params: { range: 'wide' } },
    ],
    added: [],
  })
})

test('PUT 본문: 직접 추가는 added로 간다', () => {
  const added = [{ area: 'perf' as const, title: '쿠폰 발급', api: 'POST /api/coupons', params: {} }]
  assert.deepEqual(scopeBody(scope, added).added, added)
})

test('저장 응답 반영: 꺼 둔 영역은 카드 선택을 화면 값 그대로 둔다 (다시 켜면 돌아온다)', () => {
  // 서버는 꺼진 영역 카드를 모두 included: false로 돌려준다(ScopeTest 영역_스위치를_끄면…)
  const res: Scope = { ...scope, parts: scope.parts.map((p) => ({ ...p, items: p.items.map((i) => ({ ...i, included: false })) })) }
  const kept = keepOffChoices(res, scope)
  assert.deepEqual(
    kept.parts.map((p) => p.items.map((i) => [i.key, i.included])),
    [[['perf-1', false]], [['sec-1', true]], []],
  )
})

test('영역 스위치: 켜면 그 영역 카드가 전부(AI가 뺀 카드도) 켜지고 끄면 전부 꺼진다 (-252)', () => {
  const part = { area: 'perf' as const, on: false, offReason: null, items: [
    item('perf-1', 'perf', false, {}),
    { ...item('perf-2', 'perf', false, {}), recommended: false },
    { ...item('perf-3', 'perf', false, {}), excluded: true },
  ] }
  const on = setPartOn(part, true)
  assert.equal(on.on, true)
  assert.deepEqual(on.items.map((i) => i.included), [true, true, true])
  const off = setPartOn(on, false)
  assert.equal(off.on, false)
  assert.deepEqual(off.items.map((i) => i.included), [false, false, false])
})

test('카드 토글: 하나라도 켜면 스위치가 켜지고 다 끄면 꺼진다 — 꺼진 영역 카드도 바로 켠다 (-252)', () => {
  const off = { area: 'perf' as const, on: false, offReason: null, items: [item('perf-1', 'perf', false, {}), item('perf-2', 'perf', false, {})] }
  const one = toggleCard(off, 'perf-2')
  assert.equal(one.on, true)
  assert.deepEqual(one.items.map((i) => i.included), [false, true])
  const two = toggleCard(one, 'perf-1')
  assert.equal(two.on, true)
  const none = toggleCard(toggleCard(two, 'perf-1'), 'perf-2')
  assert.equal(none.on, false)
  assert.deepEqual(none.items.map((i) => i.included), [false, false])
})

test('영역 스위치 · 카드: 코드 없는 회차의 코드 품질은 켜지지 않는다 (OFF "코드 없음" 고정, run-flow.md · !94 리뷰 2-1)', () => {
  const noCode = { area: 'code' as const, on: false, offReason: '코드 없음', items: [
    { ...item('code-1', 'sec', false, {}), area: 'code' as const, tool: 'semgrep' },
  ] }
  assert.deepEqual(setPartOn(noCode, true), noCode)
  assert.deepEqual(toggleCard(noCode, 'code-1'), noCode)
})
