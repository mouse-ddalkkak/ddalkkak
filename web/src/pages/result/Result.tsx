import { useRef, useState } from 'react'
import { getResult, makePrompt, retryReport, type Issue } from '../../api/runs'
import { useLoad } from '../../hooks/useLoad'
import { useKeys } from '../../hooks/useKeys'
import { pressable } from '../../utils/keys'
import { useApp } from '../../app/AppContext'
import Button from '../../components/Button'
import PromptBox from '../../components/PromptBox'
import { AREAS, AREA_NAME, scoreColor, scoreText } from '../../utils/score'
import { koDateTime } from '../../utils/format'
import s from './Result.module.css'

// 6 결과 — 행 "자세히" → 7 · 재검사 → #/code(2-b) · 이력 탭 → 8
export default function Result({ runId }: { runId: string }) {
  const { showMessage } = useApp()
  const { data, setData } = useLoad(() => getResult(runId), [runId])
  const [sel, setSel] = useState<string[]>([])
  const [open, setOpen] = useState<Record<string, boolean>>({})
  const [prompt, setPrompt] = useState<{ ids: string[]; text: string } | null>(null)
  const [retrying, setRetrying] = useState(false)
  // ESC 홈 · ENTER 프롬프트 만들기 (F-HOME-05)
  const enter = useRef(() => {})
  useKeys({ Escape: () => (location.hash = '#/'), Enter: () => enter.current() })
  if (!data) return null

  const toggle = (ids: string[], on: boolean) => setSel((x) => (on ? [...new Set([...x, ...ids])] : x.filter((i) => !ids.includes(i))))
  // 담은 순서가 아니라 결과 화면 순서(위험 → 주의)로 보낸다
  const ordered = data.issues.filter((i) => sel.includes(i.id)).map((i) => i.id)

  const make = async (again = false) => {
    const ids = again && prompt ? prompt.ids : ordered
    if (ids.length === 0) return showMessage('항목을 하나 이상 골라 주세요')
    try {
      const r = await makePrompt(runId, ids)
      setPrompt({ ids, text: r.text })
      if (again) showMessage('다시 생성했어요')
    } catch {
      // 래퍼가 문구를 띄웠다
    }
  }
  enter.current = () => make()

  // 다시 해석 — 성공하면 응답(다시 해석한 결과)으로 바꾼다. 503 문구는 래퍼가 띄웠다
  const retry = async () => {
    setRetrying(true)
    try {
      setData(await retryReport(runId))
    } catch {
      // 래퍼가 문구를 띄웠다
    }
    setRetrying(false)
  }

  const issueRow = (i: Issue) => {
    const on = sel.includes(i.id)
    return (
      <div key={i.id} className={s.row}>
        <div className={s.rowText}>
          <div className={s.rowHead}>
            <span className={s.rowTitle}>{i.title}</span>
            <span className={s.certainty}>{i.certainty === 'suspected' ? '의심' : '확인됨'}</span>
          </div>
          <span className={s.rowTech}>
            {i.tech} · 지금 {i.asIs.value} → 목표 <span className={s.tobe}>{i.toBe.value}</span>
          </span>
        </div>
        <button type="button" className={s.pick} data-on={on ? '' : undefined} onClick={() => toggle([i.id], !on)}>
          {on ? '✓ 프롬프트에 담김' : '+ 프롬프트에 담기'}
        </button>
        <Button variant="outline" size="sm" arrow={false} onClick={() => (location.hash = `#/runs/${encodeURIComponent(runId)}/issues/${encodeURIComponent(i.id)}`)}>
          자세히<span className={s.arrow} />
        </Button>
      </div>
    )
  }

  return (
    <div className={s.wrap}>
      <div className={s.head}>
        <div className={s.headText}>
          <h1 className={s.service}>{data.service}</h1>
          <span className={s.meta}>
            {koDateTime(data.at)} 검사 · 소요 {data.durationMin}분 · 시나리오 {data.scenarioCount} · 검사 항목 {data.itemCount}
          </span>
        </div>
        <Button onClick={() => (location.hash = '#/code')} className={s.rescan}>
          재검사
        </Button>
      </div>

      {data.reportError && (
        <div className={s.reportError}>
          <span className={s.grow}>해석하지 못했어요</span>
          <Button variant="outline" size="sm" arrow={false} disabled={retrying} onClick={retry}>
            {retrying ? '해석 중…' : '다시 해석'}
          </Button>
        </div>
      )}

      <section className={s.scores}>
        {AREAS.map((a) => {
          const v = data.scores[a]
          const sum = data.summary[a]
          return (
            <div key={a} className={s.tile}>
              <div className={s.ring}>
                <span
                  className={s.ringBar}
                  data-none={v == null ? '' : undefined}
                  style={{ background: v == null ? undefined : `conic-gradient(${scoreColor(v)} 0 ${v}%, var(--ink-700) ${v}% 100%)` }}
                />
                <span className={s.ringVal} style={{ color: scoreColor(v) }}>
                  {scoreText(v)}
                </span>
              </div>
              <span className={s.tileName}>{AREA_NAME[a]}</span>
              <span className={s.tileSub}>{v == null ? `미검사${sum.skipReason ? ` · ${sum.skipReason}` : ''}` : `통과 ${sum.pass} · 실패 ${sum.fail}`}</span>
            </div>
          )
        })}
      </section>

      <div className={s.cart}>
        <div className={s.cartHead}>
          <span className={s.cartTitle}>프롬프트에 담은 문제 {sel.length}개</span>
          <button type="button" className={s.link} onClick={() => toggle(data.issues.map((i) => i.id), true)}>
            모두 담기
          </button>
          <button type="button" className={s.link} onClick={() => setSel([])}>
            모두 빼기
          </button>
          <span className={s.grow} />
          <Button onClick={() => make()} className={s.make}>
            담은 {sel.length}개로 프롬프트 만들기
          </Button>
        </div>
        {prompt && <PromptBox title="묶음 수정 프롬프트" badge={`${prompt.ids.length}개 항목`} text={prompt.text} onRegen={() => make(true)} />}
      </div>

      {AREAS.map((a) => {
        const v = data.scores[a]
        const sum = data.summary[a]
        const issues = data.issues.filter((i) => i.area === a)
        const passed = data.passed.filter((p) => p.area === a)
        const groups = [
          { key: `${a}-danger`, label: '위험', tone: 'coral-400', rows: issues.filter((i) => i.level === 'danger'), def: true },
          { key: `${a}-warn`, label: '주의', tone: 'amber-400', rows: issues.filter((i) => i.level === 'warn'), def: false },
        ].filter((g) => g.rows.length > 0)
        return (
          <section key={a} className={s.area}>
            <div className={s.areaHead}>
              <span className={s.areaTile} style={{ background: scoreColor(v), borderColor: scoreColor(v) }}>
                {scoreText(v)}
              </span>
              <div className={s.areaText}>
                <span className={s.areaName}>{AREA_NAME[a]}</span>
                <span className={s.areaLine}>{v == null ? `미검사${sum.skipReason ? ` · ${sum.skipReason}` : ''}` : sum.line}</span>
              </div>
              <span className={s.areaCount}>
                <span className={s.fix}>고칠 점 {issues.length}</span> · 통과 {sum.pass}
              </span>
            </div>
            {groups.map((g) => {
              const isOpen = open[g.key] ?? g.def
              const ids = g.rows.map((r) => r.id)
              const all = ids.every((i) => sel.includes(i))
              return (
                <div key={g.key} className={s.group} style={{ borderColor: `var(--${g.tone})` }}>
                  <div className={s.groupHead} {...pressable(() => setOpen((o) => ({ ...o, [g.key]: !isOpen })))}>
                    <span className={s.groupArrow}>{isOpen ? '▴' : '▾'}</span>
                    <span className={s.groupLabel} style={{ color: `var(--${g.tone})` }}>
                      {g.label} {g.rows.length}
                    </span>
                    {!isOpen && <span className={s.preview}>{g.rows[0].title}</span>}
                    <span className={s.grow} />
                    <button
                      type="button"
                      className={s.link}
                      onClick={(e) => {
                        e.stopPropagation()
                        toggle(ids, !all)
                      }}
                    >
                      {all ? '모두 빼기' : '모두 담기'}
                    </button>
                  </div>
                  {isOpen && g.rows.map(issueRow)}
                </div>
              )
            })}
            {passed.length > 0 && (
              <div className={`${s.group} ${s.passGroup}`}>
                <div className={s.groupHead} {...pressable(() => setOpen((o) => ({ ...o, [`${a}-pass`]: !o[`${a}-pass`] })))}>
                  <span className={s.groupArrow}>{open[`${a}-pass`] ? '▴' : '▾'}</span>
                  <span className={s.groupLabel} style={{ color: 'var(--green-400)' }}>
                    ✓ 통과 {sum.pass}
                  </span>
                </div>
                {open[`${a}-pass`] && (
                  <div className={s.passList}>
                    {passed.map((p) => (
                      <span key={p.name} className={s.passItem}>
                        <span className={s.check}>✓</span>
                        <span>
                          <span className={s.passName}>{p.name}</span>
                          <span className={s.passVal}> · {p.value}</span>
                        </span>
                      </span>
                    ))}
                  </div>
                )}
              </div>
            )}
          </section>
        )
      })}
    </div>
  )
}
