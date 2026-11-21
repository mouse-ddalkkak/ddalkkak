import { useState } from 'react'
import { checkAi, deleteAiSettings, putAiSettings, type AiProvider } from '../api/ai'
import { useApp } from '../app/AppContext'
import Button from './Button'
import Input from './Input'
import Led from './Led'
import Select from './Select'
import { ApiError } from '../api/client'
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
// -254: 연결돼 있으면 폼 대신 제공자 · 가린 키 · 연결됨을 보여 주고, "바꾸기"를 눌러야 폼이 나온다. "연결 해제"는 저장한 설정을 지운다
export default function AiForm({ onConnected }: { onConnected?: () => void } = {}) {
  const { ai, setAi, showMessage } = useApp()
  const [changing, setChanging] = useState(false)
  const [removing, setRemoving] = useState(false)
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
  // 버튼 옆 오류는 다시 고르거나 고치면 지운다 (screens.md §3, -257)
  const clearErr = () => setState((x) => (x.kind === 'err' ? { kind: 'idle' } : x))

  const check = async () => {
    const input = { provider, baseUrl: official ? null : baseUrl.trim() || null, key, model: options ? modelValue(choice, model) : model.trim() }
    setState({ kind: 'checking' })
    try {
      const res = await checkAi(input)
      // ok: false 는 응답 값 — 그 message를 보여 준다
      if (!res.ok) return setState({ kind: 'err', text: res.message })
      setAi(await putAiSettings(input))
      setChanging(false)
      setState({ kind: 'ok', text: res.message })
      showMessage('AI 연결됨')
      onConnected?.()
    } catch (e) {
      // 400(주소 · 키 형식)은 버튼 옆에 남긴다 (-257). 서버 꺼짐(code 없음)은 하단 바가 띄웠다
      setState(e instanceof ApiError && e.code ? { kind: 'err', text: e.message } : { kind: 'idle' })
    }
  }

  const remove = async () => {
    setRemoving(true)
    try {
      setAi(await deleteAiSettings())
      setChanging(false)
      setState({ kind: 'idle' })
      showMessage('AI 연결 해제됨')
    } catch {
      // 실패 문구는 래퍼가 띄웠다. 다시 누를 수 있게 푼다
    } finally {
      setRemoving(false)
    }
  }

  if (ai?.connected && !changing) {
    const pv = PROVIDERS.find((p) => p.k === ai.provider)
    return (
      <div className={s.grid}>
        <div className={`${s.full} ${s.connected}`}>
          <span className={s.ok}>✓ 연결됨</span>
          <dl className={s.info}>
            <dt>제공자</dt>
            <dd>{pv?.n ?? ai.provider}</dd>
            <dt>주소</dt>
            <dd>{ai.baseUrl ?? (pv && pv.k !== 'custom' ? '공식 주소' : '-')}</dd>
            <dt>API 키</dt>
            <dd>{ai.keyMasked}</dd>
            <dt>모델</dt>
            <dd>{ai.model || '기본 모델'}</dd>
          </dl>
          <span className={s.note}>키는 이 서버에만 저장돼 있고 다시 보여 주지 않아요</span>
        </div>
        <div className={`${s.full} ${s.row}`}>
          <Button onClick={() => { setState({ kind: 'idle' }); setKey(''); setChanging(true) }} className={s.check}>
            바꾸기
          </Button>
          <Button variant="outline" onClick={remove} disabled={removing} className={s.check}>
            연결 해제
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className={s.grid}>
      <div className={s.full}>
        <span className={s.label}>제공자</span>
        <div className={s.providers}>
          {PROVIDERS.map((p) => (
            <button key={p.k} type="button" className={s.pv} data-on={p.k === provider ? '' : undefined} onClick={() => { setProvider(p.k); setBaseUrl(''); setOtherUrl(false); setChoice(''); setModel(''); clearErr() }}>
              {p.n}
            </button>
          ))}
        </div>
      </div>
      <div className={s.full}>
        <div className={s.labelRow}>
          <span className={s.label}>{official ? '주소 · 공식' : current.label}</span>
          {provider !== 'custom' && (
            <button type="button" className={s.link} onClick={() => { setOtherUrl(!otherUrl); setBaseUrl(''); clearErr() }}>
              {otherUrl ? '공식 주소로' : '다른 주소 쓰기 (게이트웨이)'}
            </button>
          )}
        </div>
        {official ? (
          <Input value={current.hint} disabled readOnly aria-label="주소" />
        ) : (
          <Input value={baseUrl} onChange={(e) => { setBaseUrl(e.target.value); clearErr() }} placeholder={current.hint} aria-label="주소" />
        )}
      </div>
      <div className={s.cell}>
        <span className={s.label}>API 키</span>
        <Input type="password" value={key} onChange={(e) => { setKey(e.target.value); clearErr() }} autoComplete="off" />
      </div>
      <div className={s.cell}>
        <span className={s.label}>모델</span>
        {options && (
          <Select value={choice} options={options} onChange={(v) => { setChoice(v); clearErr() }} aria-label="모델" />
        )}
        {(!options || choice === CUSTOM) && (
          <Input value={model} onChange={(e) => { setModel(e.target.value); clearErr() }} placeholder={options ? '모델 이름' : '비워 두면 기본 모델'} aria-label="모델 직접 입력" />
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
        {ai?.connected && (
          <Button variant="text" arrow={false} disabled={state.kind === 'checking'} onClick={() => { setChanging(false); setState({ kind: 'idle' }) }}>
            취소
          </Button>
        )}
      </div>
    </div>
  )
}
