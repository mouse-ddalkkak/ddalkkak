// 화면 3 "API 목록 받기" 아래 바뀐 API 목록 (F-API-04). 첫 검사(apiDiff null)는 목록 없음 — web/test/apiDiff.test.ts
import type { Prepare } from '../../api/runs'

const CHANGE_TEXT = { new: '새로 생김', removed: '없어짐', changed: '바뀜' } as const

export const diffLines = (d: Prepare['apiDiff']) => d?.apis.map((a) => ({ api: a.api, text: CHANGE_TEXT[a.change] })) ?? []
