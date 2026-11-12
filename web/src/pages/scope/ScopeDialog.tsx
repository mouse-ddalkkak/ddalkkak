import { useState } from 'react'
import type { Area, ScopeItem } from '../../api/runs'
import Button from '../../components/Button'
import Input from '../../components/Input'
import { AREA_NAME } from '../../utils/score'
import Dialog from '../../components/Dialog'
import AiForm from '../../components/AiForm'
import { useApp } from '../../app/AppContext'
import { LOAD_NAME, PRESETS, RANGES, SEVERITIES, SHAPES, TOOL_DEFAULT, estimate, type Added } from '../../utils/scopeParams'
import s from './ScopeDialog.module.css'

// 4 ✎ 설정 팝업 · "+ 추가" (screens.md 4 "설정 팝업", F-SCOPE-03~05)
// 팝업 틀은 components/Dialog

type Params = Record<string, unknown>

function Choice({ on, onClick, label, sub }: { on: boolean; onClick: () => void; label: string; sub?: string }) {
  return (
    <button type="button" className={s.choice} data-on={on ? '' : undefined} onClick={onClick}>
      <span className={s.choiceLabel}>{label}</span>
      {sub && <span className={s.choiceSub}>{sub}</span>}
    </button>
  )
}

const num = (v: unknown) => Number(v) || 0

/** ✎ — 성능(k6) · 보안(zap) · 코드(semgrep). ai = 카드의 aiParams ("AI 추천으로"), 직접 추가 카드는 null이라 버튼이 없다 (-180) */
export function EditDialog({
  item,
  ai,
  busy,
  onClose,
  onApply,
}: {
  item: ScopeItem
  ai: Params | null
  busy: boolean
  onClose: () => void
  onApply: (params: Params, message: string) => void
}) {
  const [p, setP] = useState<Params>(item.params)
  const [message, setMessage] = useState('적용했어요')
  // AI 미연결(응답 전 포함)이면 "AI 추천으로"를 잠그고 누르면 AI 연결 팝업 — 연결되면 그 자리에서 풀린다 (-215)
  const locked = !useApp().ai?.connected
  const [aiOpen, setAiOpen] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const set = (patch: Params) => {
    setP({ ...p, ...patch })
    setMessage('적용했어요')
  }
  const cancel = (
    <Button variant="outline" size="sm" arrow={false} onClick={onClose}>
      취소
    </Button>
  )
  const apply = (
    <Button size="sm" disabled={busy} onClick={() => onApply(p, message)}>
      적용
    </Button>
  )

  if (item.tool === 'zap' || item.tool === 'semgrep') {
    const zap = item.tool === 'zap'
    const field = zap ? 'range' : 'severity'
    return (
      <Dialog
        title={zap ? '얼마나 넓게 공격해 볼까요' : '어디까지 지적할까요'}
        onClose={onClose}
        actions={
          <>
            <span className={s.grow} />
            {cancel}
            {apply}
          </>
        }
      >
        <span className={s.sub}>{item.title}</span>
        <div className={s.choices}>
          {(zap ? RANGES : SEVERITIES).map((c) => (
            <Choice key={c.value} on={p[field] === c.value} onClick={() => set({ [field]: c.value })} label={c.label} sub={(c as { sub?: string }).sub} />
          ))}
        </div>
      </Dialog>
    )
  }

  const from = num(p.usersFrom)
  const to = num(p.usersTo)
  const min = num(p.durationMin)
  const est = estimate({ usersFrom: from, usersTo: to, durationMin: min })
  const numInput = (label: string, field: string, scale = 1) => (
    <div className={s.numField}>
      <span className={s.numLabel}>{label}</span>
      <Input
        type="number"
        aria-label={label}
        value={String(Math.round(num(p[field]) * scale * 100) / 100)}
        onChange={(e) => set({ [field]: Number(e.target.value) / scale })}
      />
    </div>
  )
  return (
    <Dialog
      title={item.title}
      onClose={onClose}
      actions={
        <>
          <Button
            variant="text"
            size="sm"
            arrow={false}
            onClick={() => {
              setP({ ...p, ...TOOL_DEFAULT.k6 })
              setMessage('적용했어요')
            }}
          >
            도구 기본값으로
          </Button>
          {ai && (
            <Button
              variant="text"
              size="sm"
              arrow={false}
              onClick={() => {
                if (locked) return setAiOpen(true)
                // AI 없이 준비한 카드의 aiParams는 기본값이다. 지금 AI를 연결해도 이 회차의 추천을 다시 받지 않으니 값을 바꾸지 않는다 (-215)
                if (item.source === 'default') return setNote('이 회차는 AI 없이 준비했어요 · AI 추천은 다음 검사 준비부터 들어가요')
                setP({ ...p, ...ai })
                setMessage('AI 추천으로 맞췄어요')
              }}
            >
              {locked ? '🔒 AI 추천으로' : 'AI 추천으로'}
            </Button>
          )}
          <span className={s.grow} />
          {cancel}
          {apply}
        </>
      }
    >
      <span className={s.sentence}>
        {to}명이 동시에 상황을 {min}분 동안 재현해요
      </span>
      <div className={s.choices}>
        {PRESETS.map((x) => (
          <Choice key={x.label} on={from === x.usersFrom && to === x.usersTo} onClick={() => set({ usersFrom: x.usersFrom, usersTo: x.usersTo })} label={x.label} sub={locked ? x.sub.replace(' · AI 추천', '') : x.sub} />
        ))}
      </div>
      <div className={s.choices}>
        {SHAPES.map((x) => (
          <Choice key={x.value} on={p.shape === x.value} onClick={() => set({ shape: x.value })} label={x.label} />
        ))}
      </div>
      <span className={s.estimate}>
        예상 요청 약 {est.requests.toLocaleString()}건 · 서버 부하 {LOAD_NAME[est.load]} · {est.advice}
      </span>
      <details className={s.direct}>
        <summary>직접 숫자로</summary>
        <div className={s.nums}>
          {numInput('시작 사용자', 'usersFrom')}
          {numInput('끝 사용자', 'usersTo')}
          {numInput('시간(분)', 'durationMin')}
          {numInput('실패 기준 p95(ms)', 'failP95Ms')}
          {numInput('실패 기준 오류율(%)', 'failErrorRate', 100)}
        </div>
      </details>
      {note && <span className={s.sub}>{note}</span>}
      {/* 네이티브 <dialog>라 ✎ 팝업 위에 겹쳐 열리고, 닫아도 ✎ 팝업의 값은 그대로다 */}
      {aiOpen && (
        <Dialog title="AI 연결 · 선택" onClose={() => setAiOpen(false)}>
          <AiForm onConnected={() => setAiOpen(false)} />
        </Dialog>
      )}
    </Dialog>
  )
}

/** + 추가 — 성능 · 보안은 대상 API(이번 API 목록의 "메서드 경로"), 코드 품질은 대상 코드. 맞는지는 서버 NO_TARGET이 가린다 */
export function AddDialog({
  area,
  apis,
  busy,
  onClose,
  onAdd,
}: {
  area: Area
  apis: string[]
  busy: boolean
  onClose: () => void
  onAdd: (added: Added) => void
}) {
  const [title, setTitle] = useState('')
  const [target, setTarget] = useState('')
  const code = area === 'code'
  return (
    <Dialog
      title={`${AREA_NAME[area]} 시나리오 추가`}
      onClose={onClose}
      actions={
        <>
          <span className={s.grow} />
          <Button variant="outline" size="sm" arrow={false} onClick={onClose}>
            취소
          </Button>
          <Button size="sm" disabled={busy || !title.trim()} onClick={() => onAdd({ area, title: title.trim(), ...(code ? { target } : { api: target }), params: {} })}>
            추가
          </Button>
        </>
      }
    >
      <div className={s.numField}>
        <span className={s.numLabel}>이름</span>
        <Input aria-label="이름" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="쿠폰 발급 피크" />
      </div>
      <div className={s.numField}>
        <span className={s.numLabel}>{code ? '대상 코드' : '대상 API'}</span>
        <Input aria-label={code ? '대상 코드' : '대상 API'} value={target} onChange={(e) => setTarget(e.target.value)} placeholder={code ? '*Service.java' : area === 'perf' ? 'GET /api/products' : 'POST /api/coupons'} list={code ? undefined : 'scope-apis'} />
      </div>
      {!code && (
        <datalist id="scope-apis">
          {apis.map((a) => (
            <option key={a} value={a} />
          ))}
        </datalist>
      )}
      <span className={s.sub}>조건은 도구 기본값으로 시작해요. 추가한 뒤 ✎로 바꿀 수 있어요</span>
    </Dialog>
  )
}
