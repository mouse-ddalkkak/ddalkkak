import { api } from './client'
import type { Scores } from './runs'

export type Home = {
  service: { id: string; name: string; host: string; port: number; connected: boolean; apiCount: number | null } | null
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
}

export const getHome = () => api<Home>('GET', '/api/home')
