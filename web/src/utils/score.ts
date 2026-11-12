import type { Area } from '../api/runs'

export const AREA_NAME: Record<Area, string> = { perf: '성능', sec: '보안', code: '코드 품질' }
export const AREAS: Area[] = ['perf', 'sec', 'code']

/** 점수 색 — screens.md 비주얼: 0–49 coral / 50–89 amber / 90+ green / 미검사 회색 */
export function scoreColor(v: number | null | undefined) {
  if (v == null) return 'var(--ink-600)'
  return v < 50 ? 'var(--coral-400)' : v < 90 ? 'var(--amber-400)' : 'var(--green-400)'
}

/** 게이지 색 — 70 이상 amber · 85 이상 coral */
export function gaugeColor(pct: number) {
  return pct >= 85 ? 'var(--coral-400)' : pct >= 70 ? 'var(--amber-400)' : 'var(--green-400)'
}

export const scoreText = (v: number | null | undefined) => (v == null ? '--' : String(v))

/** 점수 근거 한 줄 — 영역 점수 = max(0, 100 − 문제별 감점 합) (F-RPT-04 · -247). 예: "100 − 30 − 10 = 60" */
export function scoreBasis(penalties: number[], score: number) {
  if (penalties.length === 0) return `감점 없음 = ${score}`
  return `100 − ${penalties.join(' − ')} = ${score}${100 - penalties.reduce((a, b) => a + b, 0) < 0 ? ' (0점 아래로는 내려가지 않아요)' : ''}`
}
