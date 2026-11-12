import { useEffect, useRef, useState } from 'react'
import { getPrepareApis, getScope, putScope, startRun, type Area, type Scope as ScopeData, type ScopeItem } from '../../api/runs'
import { useApp } from '../../app/AppContext'
import { keepOffChoices, noCode, turnOn, type Added } from '../../utils/scopeParams'
import { AddDialog, EditDialog } from './ScopeDialog'
import { syncEstimate } from './syncEstimate'
import { useLoad } from '../../hooks/useLoad'
import { pressable } from '../../utils/keys'
import PageTitle from '../../components/PageTitle'
import Button from '../../components/Button'
import RunBusyDialog from '../../components/RunBusyDialog'
import { ApiError } from '../../api/client'
import Checkbox from '../../components/Checkbox'
import Switch from './Switch'
import AuthBar from './AuthBar'
import { AREA_NAME } from '../../utils/score'
import s from './Scope.module.css'

// 4 검사 범위 선택 — 고른 값은 "딸깍" 때 PUT …/scope 로 보내고 POST /api/runs
// ✎ 설정 팝업 · "+ 추가"는 그 자리에서 PUT …/scope로 저장하고 응답(다시 계산한 범위)으로 바꾼다. 400은 래퍼가 서버 message를 띄운다
const TOOL_NAME: Record<string, string> = { k6: 'k6', zap: 'ZAP', semgrep: 'Semgrep', ddalkkak: '권한 점검' }
const RANGE: Record<string, string> = { default: '기본', wide: '넓게', full: '전체' }
const SEVERITY: Record<string, string> = { critical: '치명만', major: '주요까지', all: '전부' }

// 카드 "조건" 줄 — params에서 만든다 (성능 "50→200명 · 3분" · 보안/코드는 설정 팝업의 단계 이름)
function condition(i: ScopeItem) {
  const p = i.params as Record<string, number | string>
  if (i.area === 'perf') return `${p.usersFrom}→${p.usersTo}명 · ${p.durationMin}분`
  if (p.range) return RANGE[p.range as string] ?? String(p.range)
  if (p.severity) return SEVERITY[p.severity as string] ?? String(p.severity)
  if (p.idCount) return `다른 id ${p.idCount}개`
  if (p.count) return `계정 ${p.count}개`
  return ''
}

export default function Scope({ runId }: { runId: string }) {
  const { data, setData } = useLoad(() => getScope(runId), [runId])
  const [info, setInfo] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [runBusy, setRunBusy] = useState(false)
  const [dialog, setDialog] = useState<{ edit: ScopeItem } | { add: Area } | null>(null)
  const { showMessage } = useApp()
  // "+ 추가" 대상 후보 — 이번 회차 API 목록 (-179). 실패하면 후보만 비고 자유 입력은 그대로 (맞는지는 서버 NO_TARGET)
  const apis = useLoad(() => getPrepareApis(runId), [runId]).data?.apis.map((a) => a.api) ?? []
  // 스위치 · 카드 토글 뒤 "예상 약 M분"은 서버 estimate로 (화면은 계산하지 않는다). 선택은 화면 값 그대로 두고 estimate만 바꾼다
  const sync = useRef<ReturnType<typeof syncEstimate<ScopeData>>>(null)
  sync.current ??= syncEstimate<ScopeData>({
    put: (next) => putScope(runId, next),
    apply: (estimate) => setData((d) => d && { ...d, estimate }),
  })
  useEffect(() => () => sync.current?.cancel(), [])
  if (!data) return null

  // 응답 전 다시 누르면 같은 added가 두 번 가 카드가 두 장 생긴다 → 저장 중에는 팝업 버튼을 막는다
  const save = async (next: ScopeData, added: Added[], message: string) => {
    if (busy) return
    sync.current?.cancel() // 저장 응답이 다시 계산한 estimate를 준다. 앞서 걸린 토글 PUT의 옛 값이 덮지 않게
    setBusy(true)
    try {
      setData(keepOffChoices(await putScope(runId, next, added), next))
      setDialog(null)
      showMessage(message)
    } catch {
      // 래퍼가 문구를 띄웠다. 팝업은 열어 둔다
    } finally {
      setBusy(false)
    }
  }
  const applyParams = (key: string, params: Record<string, unknown>, message: string) =>
    save(
      { ...data, parts: data.parts.map((p) => ({ ...p, items: p.items.map((i) => (i.key === key ? { ...i, params } : i)) })) },
      [],
      message,
    )

  const update = (fn: (d: ScopeData) => ScopeData) => {
    const next = fn(data)
    setData(next)
    sync.current?.schedule(next)
  }
  const togglePart = (area: string) =>
    update((d) => ({ ...d, parts: d.parts.map((p) => (p.area !== area ? p : p.on ? { ...p, on: false } : turnOn(p))) }))
  const toggleItem = (key: string) =>
    update((d) => ({
      ...d,
      parts: d.parts.map((p) => ({ ...p, items: p.items.map((i) => (i.key === key ? { ...i, included: !i.included } : i)) })),
    }))
  const all = data.parts.every((p) => noCode(p) || (p.on && p.items.every((i) => i.included)))
  const setAll = (on: boolean) =>
    update((d) => ({
      ...d,
      parts: d.parts.map((p) => (noCode(p) ? p : { ...p, on, items: on ? p.items.map((i) => ({ ...i, included: true })) : p.items })),
    }))
  const count = data.parts.filter((p) => p.on).reduce((n, p) => n + p.items.filter((i) => i.included).length, 0)

  const go = async () => {
    sync.current?.cancel()
    setBusy(true)
    try {
      await putScope(runId, data)
      const res = await startRun(runId)
      location.hash = `#/runs/${encodeURIComponent(res.runId)}/progress`
    } catch (e) {
      // 다른 검사가 돌고 있음 → 하단 바 대신 팝업 (-214)
      if (e instanceof ApiError && e.code === 'RUN_IN_PROGRESS') setRunBusy(true)
      setBusy(false)
    }
  }

  return (
    <div className={s.wrap} onClick={() => setInfo(null)}>
      <PageTitle
        title="이번에 무엇을 검사할까요"
        sub="필요한 항목만 골라도 돼요. 고른 만큼만 검사해요"
        right={
          <Checkbox checked={all} onChange={setAll} className={s.all}>
            전체 선택
          </Checkbox>
        }
      />
      <AuthBar runId={runId} />
      {data.parts.map((p) => {
        const tools = [...new Set(p.items.map((i) => i.tool))]
        return (
          <section key={p.area} className={s.part}>
            <div className={s.partHead}>
              <Switch on={p.on} disabled={noCode(p)} onChange={() => togglePart(p.area)} />
              <span className={s.partName}>{AREA_NAME[p.area]}</span>
              <span className={s.count}>{p.on ? `${p.items.filter((i) => i.included).length}개` : (p.offReason ?? '')}</span>
              <span className={s.grow} />
              {tools.map((t) => (
                <span key={t} className={s.tool}>
                  {TOOL_NAME[t] ?? t}
                </span>
              ))}
              <button
                type="button"
                className={s.add}
                disabled={noCode(p)}
                onClick={() => setDialog({ add: p.area })}
              >
                + 추가
              </button>
            </div>
            <div className={s.cards} style={{ opacity: p.on ? 1 : 0.45 }}>
              {p.items.map((i) => {
                const inc = p.on && i.included
                return (
                  <div key={i.key} className={s.card} data-inc={inc ? '' : undefined} {...pressable(() => (p.on ? toggleItem(i.key) : noCode(p) || showMessage('스위치를 먼저 켜 주세요')))}>
                    {info === i.key && (
                      <div role="tooltip" className={s.tip} onClick={(e) => e.stopPropagation()}>
                        <span className={s.tipTitle}>왜 이 시나리오?</span>
                        <span className={s.tipText}>{i.why}</span>
                      </div>
                    )}
                    <span className={s.cardTitle}>{i.title}</span>
                    <span className={s.target}>{i.api ?? i.target}</span>
                    <span className={s.cond}>{condition(i)}</span>
                    <div className={s.cardFoot}>
                      {inc ? (
                        <>
                          <button
                            type="button"
                            aria-label="추천 근거 보기"
                            className={s.info}
                            data-on={info === i.key ? '' : undefined}
                            onClick={(e) => {
                              e.stopPropagation()
                              setInfo(info === i.key ? null : i.key)
                            }}
                          >
                            i
                          </button>
                          <span className={s.grow} />
                          {i.tool !== 'ddalkkak' && (
                            <button
                              type="button"
                              aria-label="조건 바꾸기"
                              className={s.edit}
                              onClick={(e) => {
                                e.stopPropagation()
                                setDialog({ edit: i })
                              }}
                            >
                              ✎
                            </button>
                          )}
                        </>
                      ) : (
                        <span className={s.exc}>{i.excluded && i.excludeReason ? `AI 제외 · ${i.excludeReason}` : '+ 포함'}</span>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          </section>
        )
      })}
      {dialog && 'edit' in dialog && (
        <EditDialog
          item={dialog.edit}
          ai={dialog.edit.aiParams}
          busy={busy}
          onClose={() => setDialog(null)}
          onApply={(params, message) => applyParams(dialog.edit.key, params, message)}
        />
      )}
      {dialog && 'add' in dialog && (
        <AddDialog
          area={dialog.add}
          // 성능은 GET만 (쓰기 · 삭제 API에 부하를 주지 않는다, NF-07 · openapi ScopeInput.added)
          apis={dialog.add === 'perf' ? apis.filter((a) => a.startsWith('GET ')) : apis}
          busy={busy}
          onClose={() => setDialog(null)} onAdd={(added) => save(data, [added], '시나리오를 추가했어요')} />
      )}
      <div className={s.foot}>
        <Button variant="text" arrow={false} onClick={() => (location.hash = '#/')}>
          ← 이전
        </Button>
        <span className={s.grow} />
        <span className={s.estimate}>
          시나리오 {count}개 · 예상 약 {data.estimate.durationMin}분
        </span>
        <Button size="lg" onClick={go} disabled={busy || count === 0}>
          딸깍
        </Button>
      </div>
      {runBusy && <RunBusyDialog onClose={() => setRunBusy(false)} />}
    </div>
  )
}
