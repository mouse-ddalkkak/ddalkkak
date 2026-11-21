// 0-1 설치 ↔ 0-2 로그인 → 1 · 0-3 화면 전환 (Pixel Flow open0a · toLogin · login, -259). "움직임 줄이기"면 효과 없이 바로 끝난다
// 곡선은 Pixel Flow와 다르다: 나타날 때는 ease-out(바로 보이게), 사라질 때는 ease-in(끝에서 기다리지 않게).
// Pixel Flow의 양끝 느린 곡선 · 1초 ease-in은 전환 사이에 빈 화면 · 멈춘 듯한 구간이 0.2~0.5초 생겼다
const IN = 'cubic-bezier(.2,.8,.4,1)'
const OUT = 'cubic-bezier(.6,0,.8,.4)'
const still = () => !document.body.animate || matchMedia('(prefers-reduced-motion: reduce)').matches

/** 자식들이 가운데에서 펼쳐지며(in) 나타나거나 가운데로 모이며(out) 사라진다 */
export function merge(box: HTMLElement, dir: 'in' | 'out'): Promise<unknown> {
  if (still()) return Promise.resolve()
  const c = box.getBoundingClientRect(), cx = c.left + c.width / 2
  return Promise.all(
    [...box.children].map((el) => {
      const r = el.getBoundingClientRect()
      const away = { transform: `translateX(${cx - (r.left + r.width / 2)}px) scale(.6)`, opacity: 0 }
      const here = { transform: 'none', opacity: 1 }
      return el.animate(dir === 'in' ? [away, here] : [here, away], dir === 'in' ? { duration: 520, easing: IN, fill: 'backwards' } : { duration: 420, easing: OUT, fill: 'forwards' }).finished
    }),
  )
}

/** 작게 시작해 살짝 커졌다 제자리로 */
export function pop(el: HTMLElement) {
  if (still()) return
  el.animate([{ transform: 'scale(.6)', opacity: 0 }, { transform: 'scale(1.03)', opacity: 1, offset: 0.7 }, { transform: 'none', opacity: 1 }], { duration: 440, easing: 'ease-out' })
}

/** 살짝 커졌다 작아지며 사라진다 (로그인 성공 뒤) */
export function shrink(el: HTMLElement): Promise<unknown> {
  if (still()) return Promise.resolve()
  // 구간마다 곡선을 따로: 누르자마자 커지고(ease-out) 이어서 줄어든다(ease-in)
  return el.animate(
    [
      { transform: 'none', opacity: 1, easing: 'ease-out' },
      { transform: 'scale(1.03)', opacity: 1, offset: 0.25, easing: OUT },
      { transform: 'scale(.6)', opacity: 0 },
    ],
    { duration: 600, fill: 'forwards' },
  ).finished
}

/** 아래에서 살짝 올라오며 나타난다 (로그인 뒤 1 · 0-3) */
export function enter(el: HTMLElement | null) {
  // 0-3은 첫 렌더에 감싸는 칸이 없을 수 있다(null). 오류로 앱 전체가 내려가지 않게 그냥 넘어간다
  if (!el || still()) return
  el.animate([{ transform: 'translateY(16px) scale(.98)', opacity: 0 }, { transform: 'none', opacity: 1 }], { duration: 360, easing: IN })
}

// 전환 효과는 앞 화면에서 넘어왔을 때만 (0-1 → 로그인 'login', 로그인 → 1 · 0-3 'afterLogin').
// 401 · 로그아웃 · 새로고침으로 온 화면은 그대로. 1초 안에 그려진 화면이면 효과를 낸다 —
// 로그인 → 0-3 → 1처럼 바로 한 번 더 넘어가도 마지막 화면까지 효과가 이어진다
let mark: { to: string; at: number } | null = null
export const markEnter = (to: 'login' | 'afterLogin') => void (mark = { to, at: performance.now() })
export const shouldEnter = (to: 'login' | 'afterLogin') => !!mark && mark.to === to && performance.now() - mark.at < 1000
