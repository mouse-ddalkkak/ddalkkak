import type { HTMLAttributes } from 'react'
import s from './Panel.module.css'

/** 파란 4px 테두리 패널 (section) */
export default function Panel({ className, ...rest }: HTMLAttributes<HTMLElement>) {
  return <section className={`${s.panel} ${className ?? ''}`} {...rest} />
}
