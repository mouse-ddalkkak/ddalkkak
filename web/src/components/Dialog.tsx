import { useEffect, useId, useRef, type ReactNode } from 'react'
import s from './Dialog.module.css'

/**
 * 팝업 — 네이티브 <dialog>: showModal()이 화면을 막는다. 닫기는 ✕ · 버튼으로만 — 키보드 단축키를 두지 않아 ESC로도 닫히지 않는다 (-212)
 */
export default function Dialog({ title, onClose, children, actions }: { title: string; onClose: () => void; children: ReactNode; actions?: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  // showModal()은 첫 버튼(머리 ✕)에 포커스를 준다 — 팝업 자체에 포커스를 둬 ENTER가 바로 닫지 않게
  // 사라질 때 열기 전 포커스로 돌려준다 — 겹친 팝업(4 ✎ 위 AI 연결)이 닫히고 포커스가 body에 남으면 ESC가 아래 팝업의 noEsc를 거치지 않아 닫힌다 (-215)
  useEffect(() => {
    const back = document.activeElement as HTMLElement | null
    ref.current?.showModal()
    ref.current?.focus()
    return () => back?.focus()
  }, [])
  // ESC로 닫히지 않게: 브라우저는 cancel을 두 번째부터 못 막기도 해서 keydown에서 먼저 막는다
  const noEsc = (e: { key: string; preventDefault: () => void }) => e.key === 'Escape' && e.preventDefault()
  return (
    <dialog ref={ref} className={s.dialog} tabIndex={-1} onClose={onClose} onCancel={(e) => e.preventDefault()} onKeyDown={noEsc} aria-labelledby={titleId}>
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
        <div className={s.actions}>
          {actions}
        </div>
      )}
    </dialog>
  )
}
