import type { MouseEvent, ReactNode } from 'react'
import s from './NavTab.module.css'

export default function NavTab({
  href,
  on,
  onClick,
  children,
}: {
  href: string
  on: boolean
  onClick?: (e: MouseEvent<HTMLAnchorElement>) => void
  children: ReactNode
}) {
  return (
    <a href={href} className={s.tab} data-on={on ? '' : undefined} onClick={onClick}>
      {children}
    </a>
  )
}
