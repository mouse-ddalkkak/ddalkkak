import s from './BlinkCursor.module.css'

/** 깜빡이는 블록 커서 (하단 바 메시지 · 0-2 입력 · 5 로그) */
export default function BlinkCursor({ small = false }: { small?: boolean }) {
  return <span className={small ? s.small : s.cursor} aria-hidden />
}
