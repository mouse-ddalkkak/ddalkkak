import { useEffect, useRef, useState } from 'react'
import { filterOptions } from '../utils/selectFilter'
import s from './Select.module.css'

type Option = { value: string; label: string }

/**
 * 셀렉트 — Input과 같은 틀(4px 테두리 · "> ") + 픽셀 목록 (-257). 네이티브 <select> · <datalist>의 열린 목록은 브라우저 모양이라 직접 그린다
 * editable = 자유 입력 + 후보(4 "+ 추가" 대상 API), 아니면 고르기만(AI 모델)
 * 목록은 아래로 흐름 안에 펼친다 — 팝업(<dialog>) 안에서 잘리지 않게. 항목은 버튼이라 Tab · ENTER · 스페이스는 브라우저 기본
 */
export default function Select({
  value,
  options,
  onChange,
  editable = false,
  placeholder,
  'aria-label': label,
}: {
  value: string
  options: Option[]
  onChange: (value: string) => void
  editable?: boolean
  placeholder?: string
  'aria-label': string
}) {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  // 밖을 누르거나 포커스가 밖으로 가면 닫는다. 항목은 mousedown을 막아 포커스가 옮겨 가지 않는다(Safari는 버튼 클릭에 포커스를 안 줘 먼저 닫히면 고른 값이 사라진다)
  useEffect(() => {
    if (!open) return
    const close = (e: Event) => box.current?.contains(e.target as Node) || setOpen(false)
    document.addEventListener('pointerdown', close)
    document.addEventListener('focusin', close)
    return () => {
      document.removeEventListener('pointerdown', close)
      document.removeEventListener('focusin', close)
    }
  }, [open])

  const shown = editable ? filterOptions(options.map((o) => o.value), value).map((v) => options.find((o) => o.value === v)!) : options
  const keep = (e: { preventDefault: () => void }) => e.preventDefault()
  const picked = options.find((o) => o.value === value)?.label ?? value
  return (
    <div ref={box} className={s.wrap}>
      <div className={s.field} data-open={open ? '' : undefined}>
        <span className={s.prompt}>&gt;</span>
        {editable ? (
          <input
            className={s.input}
            value={value}
            placeholder={placeholder}
            aria-label={label}
            aria-expanded={open}
            role="combobox"
            onFocus={() => setOpen(true)}
            onChange={(e) => {
              onChange(e.target.value)
              setOpen(true)
            }}
          />
        ) : (
          // aria-label이 버튼 글자를 덮으니 고른 값도 같이 읽히게 (!164 리뷰)
          <button type="button" className={s.input} aria-label={picked ? `${label} · ${picked}` : label} aria-haspopup="listbox" aria-expanded={open} onClick={() => setOpen(!open)}>
            {picked}
          </button>
        )}
        <button type="button" className={s.arrow} tabIndex={-1} aria-hidden onMouseDown={keep} onClick={() => setOpen(!open)}>
          {open ? '▴' : '▾'}
        </button>
      </div>
      {open && shown.length > 0 && (
        <div className={s.list} role="listbox" aria-label={label}>
          {shown.map((o) => (
            <button
              key={o.value}
              type="button"
              role="option"
              aria-selected={o.value === value}
              className={s.option}
              onMouseDown={keep}
              onClick={() => {
                onChange(o.value)
                // 키보드로 고르면 이 버튼이 사라져 포커스가 body로 간다 — 팝업 ESC가 noEsc를 거치지 않는다. 트리거로 돌려준다
                // 닫기보다 먼저: editable input의 onFocus(열기)를 뒤의 setOpen(false)가 덮는다
                box.current?.querySelector<HTMLElement>('input, button')?.focus()
                setOpen(false)
              }}
            >
              {o.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
