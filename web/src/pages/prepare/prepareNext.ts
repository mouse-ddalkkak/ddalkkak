// 화면 3 준비 응답 → 다음 행동 (docs/web.md §3 폴링 표). 폴링과 "기본 범위로 바로 가기" 응답이 같이 쓴다 — web/test/prepareNext.test.ts
import type { Prepare } from '../../api/runs'

export function prepareNext(d: Prepare): 'scope' | 'stop' | 'poll' {
  if (d.error) return 'stop'
  return d.done ? 'scope' : 'poll'
}
