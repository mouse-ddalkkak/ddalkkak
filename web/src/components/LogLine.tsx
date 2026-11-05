import s from './LogLine.module.css'

/** 로그 한 줄 "[tag] text" — ERRO가 든 줄은 빨강 (5 진행 로그 · 7 관측 근거) */
export default function LogLine({ tag, text, time }: { tag?: string; text: string; time?: string }) {
  const error = /\bERRO\b/.test(text)
  return (
    <span className={error ? s.error : s.line}>
      {(time || tag) && (
        <>
          <span className={s.tag}>{[time, tag && `[${tag}]`].filter(Boolean).join(' ')}</span>{' '}
        </>
      )}
      {text}
    </span>
  )
}
