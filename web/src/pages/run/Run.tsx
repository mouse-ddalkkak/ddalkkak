import { useEffect, useRef, useState } from 'react'
import {
  finishPartial,
  getProgress,
  getRunLogs,
  openRunEvents,
  retryRun,
  stopRun,
  type LogEvent,
  type Progress,
  type StopEvent,
} from '../../api/runs'
import { getHome } from '../../api/home'
import { useApp } from '../../app/AppContext'
import PageTitle from '../../components/PageTitle'
import TitleFrame from '../../components/TitleFrame'
import Gauge from '../../components/Gauge'
import Button from '../../components/Button'
import Led from '../../components/Led'
import LogLine from '../../components/LogLine'
import BlinkCursor from '../../components/BlinkCursor'
import { timeSec } from '../../utils/format'
import { trapTab } from '../../utils/keys'
import { watchRun } from './watchRun'
import ServerStatus from '../home/ServerStatus'
import ResumeDialog from '../../components/ResumeDialog'
import s from './Run.module.css'

const MAX_LOGS = 200 // F-RUN-10 최근 로그 200줄

// 5 검사 진행 — SSE(progress · log · error · done)로 움직인다. 연결 규칙은 watchRun.ts (docs/web.md §3)
export default function Run({ runId }: { runId: string }) {
  const { showMessage, home } = useApp()
  const [data, setData] = useState<Progress>()
  const [logs, setLogs] = useState<LogEvent[]>([])
  const [stopEvent, setStopEvent] = useState<StopEvent | null>(null)
  // 딸깍 재시작으로 멈춘 회차면 멈춘 단계 이름(없으면 null) — 이어서 검사할지 묻는다 (-228). undefined면 묻지 않음
  const [resume, setResume] = useState<string | null | undefined>(undefined)
  // 이어서 · 여기까지를 고르면 같은 주소에서 스트림을 다시 붙인다
  const [gen, setGen] = useState(0)
  const [open, setOpen] = useState<Record<string, boolean>>({})
  const [auto, setAuto] = useState(true)
  const [full, setFull] = useState<string[] | null>(null)
  const logBox = useRef<HTMLDivElement>(null)

  // done · partial → 6, stopped → 1 + 중단 문구 (docs/web.md §3 done 규칙)
  // SSE done과 POST /stop 응답이 둘 다 부르므로 한 번만
  const left = useRef(false)
  const stopping = useRef(false) // POST /stop 보내는 중
  const leave = (status: string) => {
    if (left.current) return
    left.current = true
    if (status === 'stopped') {
      // 딸깍 재시작으로 멈춘 회차(홈 run.state interrupted)면 홈으로 보내지 않고 이어서 검사할지 묻는다 (-228)
      getHome()
        .then((h) => (h.run?.state === 'interrupted' && h.run.runId === runId ? (h.run.interruptedAt?.name ?? null) : undefined))
        .catch(() => undefined)
        .then((at) => {
          if (at !== undefined) return setResume(at)
          location.hash = '#/'
          showMessage('검사를 중단했어요')
        })
    } else location.hash = `#/runs/${encodeURIComponent(runId)}`
  }
  const leaveRef = useRef(leave)
  leaveRef.current = leave

  useEffect(() => {
    left.current = false
    stopping.current = false
    setData(undefined)
    setLogs([])
    setStopEvent(null)
    setFull(null)
    setResume(undefined)
    return watchRun({
      open: () => openRunEvents(runId),
      getProgress: () => getProgress(runId),
      on: {
        snapshot: (p) => {
          setData(p)
          setLogs(p.logs.slice(-MAX_LOGS))
        },
        progress: (d) => setData((p) => p && { ...p, pct: d.pct, leftMin: d.leftMin }),
        log: (d) => setLogs((l) => [...l, d].slice(-MAX_LOGS)),
        error: setStopEvent,
        leave: (st) => leaveRef.current(st),
      },
    })
  }, [runId, gen])

  useEffect(() => {
    if (auto && logBox.current) logBox.current.scrollTop = logBox.current.scrollHeight
  }, [logs, full, auto])

  const act = (call: (id: string) => Promise<{ status: string }>, then: (status: string) => void) => async () => {
    try {
      then((await call(runId)).status)
    } catch {
      // 래퍼가 문구를 띄웠다
    }
  }
  // 연타에도 POST /stop은 한 번. 실패하면 다시 누를 수 있게 푼다
  const stop = async () => {
    if (stopping.current) return
    stopping.current = true
    try {
      const { status } = await stopRun(runId)
      // running이면 서버가 결과 정리 중 — 끝나면 SSE done(stopped)이 와서 그때 떠난다 (openapi stopRun).
      // 그동안 오류 팝업을 닫는다 — 열려 있으면 "다시 시도"가 정리 중인 회차에 NOT_FOUND (!113 리뷰, partial과 같게)
      if (status !== 'running') leave(status)
      else setStopEvent(null)
    } catch {
      stopping.current = false // 래퍼가 문구를 띄웠다
    }
  }
  // 다시 시도 뒤에는 서버가 같은 스트림으로 이어 보내 다시 열지 않는다. 또 멈추면 error가 다시 와 팝업이 뜬다 (docs/web.md §3, -156)
  const retrying = useRef(false) // 연타에도 POST /retry는 한 번
  const retry = async () => {
    if (retrying.current) return
    retrying.current = true
    await act(retryRun, () => setStopEvent(null))()
    retrying.current = false
  }
  // running이면 서버가 여기까지 결과를 정리 중 — 끝나면 SSE done(partial)이 와서 그때 6으로 간다 (openapi finishRunPartial, -143).
  // 그동안 팝업을 닫는다
  const partialSent = useRef(false) // 연타에도 POST /finish-partial은 한 번
  const partial = async () => {
    if (partialSent.current) return
    partialSent.current = true
    await act(finishPartial, (status: string) => {
      if (status !== 'running') return leave(status)
      setStopEvent(null)
    })()
    if (!left.current) partialSent.current = false
  }

  // 오류 팝업은 <dialog>가 아니라 Tab을 직접 가둔다: 끝 버튼에서 반대쪽 끝으로. 열릴 때는 다시 시도에 포커스(autoFocus).
  // 팝업 빈 곳을 눌러 포커스가 body로 가도 잡도록 document에 건다 (-194)
  const popup = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!stopEvent) return
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key !== 'Tab' || !popup.current) return
      const next = trapTab<HTMLButtonElement>(popup.current, document.activeElement, e.shiftKey)
      if (!next) return
      e.preventDefault()
      next.focus()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [stopEvent])

  const toggleFull = async () => {
    if (full) return setFull(null)
    try {
      setFull((await getRunLogs(runId)).split('\n').filter(Boolean))
    } catch {
      // 래퍼가 문구를 띄웠다
    }
  }
  const fullLabel = full ? '로그 접기' : '전체 로그'

  // 재시작으로 멈춘 회차는 SSE가 done 하나만 보내 단계 트리(data)가 없다 — 그 전에 묻는다
  if (resume !== undefined)
    return (
      <ResumeDialog
        runId={runId}
        at={resume}
        onClose={() => {
          location.hash = '#/'
        }}
        onPicked={() => setGen((n) => n + 1)}
      />
    )
  if (!data) return null
  const isOpen = (step: string, state: string) => open[step] ?? state === 'running'
  const stepName = (step: string) => data.steps.find((st) => st.step === step)?.name ?? step

  return (
    <div className={s.wrap}>
      <PageTitle title="검사하고 있어요" sub="창을 닫아도 검사는 계속돼요. 끝나면 알려드릴게요" />
      <div className={s.top}>
        <TitleFrame dim className={s.frame}>
          <span className={s.state}>검사 중</span>
          <span className={s.pct}>{data.pct}%</span>
          <div className={s.gauge}>
            <Gauge pct={data.pct} cells={20} color="var(--cyan-300)" tall />
          </div>
          <div className={s.actions}>
            {data.leftMin != null && <span className={s.left}>약 {data.leftMin}분 남음</span>}
            <Button variant="outline" size="sm" onClick={stop}>
              중단
            </Button>
          </div>
        </TitleFrame>
        <section className={s.tree}>
          {data.steps.map((st) => (
            <div key={st.step} className={s.step}>
              <div
                className={s.stepHead}
                data-state={st.state}
                onClick={() => st.items.length && setOpen((o) => ({ ...o, [st.step]: !isOpen(st.step, st.state) }))}
              >
                {st.state === 'running' ? <Led color="band-500" /> : <Led color={st.state === 'done' ? 'green-400' : 'ink-600'} blink={false} />}
                <span className={s.stepName}>
                  {st.name}
                  {st.state === 'skipped' && ` · 건너뜀${st.skipReason ? `(${st.skipReason})` : ''}`}
                </span>
                {st.items.length > 0 && <span className={s.arrow}>{isOpen(st.step, st.state) ? '▾' : '▸'}</span>}
              </div>
              {st.items.length > 0 && isOpen(st.step, st.state) && (
                <div className={s.items}>
                  {st.items.map((it) => (
                    <div key={it.key} className={s.item} data-state={it.state}>
                      {it.state === 'running' ? (
                        <Led color="band-500" size={12} round />
                      ) : (
                        <Led color={it.state === 'done' ? 'green-400' : 'ink-600'} size={12} round blink={false} />
                      )}
                      <span>
                        {it.title}
                        {it.state === 'skipped' && ` · 건너뜀${it.skipReason ? `(${it.skipReason})` : ''}`}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ))}
        </section>
        {/* 검사 중에도 홈과 같은 서버 상태 (-214) */}
        {home && <ServerStatus home={home} className={s.server} />}
      </div>
      <section className={s.logPanel}>
        <div className={s.logHead}>
          <span className={s.logTitle}>진행 로그</span>
          <span className={s.grow} />
          <button type="button" className={s.link} onClick={toggleFull}>
            {fullLabel}
          </button>
          <button type="button" className={s.toggle} aria-pressed={auto} onClick={() => setAuto((a) => !a)}>
            자동 스크롤 {auto ? 'ON' : 'OFF'}
          </button>
        </div>
        <div className={s.logs} ref={logBox}>
          {full
            ? full.map((l, i) => <LogLine key={i} text={l} />)
            : logs.map((l, i) => <LogLine key={i} tag={l.tag} text={l.text} />)}
          <span>
            <BlinkCursor small />
          </span>
        </div>
      </section>
      {stopEvent && (
        <div className={s.overlay}>
          <div role="alertdialog" aria-modal="true" aria-labelledby="run-stop-title" className={s.popup} ref={popup}>
            <div className={s.popHead}>
              <span className={s.popLed}>
                <Led color="coral-400" />
              </span>
              <div className={s.popText}>
                <span id="run-stop-title" className={s.popTitle}>
                  {stopEvent.text}
                </span>
                <span className={s.caption}>
                  {stepName(stopEvent.step)} · {stopEvent.title} · {timeSec(stopEvent.ts)}
                </span>
              </div>
              <button type="button" className={s.close} aria-label="닫기" onClick={() => setStopEvent(null)}>
                ✕
              </button>
            </div>
            <div className={s.popLogs}>
              {full?.map((l, i) => <LogLine key={`f${i}`} text={l} />)}
              {stopEvent.lines.map((l, i) => (
                <LogLine key={i} text={l} />
              ))}
            </div>
            <div className={s.popActions}>
              <Button size="sm" autoFocus onClick={retry}>
                다시 시도
              </Button>
              <Button variant="outline" size="sm" onClick={partial}>
                여기까지 결과 보기
              </Button>
              <Button variant="outline" size="sm" onClick={stop}>
                중단
              </Button>
              <span className={s.grow} />
              <button type="button" className={s.link} onClick={toggleFull}>
                {fullLabel}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
