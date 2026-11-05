// 화면 4 설정 팝업 · 직접 추가 값 (F-SCOPE-03~06) — 순수 모듈, web/test/scopeParams.test.ts
import type { Area, Scope, ScopePart } from '../api/runs'

/** 세기 4단계 (functional-spec F-SCOPE-03 · screens.md 4 설정 팝업) */
export const PRESETS = [
  { label: '가볍게', sub: '평소', usersFrom: 10, usersTo: 50 },
  { label: '보통', sub: '피크 · AI 추천', usersFrom: 50, usersTo: 200 },
  { label: '세게', sub: '세일 날', usersFrom: 100, usersTo: 500 },
  { label: '극한', sub: '버티나 보기', usersFrom: 200, usersTo: 1000 },
]

export const SHAPES = [
  { value: 'constant', label: '일정' },
  { value: 'ramp', label: '점진' },
  { value: 'spike', label: '급증' },
  { value: 'step', label: '계단' },
]
export const RANGES = [
  { value: 'default', label: '기본', sub: '알려진 패턴 · 120회' },
  { value: 'wide', label: '넓게', sub: '변형 포함 · 600회' },
  { value: 'full', label: '전체', sub: '모든 조합 · 2,400회' },
]
export const SEVERITIES = [
  { value: 'critical', label: '치명만' },
  { value: 'major', label: '주요까지' },
  { value: 'all', label: '전부' },
]

/** 도구 기본값 — 서버가 직접 추가의 빈 칸을 채우는 값과 같다 (run-flow.md "직접 추가") */
export const TOOL_DEFAULT: Record<'k6' | 'zap' | 'semgrep', Record<string, unknown>> = {
  k6: { usersFrom: 50, usersTo: 200, durationMin: 3, shape: 'ramp', failP95Ms: 500, failErrorRate: 0.01 },
  zap: { range: 'default' },
  semgrep: { severity: 'major' },
}

export type Load = 'low' | 'medium' | 'high' | 'very_high'
export const LOAD_NAME: Record<Load, string> = { low: '낮음', medium: '중간', high: '높음', very_high: '매우 높음' }

/** 성능 카드 하나의 예상치 — 서버 scope/Estimate.java와 같은 식 (F-SCOPE-06) */
export function estimate(p: { usersFrom: number; usersTo: number; durationMin: number }) {
  const requests = Math.round(((p.usersFrom + p.usersTo) / 2) * p.durationMin * 60 * 1.6)
  const load: Load = p.usersTo <= 80 ? 'low' : p.usersTo <= 300 ? 'medium' : p.usersTo <= 700 ? 'high' : 'very_high'
  const advice = load === 'low' ? '운영 서버도 괜찮아요' : load === 'medium' ? '개발 서버 권장' : '운영 서버에서는 피하세요'
  return { requests, load, advice }
}

export type Added = { area: Area; title: string; api?: string; target?: string; params: Record<string, unknown> }

/** PUT …/scope 본문. 서버는 영역 스위치 → 카드 → 직접 추가 순서로 적용하므로 꺼진 영역의 카드도 included: false로 보낸다 */
export function scopeBody(scope: Scope, added: Added[] = []) {
  return {
    areas: Object.fromEntries(scope.parts.map((p) => [p.area, p.on])),
    items: scope.parts.flatMap((p) => p.items.map((i) => ({ key: i.key, included: p.on && i.included, params: i.params }))),
    added,
  }
}

/** 저장(PUT) 응답을 화면에 반영할 때 꺼 둔 영역은 카드 선택을 화면 값 그대로 둔다.
 *  서버는 꺼진 영역 카드를 모두 included: false로 돌려주므로, 그대로 덮으면 다시 켜도 "0개"가 된다 */
export function keepOffChoices(res: Scope, before: Scope): Scope {
  return {
    ...res,
    parts: res.parts.map((p) => {
      const was = before.parts.find((b) => b.area === p.area)
      if (!was || was.on) return p
      const chosen = new Map(was.items.map((i) => [i.key, i.included]))
      return { ...p, items: p.items.map((i) => ({ ...i, included: chosen.get(i.key) ?? i.included })) }
    }),
  }
}

/** 코드 없는 회차의 코드 품질 — 서버가 OFF "코드 없음"으로 고정하고 카드를 세지 않는다 (run-flow.md · F-SCOPE-01) */
export const noCode = (part: ScopePart) => part.offReason === '코드 없음'

/** 영역 스위치 켜기. 포함된 카드가 하나도 없으면 AI 추천 카드(recommended && !excluded)를, 그것도 없으면 그 영역 카드를 모두 넣는다.
 *  서버 ScenarioStore.update와 같은 규칙(run-flow.md) — 다시 연 화면은 GET이 꺼진 영역 카드를 모두 빠진 채 주므로 화면이 되살려 보낸다 */
export function turnOn(part: ScopePart): ScopePart {
  if (noCode(part)) return part
  if (part.items.some((i) => i.included)) return { ...part, on: true }
  const back = part.items.some((i) => i.recommended && !i.excluded)
  return { ...part, on: true, items: part.items.map((i) => ({ ...i, included: !back || (i.recommended && !i.excluded) })) }
}
