import type { Area, Scores } from '../api/runs'

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
/**
 * 8 이력 카드 증감 (-256) — 최신 회차 점수 − 그 앞에서 마지막으로 잰 회차 점수. runs는 GET /api/runs 처럼 최신순.
 * 앞 회차가 미검사여도 건너뛰고, 막대(최근 4번) 밖의 회차까지 본다. 앞에 잰 회차가 없으면 "첫 측정", 최신이 미검사면 null
 */
export function scoreChange(runs: { scores: Scores }[], area: Area): number | '첫 측정' | null {
  const last = runs[0]?.scores[area] ?? null
  if (last == null) return null
  const prev = runs.slice(1).find((r) => r.scores[area] != null)?.scores[area] ?? null
  return prev == null ? '첫 측정' : last - prev
}

export function scoreBasis(penalties: number[], score: number) {
  if (penalties.length === 0) return `감점 없음 = ${score}`
  return `100 − ${penalties.join(' − ')} = ${score}${100 - penalties.reduce((a, b) => a + b, 0) < 0 ? ' (0점 아래로는 내려가지 않아요)' : ''}`
}
