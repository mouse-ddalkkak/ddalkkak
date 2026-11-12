import { useEffect, useRef, useState, type ReactNode } from 'react'
import { getIssue, getRunLogs, makePrompt } from '../../api/runs'
import { logSection } from '../../utils/logSection'
import LogLine from '../../components/LogLine'
import { useLoad } from '../../hooks/useLoad'
import { useApp } from '../../app/AppContext'
import Badge from '../../components/Badge'
import Button from '../../components/Button'
import Panel from '../../components/Panel'
import PromptBox from '../../components/PromptBox'
import { AREA_NAME } from '../../utils/score'
import { runOnce } from '../../utils/busy'
import { sessionMemory } from '../../utils/promptMemory'
import s from './Issue.module.css'

// 7 문제 상세 + 수정 요청문 — ← 결과로 → 6 · 이전 / 다음 (응답의 prev · next)
// "원본 로그 추가 ▸" = 같은 요청에 includeLogs: true. 붙은 줄 수는 응답에 없어(약속 ④ 빈칸) 문구에 숫자를 넣지 않는다
const SHAPE: Record<string, string> = { constant: '일정', ramp: '점진', spike: '급증', step: '계단' }
const chips = (text: string) => text.split(' · ').filter(Boolean)
// 문제별 마지막 프롬프트는 sessionStorage에 두어 다른 화면에 갔다 와도 남는다 (-216)
const memory = sessionMemory()

export default function Issue({ runId, issueId }: { runId: string; issueId: string }) {
  const { showMessage } = useApp()
  const { data } = useLoad(() => getIssue(runId, issueId), [runId, issueId])
  const [fold, setFold] = useState<Record<string, boolean>>({})
  const [prompt, setPrompt] = useState<string | null>(() => memory.issue(runId, issueId))
  // 생성 요청 중: 만들기 · 다시 생성 · 원본 로그 추가를 막고 "생성 중…". 다시 그리기 전 두 번째 클릭은 ref가 막는다
  const [busy, setBusy] = useState(false)
  const inFlight = useRef(false)
  // 관측 근거 원문 로그 — 처음 펼칠 때 한 번 전체를 받아 이 문제 itemKey 구간만 (F-RES-06, -142)
  // 이전 / 다음은 URL이 바뀌어 App이 화면을 새로 그리므로(Fragment key) 다른 문제의 구간이 남지 않는다
  const [rawLines, setRawLines] = useState<string[] | null>(null)
  const itemKey = data?.itemKey
  useEffect(() => {
    if (!fold.obs || !itemKey || rawLines) return
    let ignore = false
    getRunLogs(runId).then(
      (text) => !ignore && setRawLines(logSection(text, itemKey)),
      () => {}, // 래퍼가 문구를 띄웠다
    )
    return () => {
      ignore = true
    }
  }, [fold.obs, itemKey, rawLines, runId])
  if (!data) return null

  const run = `#/runs/${encodeURIComponent(runId)}`
  const goIssue = (id: string) => (location.hash = `${run}/issues/${encodeURIComponent(id)}`)
  const make = (again = false, logs = false) =>
    runOnce(inFlight, setBusy, async () => {
      try {
        const r = await makePrompt(runId, [data.id], logs)
        setPrompt(r.text)
        memory.saveIssue(runId, issueId, r.text)
        if (logs) showMessage('원본 로그를 붙였어요')
        else if (again) showMessage('다시 생성했어요')
      } catch {
        // 래퍼가 문구를 띄웠다. 생성 중 표시만 풀린다
      }
    })
  const section = (key: string, title: string, body: ReactNode, first = false) => (
    <>
      <button type="button" className={`${s.fold} ${first ? '' : s.foldLine}`} onClick={() => setFold((f) => ({ ...f, [key]: !f[key] }))}>
        <span>{title}</span>
        <span>{fold[key] ? '▾' : '▸'}</span>
      </button>
      {fold[key] && body}
    </>
  )

  return (
    <div className={s.wrap}>
      <div className={s.head}>
        <div className={s.badges}>
          <Badge color={data.level === 'danger' ? 'coral-400' : 'amber-400'}>{data.level === 'danger' ? '위험' : '주의'}</Badge>
          <Badge>{AREA_NAME[data.area]}</Badge>
          <Badge kind="plain" color={data.certainty === 'suspected' ? 'amber-400' : 'green-400'}>
            {data.certainty === 'suspected' ? '의심' : '확인됨'}
          </Badge>
          <span className={s.api}>{data.api}</span>
          <span className={s.grow} />
          {data.prev && (
            <button type="button" className={s.nav} onClick={() => goIssue(data.prev!)}>
              ← 이전
            </button>
          )}
          {data.next && (
            <button type="button" className={s.nav} onClick={() => goIssue(data.next!)}>
              다음 →
            </button>
          )}
          <button type="button" className={s.nav} onClick={() => (location.hash = run)}>
            ← 결과로
          </button>
        </div>
        <h1 className={s.title}>{data.title}</h1>
        <p className={s.impact}>{data.impact}</p>
      </div>

      <div className={s.grid}>
        <Panel>
          <span className={s.panelTitle}>지금 → 고친 뒤</span>
          <div className={s.compare}>
            <div className={`${s.box} ${s.asis}`}>
              <span className={s.boxLabel}>AS-IS · 지금</span>
              <span className={s.boxValue}>{data.asIs.value}</span>
              <span className={s.boxSub}>{data.asIs.label}</span>
            </div>
            <span className={s.to}>→</span>
            <div className={`${s.box} ${s.tobe}`}>
              <span className={s.boxLabel}>TO-BE · 고친 뒤</span>
              <span className={s.boxValue}>{data.toBe.value}</span>
              <span className={s.boxSub}>{data.toBe.label}</span>
            </div>
          </div>
          {data.metrics.length > 0 && (
            <div className={s.metrics}>
              <span className={s.mHead}>지표</span>
              <span className={`${s.mHead} ${s.right}`}>지금</span>
              <span />
              <span className={`${s.mHead} ${s.right}`}>목표</span>
              {data.metrics.map((m) => (
                <Metric key={m.name} {...m} />
              ))}
            </div>
          )}
          {data.suspectReason && (
            <div className={s.suspect}>
              <span className={s.suspectTitle}>의심인 이유</span>
              <span className={s.caption}>{data.suspectReason}</span>
            </div>
          )}
        </Panel>

        <Panel>
          <span className={s.panelTitle}>이렇게 고치세요</span>
          <div className={s.fix}>
            <span className={s.fixLabel}>어디서</span>
            <div className={s.chips}>
              {chips(data.where).map((c) => (
                <span key={c} className={s.chip}>
                  {c}
                </span>
              ))}
            </div>
            <span className={s.fixLabel}>원인</span>
            <span className={s.fixText}>{data.cause}</span>
            <span className={s.fixLabel}>고치는 법</span>
            <div className={s.ways}>
              <div className={s.way}>
                <span className={s.rec}>추천</span>
                <span className={s.fixText}>{data.fix.recommended}</span>
              </div>
              {data.fix.alternative && (
                <div className={s.way}>
                  <Badge>대안</Badge>
                  <span className={`${s.fixText} ${s.alt}`}>{data.fix.alternative}</span>
                </div>
              )}
            </div>
            <span className={s.fixLabel}>통과 기준</span>
            <div className={s.chips}>
              {chips(data.passCriteria).map((c) => (
                <span key={c} className={s.chip}>
                  {c}
                </span>
              ))}
            </div>
          </div>
          <div className={s.folds}>
            {data.code &&
              section(
                'code',
                '관련 코드',
                <div className={`${s.foldBody} ${s.code}`}>
                  {data.code.lines.map((l, i) => (
                    <span key={i} className={l.trimStart().startsWith(String(data.code!.line)) ? s.hit : undefined}>
                      {l}
                    </span>
                  ))}
                  <span className={s.codeMeta}>
                    {data.code.file.split('/').pop()} {data.code.line}번째 줄 · {[data.code.branch, data.code.commit].filter(Boolean).join(' ')}
                    {data.code.decompiled && ' · 디컴파일 기준'}
                  </span>
                </div>,
                true,
              )}
            {section(
              'obs',
              '관측 근거',
              <div className={s.foldBody}>
                {data.evidence.map((e) => (
                  <span key={e}>{e}</span>
                ))}
                {rawLines && rawLines.length > 0 && (
                  <div className={s.raw}>
                    {rawLines.map((l, i) => (
                      <LogLine key={i} text={l} />
                    ))}
                  </div>
                )}
              </div>,
              !data.code,
            )}
            {data.repro &&
              section(
                'repro',
                '재현 조건',
                <span className={s.repro}>
                  {[
                    // 템플릿 문자열은 null도 "null…"로 만들어 filter를 통과하므로 값이 있을 때만 넣는다
                    data.repro.usersFrom != null && data.repro.usersTo != null && `${data.repro.usersFrom}→${data.repro.usersTo}명`,
                    data.repro.durationMin != null && `${data.repro.durationMin}분`,
                    data.repro.shape && (SHAPE[data.repro.shape] ?? data.repro.shape),
                    data.repro.api,
                    data.repro.testAccount && `테스트 계정 ${data.repro.testAccount}`,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </span>,
              )}
          </div>
        </Panel>
      </div>

      <div className={s.makeBar}>
        <Button onClick={() => make()} disabled={busy}>
          {busy ? '생성 중…' : '수정 프롬프트 만들기'}
        </Button>
        <span className={s.caption}>아래에 붙어요 ↓</span>
      </div>
      {prompt !== null && (
        <div className={s.promptWrap}>
          <PromptBox title="수정 프롬프트" badge="1개 항목" text={prompt} onRegen={() => make(true)} onAddLogs={() => make(true, true)} busy={busy} />
        </div>
      )}
    </div>
  )
}

function Metric({ name, now, target }: { name: string; now: string; target: string }) {
  return (
    <>
      <span className={s.mName}>{name}</span>
      <span className={`${s.mNow} ${s.right}`}>{now}</span>
      <span className={s.mArrow}>→</span>
      <span className={`${s.mTarget} ${s.right}`}>{target}</span>
    </>
  )
}
