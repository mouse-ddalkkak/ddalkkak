import type { ReactNode } from 'react'
import s from './NavTab.module.css'

export default function NavTab({ href, on, children }: { href: string; on: boolean; children: ReactNode }) {
  return (
    <a href={href} className={s.tab} data-on={on ? '' : undefined}>
      {children}
    </a>
  )
}
