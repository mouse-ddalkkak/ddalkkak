import { api } from './client'

export type AiProvider = 'claude' | 'openai' | 'custom'
export type AiSettings = {
  provider: AiProvider | null
  baseUrl: string | null
  model: string | null
  keyMasked: string | null
  connected: boolean
}
export type AiInput = { provider: AiProvider; baseUrl: string | null; key: string; model: string }

export const getAiSettings = () => api<AiSettings>('GET', '/api/ai-settings')
export const putAiSettings = (input: AiInput) => api<AiSettings>('PUT', '/api/ai-settings', { body: input })
/** ok: false 는 오류가 아니라 응답 값 — 화면이 message를 보여 준다 */
export const checkAi = (input: AiInput) =>
  api<{ ok: boolean; shown: string | null; message: string }>('POST', '/api/ai-settings/check', { body: input })
