import { api } from './client'

export type Setup = { needsAck: boolean; username: string; oneTimePassword: string | null }

export const getSetup = () => api<Setup>('GET', '/api/setup')
/** 0-1 "저장했어요" — 서버가 1회용 비밀번호 원문을 버린다 */
export const ackSetup = () => api<void>('POST', '/api/setup/ack')
