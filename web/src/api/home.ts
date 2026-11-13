import { api } from './client'
import type { Scores } from './runs'

export type Home = {
  service: {
    id: string
    name: string
    host: string
    port: number
    connected: boolean
    apiCount: number | null
    /** 연결 점검 연속 실패 (-223) */
    disconnect: { failures: number; needsConfirm: boolean; lastCheckedAt: string | null } | null
  } | null
  health: {
    cpu: number
    mem: { usedMb: number; totalMb: number }
    disk: { usedGb: number; totalGb: number }
    uptimeSec: number
    serverTime: string
  } | null
  lastRun: {
    runId: string
    at: string
    scores: Scores
    topDanger: string | null
    topWarn: string | null
    passed: number
    skipped: { count: number; reason: string | null }
  } | null
  /** 상단 검사 상태 칩 (-214) · 이어서 검사(-228). 연결 안 됐으면 null */
  run: {
    state: 'none' | 'running' | 'interrupted' | 'done'
    runId: string | null
    interruptedAt: { step: 'check' | 'auth' | 'sec' | 'perf' | 'code' | 'report'; name: string } | null
  } | null
  /** 같은 컴퓨터의 앱이 딸깍을 부를 주소. 7777이 아니면 0-3 ①이 DDALKKAK_URL로 안내 (-151) */
  kitUrl: string
}

export const getHome = () => api<Home>('GET', '/api/home')

/** 1 홈 도구 상태 팝업 (-229) */
export type Tool = {
  name: 'k6' | 'zap' | 'semgrep'
  image: string
  state: 'docker_unavailable' | 'not_installed' | 'not_runnable' | 'recent_error' | 'ok'
  installed: boolean
  runnable: boolean
  checkedAt: string
  lastError: { stage: 'pull' | 'run'; reason: string; cause: string; runId: string; at: string } | null
  fix: string | null
}
export const getTools = () => api<{ tools: Tool[] }>('GET', '/api/tools')
