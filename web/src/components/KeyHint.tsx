import s from './KeyHint.module.css'

export default function KeyHint({ keys, label }: { keys: string[]; label: string }) {
  return (
    <span className={s.hint}>
      {keys.map((k) => (
        <span key={k} className={s.key}>
          {k}
        </span>
      ))}
      <span className={s.label}>{label}</span>
    </span>
  )
}
