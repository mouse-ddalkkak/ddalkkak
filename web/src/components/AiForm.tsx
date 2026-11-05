import { useState } from 'react'
import { checkAi, putAiSettings, type AiProvider } from '../api/ai'
import { useApp } from '../app/AppContext'
import Button from './Button'
import Input from './Input'
import Led from './Led'
import s from './AiForm.module.css'

// 주소는 어느 제공자든 넣을 수 있다(게이트웨이 · 사내 프록시, -178). claude · openai는 비우면 공식 주소(hint), custom은 필수
const PROVIDERS: { k: AiProvider; n: string; label: string; hint: string }[] = [
  { k: 'claude', n: 'Claude', label: '주소 · 비우면 공식', hint: 'https://api.anthropic.com' },
  { k: 'openai', n: 'OpenAI', label: '주소 · 비우면 공식', hint: 'https://api.openai.com/v1' },
  { k: 'custom', n: '직접 URL · 사내 프록시', label: '주소 · OpenAI 호환 (필수)', hint: 'http://localhost:11434/v1' },
]

// 0-3 ④ AI 연결 (선택) · admin ▾ "AI 연결" 팝업(TopBar) — 연결 확인이 성공한 값만 저장 (F-AI-01~)
export default function AiForm() {
  const { setAi, showMessage } = useApp()
  const [provider, setProvider] = useState<AiProvider>('claude')
  const [baseUrl, setBaseUrl] = useState('')
  const [key, setKey] = useState('')
  const [model, setModel] = useState('')
  const [state, setState] = useState<{ kind: 'idle' | 'checking' | 'ok' | 'err'; text?: string }>({ kind: 'idle' })
  const current = PROVIDERS.find((p) => p.k === provider) ?? PROVIDERS[0]

  const check = async () => {
    const input = { provider, baseUrl: baseUrl.trim() || null, key, model: model.trim() }
    setState({ kind: 'checking' })
    try {
      const res = await checkAi(input)
      // ok: false 는 응답 값 — 그 message를 보여 준다
      if (!res.ok) return setState({ kind: 'err', text: res.message })
      setAi(await putAiSettings(input))
      setState({ kind: 'ok', text: res.message })
      showMessage('AI 연결됨')
    } catch {
      setState({ kind: 'idle' })
    }
  }

  return (
    <div className={s.grid}>
      <div className={s.full}>
        <span className={s.label}>제공자</span>
        <div className={s.providers}>
          {PROVIDERS.map((p) => (
            <button key={p.k} type="button" className={s.pv} data-on={p.k === provider ? '' : undefined} onClick={() => { setProvider(p.k); setBaseUrl('') }}>
              {p.n}
            </button>
          ))}
        </div>
      </div>
      <div className={s.full}>
        <span className={s.label}>{current.label}</span>
        <Input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder={current.hint} />
      </div>
      <div className={s.cell}>
        <span className={s.label}>API 키</span>
        <Input type="password" value={key} onChange={(e) => setKey(e.target.value)} autoComplete="off" />
      </div>
      <div className={s.cell}>
        <span className={s.label}>모델</span>
        <Input value={model} onChange={(e) => setModel(e.target.value)} placeholder="비워 두면 기본 모델" />
      </div>
      <div className={`${s.full} ${s.row}`}>
        <Button onClick={check} disabled={state.kind === 'checking'} className={s.check}>
          연결 확인
        </Button>
        {state.kind === 'checking' && (
          <span className={s.checking}>
            <Led color="cyan-300" />
            확인 중…
          </span>
        )}
        {state.kind === 'ok' && <span className={s.ok}>✓ {state.text}</span>}
        {state.kind === 'err' && <span className={s.err}>{state.text}</span>}
      </div>
    </div>
  )
}
