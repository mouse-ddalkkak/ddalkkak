import { useState } from 'react'
import { checkAi, putAiSettings, type AiProvider } from '../api/ai'
import { useApp } from '../app/AppContext'
import Button from './Button'
import Input from './Input'
import Led from './Led'
import { CUSTOM, modelOptions, modelValue } from '../utils/aiModels'
import s from './AiForm.module.css'

// 주소는 어느 제공자든 넣을 수 있다(게이트웨이 · 사내 프록시, -178). claude · openai는 공식 주소(hint)로 막아 두고 "다른 주소 쓰기"를 눌러야 입력한다(-213), custom은 필수
const PROVIDERS: { k: AiProvider; n: string; label: string; hint: string }[] = [
  { k: 'claude', n: 'Claude', label: '주소 · 게이트웨이 (비우면 공식)', hint: 'https://api.anthropic.com' },
  { k: 'openai', n: 'OpenAI', label: '주소 · 게이트웨이 (비우면 공식)', hint: 'https://api.openai.com/v1' },
  { k: 'custom', n: '직접 URL · 사내 프록시', label: '주소 · OpenAI 호환 (필수)', hint: 'http://localhost:11434/v1' },
]

// 0-3 ④ AI 연결 (선택) · admin ▾ "AI 연결" 팝업(TopBar) · 4 ✎ "🔒 AI 추천으로" 팝업 — 연결 확인이 성공한 값만 저장 (F-AI-01~)
// onConnected = 저장까지 성공한 뒤 (4는 이걸로 팝업을 닫는다, -215)
export default function AiForm({ onConnected }: { onConnected?: () => void } = {}) {
  const { setAi, showMessage } = useApp()
  const [provider, setProvider] = useState<AiProvider>('claude')
  const [baseUrl, setBaseUrl] = useState('')
  const [otherUrl, setOtherUrl] = useState(false) // claude · openai의 "다른 주소 쓰기"(GMS 같은 게이트웨이)
  const [key, setKey] = useState('')
  const [choice, setChoice] = useState('') // 모델 셀렉트 (claude · openai). '' = 기본 모델
  const [model, setModel] = useState('') // 직접 입력 · 직접 URL의 모델
  const [state, setState] = useState<{ kind: 'idle' | 'checking' | 'ok' | 'err'; text?: string }>({ kind: 'idle' })
  const current = PROVIDERS.find((p) => p.k === provider) ?? PROVIDERS[0]
  const options = modelOptions(provider)
  const official = provider !== 'custom' && !otherUrl // 주소 칸을 막고 공식 주소로 보낸다(baseUrl null)

  const check = async () => {
    const input = { provider, baseUrl: official ? null : baseUrl.trim() || null, key, model: options ? modelValue(choice, model) : model.trim() }
    setState({ kind: 'checking' })
    try {
      const res = await checkAi(input)
      // ok: false 는 응답 값 — 그 message를 보여 준다
      if (!res.ok) return setState({ kind: 'err', text: res.message })
      setAi(await putAiSettings(input))
      setState({ kind: 'ok', text: res.message })
      showMessage('AI 연결됨')
      onConnected?.()
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
            <button key={p.k} type="button" className={s.pv} data-on={p.k === provider ? '' : undefined} onClick={() => { setProvider(p.k); setBaseUrl(''); setOtherUrl(false); setChoice(''); setModel('') }}>
              {p.n}
            </button>
          ))}
        </div>
      </div>
      <div className={s.full}>
        <div className={s.labelRow}>
          <span className={s.label}>{official ? '주소 · 공식' : current.label}</span>
          {provider !== 'custom' && (
            <button type="button" className={s.link} onClick={() => { setOtherUrl(!otherUrl); setBaseUrl('') }}>
              {otherUrl ? '공식 주소로' : '다른 주소 쓰기 (게이트웨이)'}
            </button>
          )}
        </div>
        {official ? (
          <Input value={current.hint} disabled readOnly aria-label="주소" />
        ) : (
          <Input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder={current.hint} aria-label="주소" />
        )}
      </div>
      <div className={s.cell}>
        <span className={s.label}>API 키</span>
        <Input type="password" value={key} onChange={(e) => setKey(e.target.value)} autoComplete="off" />
      </div>
      <div className={s.cell}>
        <span className={s.label}>모델</span>
        {options && (
          <label className={s.selectField}>
            <span className={s.prompt}>&gt;</span>
            <select className={s.select} value={choice} onChange={(e) => setChoice(e.target.value)} aria-label="모델">
              {options.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        )}
        {(!options || choice === CUSTOM) && (
          <Input value={model} onChange={(e) => setModel(e.target.value)} placeholder={options ? '모델 이름' : '비워 두면 기본 모델'} aria-label="모델 직접 입력" />
        )}
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
