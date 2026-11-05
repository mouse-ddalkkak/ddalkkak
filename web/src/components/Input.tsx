import type { InputHTMLAttributes } from 'react'
import s from './Input.module.css'

/** "> " 프롬프트가 붙은 입력칸 */
export default function Input({ big = false, className, ...rest }: InputHTMLAttributes<HTMLInputElement> & { big?: boolean }) {
  return (
    <label className={`${s.field} ${big ? s.big : ''} ${className ?? ''}`}>
      <span className={s.prompt}>&gt;</span>
      <input className={s.input} {...rest} />
    </label>
  )
}
