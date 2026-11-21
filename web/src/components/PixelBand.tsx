import type { ReactNode } from 'react'
import s from './PixelBand.module.css'

/** 상단 바 아래 4px 간격 · 하단 바 위 64px 바위 띠 (footer-rocks-2.png 윗부분, 아랫부분은 BottomBar, -259) */
export default function PixelBand({ side, children }: { side: 'top' | 'bottom'; children?: ReactNode }) {
  return <div className={side === 'top' ? s.top : s.bottom}>{children}</div>
}
