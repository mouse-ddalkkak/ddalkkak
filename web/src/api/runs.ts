import { api, openEvents } from './client'
import { scopeBody, type Added } from '../utils/scopeParams'

export type Area = 'perf' | 'sec' | 'code'
export type Scores = Record<Area, number | null>

// ── 3 준비 ──
export type PrepareRow = { name: string; state: 'done' | 'running' | 'waiting' | 'skipped'; detail: string | null }
export type Prepare = {
  rows: PrepareRow[]
  pct: number
  done: boolean
  apiCount: number | null
  /** 첫 검사면 null. apis는 바뀐 API (새로 생김 · 없어짐 · 바뀜) */
  apiDiff: { added: number; removed: number; changed: number; apis: { api: string; change: 'new' | 'removed' | 'changed' }[] } | null
  aiMode: 'llm' | 'default' | null
  error: { code: string; message: string } | null
}
export const startPrepare = (codeJobId: string | null) =>
  api<{ runId: string }>('POST', '/api/runs/prepare', { body: { codeJobId } })
export const getPrepare = (runId: string) => api<Prepare>('GET', `/api/runs/prepare/${encodeURIComponent(runId)}`)
/** 3 "AI 추천 없이 기본 범위로 바로 가기" — 409 PREPARE_ENDED는 화면이 폴링을 이어 가며 처리한다 */
export const skipToDefaultScope = (runId: string) =>
  api<Prepare>('POST', `/api/runs/prepare/${encodeURIComponent(runId)}/use-default`, { handle: ['PREPARE_ENDED'] })

// ── 4 범위 ──
export type ScopeItem = {
  key: string
  area: Area
  tool: string
  title: string
  api?: string
  target?: string
  why: string
  params: Record<string, unknown>
  recommended: boolean
  excluded: boolean
  excludeReason?: string
  included: boolean
  // 서버가 null이면 빼고 보낸다
  source?: 'llm' | 'default' | 'manual'
  /** 사용자가 바꾸기 전 AI 값 — ✎ "AI 추천으로". 직접 추가 카드는 null (-180) */
  aiParams: Record<string, unknown> | null
}
export type ScopePart = { area: Area; on: boolean; offReason: string | null; items: ScopeItem[] }
export type Scope = {
  parts: ScopePart[]
  estimate: { scenarioCount: number; requests: number; durationMin: number; load: string; advice: string }
}
export const getScope = (runId: string) => api<Scope>('GET', `/api/runs/prepare/${encodeURIComponent(runId)}/scope`)
export const putScope = (runId: string, scope: Scope, added: Added[] = []) =>
  api<Scope>('PUT', `/api/runs/prepare/${encodeURIComponent(runId)}/scope`, { body: scopeBody(scope, added) })
/** 4 "+ 추가" 대상 후보 — 이번 회차 API 목록 전부 (카드 없는 API 포함, 성능 GET 거르기는 화면이) (-179) */
export const getPrepareApis = (runId: string) =>
  api<{ apis: { api: string; change: 'new' | 'changed' | null }[] }>('GET', `/api/runs/prepare/${encodeURIComponent(runId)}/apis`)
export const startRun =(runId: string) => api<{ runId: string }>('POST', '/api/runs', { body: { runId } })

// ── 5 진행 ──
export type StepState = 'done' | 'running' | 'waiting' | 'skipped'
export type Progress = {
  status: 'running' | 'done' | 'partial' | 'stopped' | 'failed'
  pct: number
  leftMin: number | null
  steps: {
    step: string
    name: string
    state: StepState
    skipReason?: string | null
    items: { key: string; title: string; state: StepState; skipReason?: string }[]
  }[]
  logs: { ts: string; tag: string; text: string }[]
}
export const getProgress = (runId: string) => api<Progress>('GET', `/api/runs/${encodeURIComponent(runId)}/progress`)
export const stopRun = (runId: string) => api<{ status: string }>('POST', `/api/runs/${encodeURIComponent(runId)}/stop`)
export const retryRun = (runId: string) => api<{ status: string }>('POST', `/api/runs/${encodeURIComponent(runId)}/retry`)
export const finishPartial = (runId: string) =>
  api<{ status: string }>('POST', `/api/runs/${encodeURIComponent(runId)}/finish-partial`)
/** 도구 원문 로그 전체 (text/plain) */
export const getRunLogs = (runId: string) => api<string>('GET', `/api/runs/${encodeURIComponent(runId)}/logs`)

// SSE 이벤트 4종의 data (event: progress · log · error · done)
export type ProgressEvent = { pct: number; leftMin: number | null; step: string; item: string | null }
export type LogEvent = { ts: string; tag: string; text: string }
export type StopEvent = { step: string; item: string; title: string; ts: string; text: string; lines: string[] }
export const openRunEvents = (runId: string) => openEvents(`/api/runs/${encodeURIComponent(runId)}/events`)

// ── 6 결과 · 7 문제 ──
export type Issue = {
  id: string
  itemKey: string
  area: Area
  level: 'danger' | 'warn'
  certainty: 'confirmed' | 'suspected'
  title: string
  tech: string
  impact: string
  asIs: { value: string; label: string }
  toBe: { value: string; label: string }
  metrics: { name: string; now: string; target: string }[]
  suspectReason: string | null
  where: string
  cause: string
  fix: { recommended: string; alternative: string | null }
  passCriteria: string
}
export type Result = {
  runId: string
  service: string
  at: string
  durationMin: number
  scenarioCount: number
  itemCount: number
  status: string
  codeSource: { type: string; commit: string | null } | null
  scores: Scores
  summary: Record<Area, { line: string; pass: number; fail: number; skipReason: string | null }>
  issues: Issue[]
  passed: { area: Area; name: string; value: string }[]
  // AI 서버가 두 번 다 답하지 않았으면 오류 (F-RPT-03) → 6 "해석하지 못했어요 · 다시 해석"
  reportError: { code: string; message: string } | null
}
export type IssueDetail = Issue & {
  api: string
  code: { file: string; line: number; commit: string | null; branch: string | null; lines: string[]; decompiled: boolean } | null
  evidence: string[]
  repro: { usersFrom: number | null; usersTo: number | null; durationMin: number | null; shape: string | null; api: string | null; testAccount: string | null } | null
  prev: string | null
  next: string | null
}
export const getResult = (runId: string) => api<Result>('GET', `/api/runs/${encodeURIComponent(runId)}`)
export const retryReport = (runId: string) => api<Result>('POST', `/api/runs/${encodeURIComponent(runId)}/report`)
export const getIssue = (runId: string, issueId: string) =>
  api<IssueDetail>('GET', `/api/runs/${encodeURIComponent(runId)}/issues/${encodeURIComponent(issueId)}`)
export const makePrompt = (runId: string, issueIds: string[], includeLogs = false) =>
  api<{ text: string }>('POST', `/api/runs/${encodeURIComponent(runId)}/prompts`, { body: { issueIds, includeLogs } })

// ── 8 이력 ──
export type HistoryRun = {
  runId: string
  at: string
  status: string
  tags: string[]
  scenarioCount: number
  codeSource: string | null
  scores: Scores
  fixCount: number
}
export type History = { total: number; runs: HistoryRun[]; trend: Record<Area, (number | null)[]> & { at: string[] } }
export const getHistory = () => api<History>('GET', '/api/runs')
