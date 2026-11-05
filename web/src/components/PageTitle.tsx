import type { ReactNode } from 'react'
import s from './PageTitle.module.css'

/** 화면 머리: 큰 제목 + 설명 + 아래 구분선. right에 버튼(재검사 · 전체 선택 …) */
export default function PageTitle({ title, sub, right, center = false, children }: { title: ReactNode; sub?: ReactNode; right?: ReactNode; center?: boolean; children?: ReactNode }) {
  return (
    <div className={`${s.head} ${center ? s.center : ''}`}>
      <div className={s.text}>
        {children}
        <h1 className={s.title}>{title}</h1>
        {sub && <p className={s.sub}>{sub}</p>}
      </div>
      {right}
    </div>
  )
}
