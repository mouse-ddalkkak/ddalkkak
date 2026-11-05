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
  api<{ serviceId: string; name: string }>('POST', `/api/services/requests/${encodeURIComponent(id)}/approve`)
export const ignoreRequest = (id: string) => api<void>('POST', `/api/services/requests/${encodeURIComponent(id)}/ignore`)
