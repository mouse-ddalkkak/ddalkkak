import { Fragment } from 'react'
import s from './StepChips.module.css'

export type Step = { label: string; state: 'done' | 'on' | 'todo' }

/** 단계 칩을 점선으로 잇는다 — 완료 ✓ 초록 외곽 · 진행 중 시안(밴드) 채움 · 대기 회색 (흐름 표시줄 · 0-3 단계) */
export default function StepChips({ steps, className, doneMark = 'before' }: { steps: Step[]; className?: string; doneMark?: 'before' | 'after' }) {
  return (
    <div className={`${s.row} ${className ?? ''}`}>
      {steps.map((st, i) => (
        <Fragment key={st.label}>
          {i > 0 && <span className={s.dots} />}
          <span className={`${s.chip} ${s[st.state]}`}>
            {st.state === 'on' && <span className={s.glow} aria-hidden><span className={s.spin} /></span>}
            {st.state === 'done' ? (doneMark === 'before' ? `✓ ${st.label}` : `${st.label} ✓`) : st.label}
          </span>
        </Fragment>
      ))}
    </div>
  )
}
