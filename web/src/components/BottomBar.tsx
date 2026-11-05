import { useApp } from '../app/AppContext'
import KeyHint from './KeyHint'
import BlinkCursor from './BlinkCursor'
import s from './BottomBar.module.css'

/** 하단 바: 키 힌트(키 동작은 화면의 useKeys) + 시스템 메시지. 홈은 메시지가 없으면 PRESS ENTER */
export default function BottomBar({ hints, home }: { hints: [string, string][]; home: boolean }) {
  const { message } = useApp()
  const text = message ?? (home ? 'PRESS ENTER' : null)
  return (
    <footer className={s.footer}>
      <div className={s.inner}>
        {hints.map(([keys, label]) => (
          <KeyHint key={label} keys={keys.split(' ')} label={label} />
        ))}
        <span className={s.grow} />
        {text && (
          <span className={s.msg} role="status">
            <span className={s.cursor}>
              <BlinkCursor />
            </span>
            {text}
          </span>
        )}
      </div>
    </footer>
  )
}
