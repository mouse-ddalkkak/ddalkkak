import type { ReactNode } from 'react'
import s from './PixelBand.module.css'

/** 위아래 파란 밴드 (계단 모양 땅 + 하늘) */
export default function PixelBand({ side, children }: { side: 'top' | 'bottom'; children?: ReactNode }) {
  return (
    <div className={`${s.band} ${side === 'top' ? s.top : s.bottom}`}>
      <div className={s.ground} />
      <div className={s.sky} />
      {children}
    </div>
  )
}
