import { useLayoutEffect, useRef, useState, type FormEvent } from 'react'
import { login } from '../../api/auth'
import { getHome } from '../../api/home'
import { useApp } from '../../app/AppContext'
import Button from '../../components/Button'
import Dialog from '../../components/Dialog'
import Input from '../../components/Input'
import { markEnter, pop, shouldEnter, shrink } from '../../utils/screenFx'
import s from './Login.module.css'

// 0-2 로그인 → 첫 로그인이면 0-3, 아니면 1 (응답의 firstLogin, docs/web.md §2)
// 401 LOGIN_FAILED 문구는 래퍼가 하단 바에 띄운다
export default function Login() {
  const { loadSession, setHome } = useApp()
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [forgot, setForgot] = useState(false)
  // 0-1에서 넘어오면 카드가 튀어나온다 (-259)
  const box = useRef<HTMLFormElement>(null)
  useLayoutEffect(() => {
    if (shouldEnter('login')) pop(box.current!)
  }, [])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      const res = await login(username, password)
      // 로그인 확인 뒤에만 사용자 · AI 표시를 채운다 (§4)
      await loadSession().catch(() => {})
      // 홈 데이터를 카드가 사라지는 동안 받아 둔다 — 없으면 홈이 빈 채로 떴다가 상단 바 폴링이 받아 와야 채워져 새로고침처럼 보였다 (-259)
      // 0.8초까지만 기다린다 — 홈 응답이 늦거나 안 와도 투명한 카드로 멈추지 않게. 늦게 온 응답은 버린다:
      // 폴링이 넣은 새 값을 덮거나 로그아웃 뒤 Context를 다시 채우지 않게. 화면이 바뀌면 상단 바가 바로 다시 받는다
      const preload = res.firstLogin ? null : Promise.race([getHome().catch(() => undefined), new Promise<undefined>((r) => setTimeout(r, 800))])
      const [, h] = await Promise.all([shrink(box.current!), preload])
      if (h) setHome(h)
      markEnter('afterLogin')
      location.hash = res.firstLogin ? '#/onboarding' : '#/'
    } catch {
      setBusy(false)
    }
  }

  return (
    <div className={s.wrap}>
      <form ref={box} className={s.box} onSubmit={submit}>
        <span className={s.lead}>관리자로 로그인하세요</span>
        <div className={s.field}>
          <span className={s.label}>아이디</span>
          <Input big value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
        </div>
        <div className={s.field}>
          <span className={s.label}>비밀번호</span>
          <Input big type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" autoFocus />
        </div>
        <Button type="submit" disabled={busy}>
          로그인
        </Button>
        <Button variant="text" size="sm" arrow={false} className={s.forgot} onClick={() => setForgot(true)}>
          비밀번호를 잊었어요
        </Button>
      </form>
      {forgot && (
        <Dialog title="비밀번호 다시 만들기" onClose={() => setForgot(false)}>
          <span className={s.hint}>딸깍이 도는 서버 터미널에서 아래 명령을 실행하면 새 비밀번호가 나와요.</span>
          <span className={s.hint}>
            docker run으로 설치했다면 <code className={s.cmd}>docker exec ddalkkak ddalkkak reset-password</code>
          </span>
          <span className={s.hint}>
            Compose로 띄웠다면 <code className={s.cmd}>docker compose exec ddalkkak ddalkkak reset-password</code>
          </span>
        </Dialog>
      )}
    </div>
  )
}
