import type { HTMLAttributes } from 'react'
import s from './TitleFrame.module.css'

/** 마젠타 12px 프레임 + 네 모서리 리벳 (0-1 카드 · 1 타이틀 · 5 진행률) */
export default function TitleFrame({ dim = false, className, children, ...rest }: HTMLAttributes<HTMLDivElement> & { dim?: boolean }) {
  return (
    <div className={`${s.frame} ${className ?? ''}`} {...rest}>
      {['lt', 'rt', 'lb', 'rb'].map((k) => (
        <span key={k} className={`${s.rivet} ${s[k]} ${dim ? s.dim : ''}`} aria-hidden />
      ))}
      {children}
    </div>
  )
}
