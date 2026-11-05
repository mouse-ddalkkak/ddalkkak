import walk from '../assets/img/astro-walk.png'
import s from './HamsterWalk.module.css'

/** 하단 밴드 위를 걷는 우주 햄스터 2마리 (0-0 제외 — 0-0은 소개 연출 자리) */
export default function HamsterWalk() {
  return (
    <div className={s.track} aria-hidden>
      {[{ delay: 0, h: 120 }, { delay: 1.6, h: 104 }].map((r) => (
        <div
          key={r.delay}
          className={s.ham}
          style={{ width: Math.round((r.h * 173) / 240), height: r.h, backgroundImage: `url(${walk})`, animationDelay: `${r.delay}s, 0s` }}
        />
      ))}
    </div>
  )
}

/** 제자리 걷기 스프라이트 (0-1 카드 · 3 준비 중) */
export function HamsterSprite({ height }: { height: number }) {
  return <div className={s.sprite} aria-hidden style={{ width: Math.round((height * 173) / 240), height, backgroundImage: `url(${walk})` }} />
}
