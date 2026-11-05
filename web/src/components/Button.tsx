import type { ButtonHTMLAttributes } from 'react'
import s from './Button.module.css'

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  /** primary: 밝은 버튼 + 빨간 화살표 · outline: 테두리만 · text: 글자만 · accent: 시안 테두리 */
  variant?: 'primary' | 'outline' | 'text' | 'accent'
  size?: 'sm' | 'md' | 'lg'
  /** 빨간 픽셀 화살표 (primary 기본) */
  arrow?: boolean
}

/** 4px 테두리 · 모서리 0 · 누르면 4px 이동 (screens.md 비주얼) */
export default function Button({ variant = 'primary', size = 'md', arrow, className, children, ...rest }: Props) {
  const showArrow = arrow ?? variant === 'primary'
  return (
    <button type="button" className={`${s.btn} ${s[variant]} ${s[size]} ${className ?? ''}`} {...rest}>
      {showArrow && <span className={s.arrow} aria-hidden />}
      {children}
    </button>
  )
}
