import type { ReactNode } from 'react'
import s from './Checkbox.module.css'

/** 4px 테두리 네모 + 체크되면 시안 채움. 라벨을 누르면 바뀐다. 체크박스라 ENTER로는 바뀌지 않는다(스페이스 · 클릭만, -212) */
export default function Checkbox({
  checked,
  onChange,
  children,
  muted = false,
  className,
}: {
  checked: boolean
  onChange: (v: boolean) => void
  children?: ReactNode
  muted?: boolean
  className?: string
}) {
  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={checked}
      className={`${s.wrap} ${className ?? ''}`}
      onClick={() => onChange(!checked)}
      onKeyDown={(e) => e.key === 'Enter' && e.preventDefault()}
    >
      <Box checked={checked} muted={muted} />
      {children !== undefined && <span className={s.label}>{children}</span>}
    </button>
  )
}

/** 네모만 (카드 안 라디오처럼 쓸 때) */
export function Box({ checked, muted = false }: { checked: boolean; muted?: boolean }) {
  return (
    <span className={`${s.box} ${muted ? s.muted : ''}`} aria-hidden>
      <span className={s.fill} data-on={checked ? '' : undefined} />
    </span>
  )
}
