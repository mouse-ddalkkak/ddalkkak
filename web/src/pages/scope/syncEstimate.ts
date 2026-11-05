// 화면 4 토글 뒤 예상 시간 (F-SCOPE-02 · F-SCOPE-06, -191) — React 밖에 두어 web/test/syncEstimate.test.ts가 가짜 put으로 돌린다
// type import만 둔다 (node --test가 이 파일을 그대로 실행한다)
import type { Scope } from '../../api/runs'

type Estimate = Scope['estimate']

/** 토글마다 schedule(다음 상태). 연달아 누르면 마지막 것만 delayMs 뒤 PUT하고, 응답의 estimate만 apply에 넘긴다.
 *  늦게 온 옛 응답 · cancel 뒤 응답은 버린다. PUT 실패는 래퍼가 문구를 띄우니 여기서는 아무것도 바꾸지 않는다 */
export function syncEstimate<S>({
  put,
  apply,
  delayMs = 400,
}: {
  put: (scope: S) => Promise<{ estimate: Estimate }>
  apply: (estimate: Estimate) => void
  delayMs?: number
}) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let seq = 0
  const cancel = () => {
    clearTimeout(timer)
    seq++
  }
  const schedule = (scope: S) => {
    cancel()
    const n = seq
    timer = setTimeout(() => {
      put(scope).then(
        (res) => n === seq && apply(res.estimate),
        () => {},
      )
    }, delayMs)
  }
  return { schedule, cancel }
}
