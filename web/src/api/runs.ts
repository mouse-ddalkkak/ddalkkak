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
/** 본문 없음 — 서버가 저장한 코드 연결 방식(GET /api/code-source의 last)으로 다시 가져온다 (-224 · -242). 검사 중 409는 화면이 처리한다 */
export const startPrepare = () =>
  api<{ runId: string }>('POST', '/api/runs/prepare', { handle: ['RUN_IN_PROGRESS'] })
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
/** 4 로그인 줄 (openapi AuthSetting, -230). 값은 오지 않고 가린 hint만 */
export type AuthSetting = {
  state: 'none' | 'ok' | 'expired' | 'key_lost'
  analysis: { loginApi: string | null; signupApi: string | null; fields: string[]; fieldsGuessed: boolean }
  mode: 'account' | 'token' | 'cookie' | null
  hint: string | null
  tokenAt: string | null
  lastUsedAt: string | null
}
export type AuthInput =
  | { mode: 'account'; create: true }
  | { mode: 'account'; username: string; password: string }
  | { mode: 'token'; token: string }
  | { mode: 'cookie'; cookie: string }
export const getAuth = (runId: string) => api<AuthSetting>('GET', `/api/runs/prepare/${encodeURIComponent(runId)}/auth`)
/** 로그인 실패 · 잘못 넣음 · 앱 꺼짐은 팝업 안에 문구로 (하단 바에 띄우지 않는다) */
export const putAuth = (runId: string, body: AuthInput) =>
  api<AuthSetting>('PUT', `/api/runs/prepare/${encodeURIComponent(runId)}/auth`, {
    body,
    handle: ['NO_LOGIN_API', 'TARGET_LOGIN_FAILED', 'BAD_REQUEST', 'SERVICE_OFFLINE'],
  })
/** 409 RUN_IN_PROGRESS는 화면이 팝업으로 (-214) */
export const startRun = (runId: string) => api<{ runId: string }>('POST', '/api/runs', { body: { runId }, handle: ['RUN_IN_PROGRESS'] })

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
/** 딸깍 재시작으로 멈춘 회차를 멈춘 시나리오부터 이어서 (-228). 409 RUN_IN_PROGRESS · SERVICE_OFFLINE은 래퍼 문구 그대로 */
export const resumeRun = (runId: string) => api<{ status: string }>('POST', '/api/runs/resume', { body: { runId } })
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
  /** 이 문제로 영역 점수에서 뺀 점수 — 위험 30 · 주의 10 (-247) */
  penalty: number
}
export type Result = {
  runId: string
  service: string
  /** 회차마다 저장하는 프로젝트 이름 — 기본은 service, ✎로 고친다 (F-HIST-05) */
  projectName: string
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
  projectName: string
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
/** 8 · 6 이름 ✎ — 그 회차의 이름만 바뀐다. 응답은 저장한 이름(앞뒤 공백을 뗀 값) */
export const renameRun = (runId: string, projectName: string) =>
  api<{ runId: string; projectName: string }>('PATCH', `/api/runs/${encodeURIComponent(runId)}`, { body: { projectName } })
