import { useApp } from '../app/AppContext'
import BlinkCursor from './BlinkCursor'
import s from './BottomBar.module.css'

/** 하단 바: 시스템 메시지. 바는 페이지 맨 아래에 두고(-212) 메시지만 창 아래에 띄운다(-139) */
export default function BottomBar() {
  const { message } = useApp()
  return (
    <footer className={s.footer}>
      <div className={s.inner}>
        <span className={s.grow} />
        {message && (
          <span className={s.msg} role="status">
            <span className={s.cursor}>
              <BlinkCursor />
            </span>
            {message}
          </span>
        )}
      </div>
    </footer>
  )
}
