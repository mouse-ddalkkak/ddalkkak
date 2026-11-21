import { useLayoutEffect, type RefObject } from 'react'
import { nextZoom } from '../utils/fitZoom'

/**
 * 페이지가 창보다 길면 스크롤 대신 이 요소의 zoom을 max에서 줄여 맞춘다 (홈 · 3, -259).
 * 그릴 때마다(폴링으로 내용이 바뀔 때) · 창 크기가 바뀔 때 · 글꼴을 받은 뒤 다시 잰다
 */
export function useFitZoom(ref: RefObject<HTMLElement | null>, max: number) {
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const fit = () => {
      let k = max
      el.style.zoom = String(k)
      // zoom이 줄면 높이도 거의 같은 비율로 준다. 반올림 오차는 몇 번 더 재서 맞춘다
      for (let i = 0; i < 3; i++) {
        const next = nextZoom(k, el.getBoundingClientRect().height, document.documentElement.scrollHeight - innerHeight)
        if (next === k) break
        el.style.zoom = String((k = next))
      }
    }
    fit()
    addEventListener('resize', fit)
    document.fonts?.ready.then(fit)
    return () => removeEventListener('resize', fit)
  })
}
