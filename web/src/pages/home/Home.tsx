import { useEffect, useState } from 'react'
import { useApp } from '../../app/AppContext'
import { pressable } from '../../utils/keys'
import TitleFrame from '../../components/TitleFrame'
import RunBusyDialog from '../../components/RunBusyDialog'
import ScoreRow from './ScoreRow'
import ClockPill from './ClockPill'
import { shortDateTime } from '../../utils/format'
import { getConnectRequests } from '../../api/services'
import { getCodeSource } from '../../api/codeSource'
import { useLoad } from '../../hooks/useLoad'
import { useScan } from '../../hooks/useScan'
import Button from '../../components/Button'
import { codeLine, lines, waitingReapprove } from './homeState'
import HomeNotice from './HomeNotice'
import ServerStatus from './ServerStatus'
import ToolStatusDialog from './ToolStatusDialog'
import s from './Home.module.css'

// 1 홈 — GET /api/home은 상단 바가 10초마다 받아 둔다 (api.md "폴링 주기", -214)
export default function Home() {
  const { home: data } = useApp()
  const [waiting, setWaiting] = useState(false)
  const [sel, setSel] = useState(0)
  const [tools, setTools] = useState(false)
  const { scan, busy, closeBusy } = useScan()
  // 코드 줄 — 홈을 열 때 한 번 (screens.md 1)
  const code = useLoad(getCodeSource, []).data

  // 끊겼을 때만 연결 요청을 본다 — 토큰을 잃은 앱은 같은 이름 · host · port로 다시 허용을 기다린다 (-211)
  useEffect(() => {
    let alive = true
    const service = data?.service ?? null
    if (service?.connected !== false) {
      setWaiting(false)
      return
    }
    getConnectRequests()
      .catch(() => [])
      .then((reqs) => alive && setWaiting(waitingReapprove(service, reqs)))
    return () => {
      alive = false
    }
  }, [data])

  if (!data) return null
  const { service, health, lastRun } = data

  const menu = [
    {
      label: '딸깍 검사',
      sub: '① 검사 범위 → 시작',
      // 코드는 저장한 방식으로 가져온다 — 저장한 방식이 없으면 0-3 ④ (-224). 검사 중이면 알린다 (-214). 연결된 서비스가 없거나, 끊겼는데 다시 허용할 요청이 있으면 0-3 (F-HOME-01 예외, -211)
      go: () => scan(waiting),
    },
    // 지난 결과는 검사 이력에서 연다
    { label: '도구 상태', sub: '', go: () => setTools(true) },
    { label: '검사 이력', sub: '', go: () => (location.hash = '#/history') },
  ]

  return (
    <div className={s.page}>
      <HomeNotice home={data} />
      <div className={s.wrap}>
        {/* 지난 검사 — 검사가 없어도 같은 양식을 두고 값만 비운다 (F-HOME-03, -214) */}
        <section className={s.side}>
          <div className={s.sideHead}>
            <span className={s.sideTitle}>지난 검사</span>
            <span className={s.muted}>{lastRun ? shortDateTime(lastRun.at) : '아직 검사 이력이 없어요'}</span>
          </div>
          <div className={s.scores}>
            <ScoreRow name="성능" value={lastRun?.scores.perf ?? null} />
            <ScoreRow name="보안" value={lastRun?.scores.sec ?? null} />
            <ScoreRow name="코드" value={lastRun?.scores.code ?? null} />
          </div>
          <div className={s.summary}>
            {/* 위험 · 주의는 영역마다 한 줄. 없으면 "없음" */}
            {(lines(lastRun?.topDanger ?? null).length ? lines(lastRun!.topDanger) : ['없음']).map((l) => (
              <div key={`d${l}`} className={s.sumRow}>
                <span className={s.danger}>위험</span>
                <span className={lastRun?.topDanger ? undefined : s.muted}>{l}</span>
              </div>
            ))}
            {(lines(lastRun?.topWarn ?? null).length ? lines(lastRun!.topWarn) : ['없음']).map((l) => (
              <div key={`w${l}`} className={s.sumRow}>
                <span className={s.warn}>주의</span>
                <span className={lastRun?.topWarn ? undefined : s.muted}>{l}</span>
              </div>
            ))}
            <div className={s.sumRow}>
              <span className={s.pass}>통과</span>
              {lastRun ? (
                <span>
                  {lastRun.passed}개
                  {lastRun.skipped.count > 0 && (
                    <>
                      {' '}· 미검사 {lastRun.skipped.count}
                      {lastRun.skipped.reason && <span className={s.muted}> ({lastRun.skipped.reason})</span>}
                    </>
                  )}
                </span>
              ) : (
                <span className={s.muted}>--</span>
              )}
            </div>
          </div>
        </section>

        <div className={s.center}>
          {health && <ClockPill iso={health.serverTime} />}
          <TitleFrame className={s.title}>
            <span className={s.hero}>딸깍</span>
            {service && (
              <span className={s.service}>
                {service.name} · {service.connected ? '연결됨' : '연결 끊김'}
                {service.apiCount != null && ` · API ${service.apiCount}개`}
              </span>
            )}
            {service && code && (
              <span className={s.code}>
                코드 · {codeLine(code.last)}
                <Button variant="text" size="sm" arrow={false} onClick={() => (location.hash = '#/onboarding/4')}>
                  바꾸기
                </Button>
              </span>
            )}
          </TitleFrame>
          <div className={s.menu}>
            {menu.map((m, i) => (
              <div key={m.label} className={s.item} onMouseEnter={() => setSel(i)} onFocus={() => setSel(i)} {...pressable(m.go)}>
                <span className={s.cursor} data-on={i === sel ? '' : undefined} />
                <div className={s.itemText}>
                  <span className={s.itemLabel} data-on={i === sel ? '' : undefined}>
                    {m.label}
                  </span>
                  {m.sub && <span className={s.itemSub}>{m.sub}</span>}
                </div>
              </div>
            ))}
          </div>
        </div>

        <ServerStatus home={data} />
      </div>
      {tools && <ToolStatusDialog onClose={() => setTools(false)} />}
      {busy && <RunBusyDialog onClose={closeBusy} />}
    </div>
  )
}
