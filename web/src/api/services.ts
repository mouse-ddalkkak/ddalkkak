import { api } from './client'

export type ConnectRequest = {
  id: string
  appName: string
  framework: string
  frameworkVersion: string
  host: string
  port: number
  pid: number
  javaVersion: string | null
  buildTool: string | null
  requestedAt: string
}

export const getConnectRequests = () => api<ConnectRequest[]>('GET', '/api/services/requests')
export const approveRequest = (id: string) =>
  api<{ serviceId: string; name: string }>('POST', `/api/services/requests/${encodeURIComponent(id)}/approve`, { handle: ['ALREADY_CONNECTED'] })
/** 해제 안내 · 409 모달에서 사용자가 확인했을 때만 (F-CONN-09). 검사 중이면 409 RUN_IN_PROGRESS → 화면이 그 자리에서 알린다 */
export const disconnectService = (id: string) =>
  api<void>('POST', `/api/services/${encodeURIComponent(id)}/disconnect`, { handle: ['RUN_IN_PROGRESS'] })
export const ignoreRequest = (id: string) => api<void>('POST', `/api/services/requests/${encodeURIComponent(id)}/ignore`)
