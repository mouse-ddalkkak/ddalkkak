import { useEffect, useRef } from 'react'

/**
 * 화면이 열려 있는 동안만 다시 부른다 (docs/web.md §3 폴링)
 * - setInterval이 아니라 응답을 받은 뒤 setTimeout → 응답이 늦어도 요청이 겹치지 않는다
 * - 실패해도(래퍼가 던져도) 같은 주기로 다시. fn이 true를 돌려주면 멈춘다
 * - 화면을 떠나거나 deps(회차 id …)가 바뀌면 clearTimeout, 그 뒤 도착한 응답은 alive()가 false라 버린다
 */
export function usePolling(fn: (alive: () => boolean) => Promise<boolean | void>, ms: number, deps: unknown[]) {
  const latest = useRef(fn)
  latest.current = fn
  useEffect(() => {
    let ignore = false
    let timer: number | undefined
    const alive = () => !ignore
    const tick = async () => {
      let stop = false
      try {
        stop = (await latest.current(alive)) === true
      } catch {
        // 문구는 래퍼가 하단 바에 띄웠다
      }
      if (!ignore && !stop) timer = window.setTimeout(tick, ms)
    }
    tick()
    return () => {
      ignore = true
      window.clearTimeout(timer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
}
