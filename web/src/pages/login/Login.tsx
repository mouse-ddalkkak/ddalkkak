import { useState, type FormEvent } from 'react'
import { login } from '../../api/auth'
import { useApp } from '../../app/AppContext'
import Button from '../../components/Button'
import Input from '../../components/Input'
import s from './Login.module.css'

// 0-2 로그인 → 첫 로그인이면 0-3, 아니면 1 (응답의 firstLogin, docs/web.md §2)
// 401 LOGIN_FAILED 문구는 래퍼가 하단 바에 띄운다
export default function Login() {
  const { loadSession } = useApp()
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      const res = await login(username, password)
      // 로그인 확인 뒤에만 사용자 · AI 표시를 채운다 (§4)
      await loadSession().catch(() => {})
      location.hash = res.firstLogin ? '#/onboarding' : '#/'
    } catch {
      setBusy(false)
    }
  }

  return (
    <div className={s.wrap}>
      <form className={s.box} onSubmit={submit}>
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
        <span className={s.hint}>
          비밀번호를 잊었다면 서버에서 <code className={s.cmd}>docker compose exec server ddalkkak reset-password</code>
        </span>
      </form>
    </div>
  )
}
