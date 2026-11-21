import { api } from './client'

/** 1 홈 새 버전 띠 (F-HOME-06, -221). current null = 소스 빌드 · latest null = 새 버전 없음 · canUpdate = 설치기로 깐 딸깍(버튼) */
export type UpdateStatus = { current: string | null; latest: string | null; canUpdate: boolean }

export const getUpdate = () => api<UpdateStatus>('GET', '/api/update')
/** 새 버전 설치기를 띄운다 — 1분쯤 서버가 꺼졌다 새 버전으로 뜬다(세션이 사라져 0-2). 409 · 502는 래퍼가 하단 바에 띄운다 */
export const startUpdate = () => api<{ version: string }>('POST', '/api/update')
