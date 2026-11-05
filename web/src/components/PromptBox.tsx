import { useEffect, useState } from 'react'
import { useCopy } from '../hooks/useCopy'
import Button from './Button'
import s from './PromptBox.module.css'

/** 수정 요청문 — 편집 가능 · 복사 · 다시 생성 (6 묶음 · 7 단건) · 원본 로그 추가 (7만, onAddLogs) */
export default function PromptBox({
  title,
  badge,
  text,
  onRegen,
  onAddLogs,
}: {
  title: string
  badge: string
  text: string
  onRegen: () => void
  onAddLogs?: () => void
}) {
  const [value, setValue] = useState(text)
  const { copied, copy } = useCopy()
  useEffect(() => setValue(text), [text])

  return (
    <section className={s.box}>
      <div className={s.head}>
        <span className={s.title}>{title}</span>
        <span className={s.badge}>{badge}</span>
        <span className={s.grow} />
        <Button size="sm" arrow={false} onClick={() => copy(value)}>
          {copied ? '복사됨' : '복사'}
        </Button>
      </div>
      <textarea className={s.text} value={value} onChange={(e) => setValue(e.target.value)} rows={Math.min(14, value.split('\n').length + 1)} />
      <div className={s.foot}>
        <span className={s.caption}>편집 가능</span>
        <span className={s.grow} />
        <button type="button" className={s.link} onClick={onRegen}>
          다시 생성
        </button>
        {onAddLogs && (
          <button type="button" className={s.link} onClick={onAddLogs}>
            원본 로그 추가 ▸
          </button>
        )}
      </div>
    </section>
  )
}
