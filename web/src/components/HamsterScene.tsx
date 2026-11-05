import { useEffect, useRef } from 'react'
import walk from '../assets/img/astro-walk.png'
import { pressDepth, sceneTime, SCENE_END } from '../utils/hamScene'
import s from './HamsterScene.module.css'

const H = 110, W = 79
const cl = (x: number) => (x < 0 ? 0 : x > 1 ? 1 : x)
const ease = (x: number) => { x = cl(x); return x * x * (3 - 2 * x) }
const lerp = (a: number, b: number, k: number) => a + (b - a) * k
type Pose = [x: number, y: number, frame: number, flip: boolean]

/**
 * 0-0 소개 연출 (screens.md §2 0-0): 햄스터 2마리가 걸어와 사다리를 세우고 올라가 버튼 위에 착지 · 말풍선.
 * Pixel Flow runHamScene을 그대로 옮겼다. 하단 밴드에 두고 `[data-ham-target]` 버튼(0-0 Intro)을 찾아 좌표 · 눌림을 맞춘다
 */
export default function HamsterScene() {
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = ref.current!
    const [lad, A, B, bub] = Array.from(el.children) as HTMLElement[]
    const setH = (e: HTMLElement, [x, y, f, flip]: Pose) => {
      e.style.transform = `translate(${x - W / 2}px, ${y - H}px)` + (flip ? ' scaleX(-1)' : '')
      e.style.backgroundPosition = `${(f * 100) / 3}% 0`
      e.style.opacity = '1'
    }
    let raf = 0, p = 0, v = 0, lastT = 0, doneAt = -1
    const t0 = performance.now()
    // 움직임 줄이기 설정이면 마지막 장면만 한 번 그린다 (base.css가 CSS 애니메이션을 끄는 것과 같이)
    const still = matchMedia('(prefers-reduced-motion: reduce)').matches

    const frame = (now: number) => {
      raf = requestAnimationFrame(frame)
      const btn = document.querySelector<HTMLElement>('[data-ham-target]')
      if (!btn?.parentElement || !el.offsetWidth) return
      // .root zoom 보정: 화면 px → 이 요소 안 px
      const er = el.getBoundingClientRect(), z = er.width / el.offsetWidth || 1
      const pr = btn.parentElement.getBoundingClientRect(), sc = pr.width / 460 / z
      const P = (x: number, y: number): [number, number] => [(pr.left - er.left) / z + x * sc, (pr.top - er.top) / z + y * sc]
      if (still) btn.style.translate = `0 ${pressDepth(SCENE_END)}px` // 눌린 버튼 위에 서도록 재기 전에 누른다
      const off = (btn.getBoundingClientRect().top - pr.top) / z
      const [capX, capY] = P(230, 150), top = P(60, 150)
      const footX = top[0] + top[1] * 0.42, L = Math.hypot(top[0] - footX, top[1]), ang = Math.atan2(top[1], top[0] - footX)
      const sx = -L - 40, wd = Math.max(2.4, (footX - sx) / 260), rt = (now - t0) / 1000, t = still ? SCENE_END : sceneTime(rt, wd)
      if (t < lastT) { p = 0; v = 0 }
      lastT = t
      const wf = Math.floor(rt / 0.15) % 4, cf = Math.floor(t / 0.11) % 4
      const hh0 = H * 0.52, hh1 = H * 0.92, d0 = L - 34, dEnd = hh1 / Math.tan(-ang)
      let lx: number, ly: number, la: number, a: Pose, b: Pose

      if (t < 2.4) { // 사다리를 들고 걸어온다
        lx = lerp(sx, footX, t / 2.4); ly = -hh0 + (wf % 2) * 2; la = 0
        b = [lx + 34, 0, wf, false]; a = [lx + d0, 0, wf, false]
      } else if (t < 2.9) { // 내려놓는다
        const k = ease((t - 2.4) / 0.5); lx = footX; ly = lerp(-hh0, 0, k)
        la = Math.atan2(-lerp(hh0, hh1, k) - ly, d0)
        b = [lerp(footX + 34, footX - 30, k), 0, 0, true]; a = [footX + d0, 0, 0, false]
      } else if (t < 4.8) { // 세운다
        const k = ease((t - 2.9) / 1.7); lx = footX; ly = 0
        la = t < 4.6 ? Math.atan2(-hh1, lerp(d0, dEnd, k)) : ang
        a = [footX + lerp(d0, dEnd, k), 0, t < 4.6 ? wf : 0, true]; b = [footX - 30, 0, 0, false]
      } else { // 차례로 올라가 버튼 위로 점프
        lx = footX; ly = 0; la = ang
        const climb = (st: number, from: number): Pose | null => {
          if (t < st) return [from, 0, 0, from > footX]
          if (t < st + 0.5) return [lerp(from, footX, (t - st) / 0.5), 0, wf, from > footX]
          if (t < st + 2.5) { const q = (t - st - 0.5) / 2; return [lerp(footX, top[0], q) - 4, lerp(0, top[1], q), cf, false] }
          return null
        }
        const jump = (st: number, to: [number, number]): Pose => {
          const q = cl((t - st) / 0.6), x = lerp(top[0], to[0], ease(q)), y = lerp(top[1], to[1] + off, q) - 4 * 90 * sc * q * (1 - q)
          if (q < 1) return [x, y, 1, false]
          const hop = t > 9.8 && t < 10.3 ? Math.sin(((t - 9.8) / 0.5) * Math.PI) * 22 * sc : 0
          return [to[0], to[1] + off - hop, 0, false]
        }
        a = climb(5.0, footX + dEnd) || jump(7.5, [capX - 70 * sc, capY])
        b = climb(6.2, footX - 30) || jump(8.7, [capX + 55 * sc, capY])
      }

      if (still) p = pressDepth(t)
      else { v += (pressDepth(t) - p) * 0.32; v *= 0.62; p += v }
      btn.style.translate = `0 ${p.toFixed(2)}px`
      lad.style.width = L + 'px'; lad.style.opacity = '1'
      lad.style.transform = `translate(${lx}px, ${ly - 15}px) rotate(${la}rad)`
      setH(A, a); setH(B, b)
      if (t >= SCENE_END && doneAt < 0) doneAt = rt
      const bo = still ? 1 : doneAt < 0 ? 0 : cl((rt - doneAt - 0.15) / 0.25)
      bub.style.opacity = String(bo)
      bub.style.transform = `translate(${(a[0] + b[0]) / 2}px, ${Math.min(a[1], b[1]) - H - 22}px) translate(-50%, -100%) scale(${0.6 + 0.4 * ease(bo)})`
      if (still) cancelAnimationFrame(raf)
    }
    raf = requestAnimationFrame(frame)
    return () => {
      cancelAnimationFrame(raf)
      const btn = document.querySelector<HTMLElement>('[data-ham-target]')
      if (btn) btn.style.translate = ''
    }
  }, [])

  const ham = { backgroundImage: `url(${walk})` }
  return (
    <div ref={ref} className={s.scene} aria-hidden>
      <div className={s.ladder} />
      <div className={s.ham} style={ham} />
      <div className={s.ham} style={ham} />
      <div className={s.bubble}>도와줘. 딸깍!</div>
    </div>
  )
}
