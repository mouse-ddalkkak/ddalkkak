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
