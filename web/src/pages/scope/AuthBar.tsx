import { useState } from 'react'
import { getAuth, putAuth, type AuthSetting } from '../../api/runs'
import { ApiError } from '../../api/client'
import { useApp } from '../../app/AppContext'
import { useLoad } from '../../hooks/useLoad'
import Button from '../../components/Button'
import Dialog from '../../components/Dialog'
import Input from '../../components/Input'
import { authBody, authLine, choosable, defaultChoice, type AuthChoice } from '../../utils/authLine'
import d from './ScopeDialog.module.css'
import s from './AuthBar.module.css'

// 4 로그인 줄 · 팝업 (screens.md 4 "로그인 줄" · "로그인 팝업", F-SCOPE-09, -230)
// 분석 결과를 보고 [확인하고 로그인]을 누를 때 처음으로 검사할 앱에 로그인 요청이 간다(PUT …/auth). 그 전에는 가입 · 로그인 요청이 없다

const CHOICES: { value: AuthChoice; label: string; sub: string }[] = [
  { value: 'create', label: '딸깍이 계정 만들기', sub: 'ddalkkak-test-xxxx 로 가입' },
  { value: 'account', label: '내 계정', sub: '아이디 · 비밀번호' },
  { value: 'token', label: '토큰', sub: 'Authorization: Bearer' },
  { value: 'cookie', label: '쿠키', sub: '이름=값; 이름=값' },
]

export default function AuthBar({ runId }: { runId: string }) {
  const { data, setData } = useLoad(() => getAuth(runId), [runId])
  const [open, setOpen] = useState(false)
  if (!data) return null
  const line = authLine(data)
  return (
    <div className={s.bar} data-warn={line.warn ? '' : undefined}>
      <span className={s.name}>로그인</span>
      <span className={s.text}>{line.text}</span>
      <span className={s.grow} />
      <button type="button" className={s.edit} onClick={() => setOpen(true)}>
        {line.action} ✎
      </button>
      {open && (
        <AuthDialog
          runId={runId}
          auth={data}
          onClose={() => setOpen(false)}
          onSaved={(saved) => {
            setData(saved)
            setOpen(false)
          }}
        />
      )}
    </div>
  )
}

function AuthDialog({ runId, auth, onClose, onSaved }: { runId: string; auth: AuthSetting; onClose: () => void; onSaved: (a: AuthSetting) => void }) {
  const a = auth.analysis
  const [choice, setChoice] = useState<AuthChoice>(defaultChoice(auth))
  const [v, setV] = useState({ username: '', password: '', token: '', cookie: '' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const { showMessage } = useApp()
  const body = authBody(choice, v)

  const submit = async () => {
    if (!body || busy) return
    setBusy(true)
    setError(null)
    try {
      const saved = await putAuth(runId, body)
      showMessage('로그인을 저장했어요 · 다음 검사에서도 그대로 써요')
      onSaved(saved)
    } catch (e) {
      // 로그인 실패 등은 팝업 안에 (putAuth handle). 그 밖은 래퍼가 하단 바에 띄웠다
      if (e instanceof ApiError && e.code) setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  const field = (label: string, key: keyof typeof v, placeholder: string, type = 'text') => (
    <div className={d.numField}>
      <span className={d.numLabel}>{label}</span>
      <Input aria-label={label} type={type} autoComplete="off" value={v[key]} placeholder={placeholder} onChange={(e) => setV({ ...v, [key]: e.target.value })} />
    </div>
  )

  return (
    <Dialog
      title="검사할 앱에 어떻게 로그인할까요"
      onClose={onClose}
      actions={
        <>
          <span className={d.grow} />
          <Button variant="outline" size="sm" arrow={false} onClick={onClose}>
            취소
          </Button>
          <Button size="sm" disabled={busy || !body} onClick={submit}>
            확인하고 로그인
          </Button>
        </>
      }
    >
      <dl className={s.analysis}>
        <dt>로그인 API</dt>
        <dd>{a.loginApi ?? '찾지 못했어요'}</dd>
        <dt>가입 API</dt>
        <dd>{a.signupApi ?? '찾지 못했어요'}</dd>
        <dt>요청 칸</dt>
        <dd>{a.fields.length ? a.fields.join(' · ') : '—'}{a.fieldsGuessed && ' (칸 정보가 없어 다 보내요)'}</dd>
        <dt>토큰 위치</dt>
        <dd>{auth.tokenAt ?? '계정으로 확인하면 첫 로그인 응답에서 찾아요'}</dd>
      </dl>
      {!a.loginApi && <span className={s.warn}>로그인 API를 찾지 못했어요 · 토큰이나 쿠키를 넣어 주세요</span>}
      <div className={d.choices}>
        {CHOICES.map((c) => (
          <button
            key={c.value}
            type="button"
            className={d.choice}
            data-on={choice === c.value ? '' : undefined}
            disabled={!choosable(auth, c.value)}
            onClick={() => {
              setChoice(c.value)
              setError(null)
            }}
          >
            <span className={d.choiceLabel}>{c.label}</span>
            <span className={d.choiceSub}>{c.sub}</span>
          </button>
        ))}
      </div>
      {choice === 'account' && (
        <div className={d.nums}>
          {field('아이디 · 이메일', 'username', 'me@shop.com')}
          {field('비밀번호', 'password', '', 'password')}
        </div>
      )}
      {choice === 'token' && field('토큰', 'token', 'eyJhbGciOi… (Bearer 는 빼도 돼요)', 'password')}
      {choice === 'cookie' && field('쿠키', 'cookie', 'SESSION=3f9a…; theme=dark', 'password')}
      <span className={d.sub}>
        [확인하고 로그인]을 누를 때 처음으로 검사할 앱에 로그인 요청을 보내요. 값은 암호화해 이 서비스에 저장하고 다음 검사에서도 써요 · 마지막으로 쓴 지
        30일이 지나면 지워요
      </span>
      {error && <span className={s.error}>{error}</span>}
    </Dialog>
  )
}
