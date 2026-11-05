import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from 'react'
import { pageKey } from '../utils/keys'
import s from './Dialog.module.css'

/**
 * 팝업 — 네이티브 <dialog>: showModal()이 화면을 막고 ESC로 닫힌다(onClose).
 * ENTER = actions의 맨 오른쪽 버튼(적용 · 추가). 입력칸 · 버튼 위에서는 그 칸 몫 (F-HOME-05 "팝업이 열려 있으면 ESC 닫기 · ENTER 적용")
 */
export default function Dialog({ title, onClose, children, actions }: { title: string; onClose: () => void; children: ReactNode; actions?: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)
  const actionsRef = useRef<HTMLDivElement>(null)
  const titleId = useId()
  // showModal()은 첫 버튼(머리 ✕)에 포커스를 줘 ENTER가 ✕를 누른다 — 팝업 자체에 포커스를 둬 ENTER = 적용
  useEffect(() => {
    ref.current?.showModal()
    ref.current?.focus()
  }, [])
  const onKeyDown = (e: KeyboardEvent) => {
    if (pageKey(e.nativeEvent, false) !== 'Enter') return
    const apply = [...(actionsRef.current?.querySelectorAll('button') ?? [])].at(-1)
    if (apply && !apply.disabled) {
      e.preventDefault()
      apply.click()
    }
  }
  return (
    <dialog ref={ref} className={s.dialog} tabIndex={-1} onClose={onClose} onKeyDown={onKeyDown} aria-labelledby={titleId}>
      <div className={s.head}>
        <span id={titleId} className={s.title}>
          {title}
        </span>
        <button type="button" className={s.close} aria-label="닫기" onClick={onClose}>
          ✕
        </button>
      </div>
      {children}
      {actions && (
        <div ref={actionsRef} className={s.actions}>
          {actions}
        </div>
      )}
    </dialog>
  )
}
