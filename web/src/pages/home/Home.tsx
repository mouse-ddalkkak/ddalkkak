import { useEffect, useLayoutEffect, useRef, useState } from 'react'
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
import { useFitZoom } from '../../hooks/useFitZoom'
import { enter, shouldEnter } from '../../utils/screenFx'
import Button from '../../components/Button'
import { getUpdate, startUpdate } from '../../api/update'
import { useCopy } from '../../hooks/useCopy'
import { KIT_DEP } from '../../utils/kit'
import { codeLine, lines, updateBanner, updateFlow, updatePhase, updateStuckIn, waitingReapprove } from './homeState'
import HomeNotice from './HomeNotice'
import ServerStatus from './ServerStatus'
import ToolStatusDialog from './ToolStatusDialog'
import s from './Home.module.css'

// 1 홈 — GET /api/home은 상단 바가 10초마다 받아 둔다 (api.md "폴링 주기", -214)
export default function Home() {
  const { home: data } = useApp()
  // 내부 요소는 90% 크기, 창이 낮으면 스크롤 대신 더 줄인다 (-259)
  const page = useRef<HTMLDivElement>(null)
  useFitZoom(page, 0.9)
  // 로그인에서 넘어오면 한 번에 바뀌지 않고 올라오며 나타난다 (-259). 홈 데이터가 오기 전엔 그리지 않으니(아래 null) 처음 그려질 때 건다
  const shown = !!data
  useLayoutEffect(() => {
    if (shown && shouldEnter('afterLogin')) enter(page.current)
  }, [shown])
  const [waiting, setWaiting] = useState(false)
  const [sel, setSel] = useState(0)
  const [tools, setTools] = useState(false)
  const { scan, busy, closeBusy } = useScan()
  // 코드 줄 — 홈을 열 때 한 번 (screens.md 1)
  const code = useLoad(getCodeSource, []).data
  // 새 버전 띠 — 홈을 열 때 한 번. 서버가 GitHub 에 6시간에 한 번 묻는다 (F-HOME-06, -221)
  const update = useLoad(getUpdate, []).data
  // stuck: 눌렀는데 몇 분이 지나도 새 버전이 뜨지 않았다 — 설치기가 도중에 멈췄다 (!165 리뷰 1-1). 값은 homeState 모듈에 있어 홈을 다시 그려도 남는다 (2-1)
  const [updating, setUpdating] = useState(() => updatePhase())
  const inUpdate = updating === 'starting' || updating === 'started'
  const { copied, copy } = useCopy<'compose' | 'dep'>()
  const runUpdate = async () => {
    if (inUpdate) return
    updateFlow.start()
    setUpdating(updatePhase())
    try {
      await startUpdate()
      updateFlow.started() // 곧 서버가 꺼졌다 새 버전으로 뜬다 — 세션이 사라져 다음 요청이 0-2로 보낸다
    } catch {
      updateFlow.fail() // 409 · 502 문구는 래퍼가 하단 바에 띄웠다
    }
    setUpdating(updatePhase())
  }
  // 새 서버가 떴으면 이 요청이 401이라 0-2로 간다. 아직 이 화면이면 옛 서버가 그대로거나 서버가 내려간 채다
  useEffect(() => {
    if (updating !== 'started') return
    const t = setTimeout(() => {
      getUpdate()
        .catch(() => null)
        .then(() => setUpdating(updatePhase()))
    }, updateStuckIn() ?? 0)
    return () => clearTimeout(t)
  }, [updating])

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
  const banner = updateBanner(update, data.run?.state === 'running')

  const menu = [
    {
      label: '딸깍 검사',
      // 업데이트를 누른 뒤 서버가 바뀌기 전까지는 막는다 — 막는 곳은 useScan 한 곳(상단 탭 · 재검사도 같이, !165 리뷰 1-2 · 2-1)
      sub: inUpdate ? '업데이트가 끝나면 할 수 있어요' : '',
      // 코드는 저장한 방식으로 가져온다 — 저장한 방식이 없으면 0-3 ④ (-224). 검사 중이면 알린다 (-214). 연결된 서비스가 없거나, 끊겼는데 다시 허용할 요청이 있으면 0-3 (F-HOME-01 예외, -211)
      go: () => scan(waiting),
    },
    // 지난 결과는 검사 이력에서 연다
    { label: '도구 상태', sub: '', go: () => setTools(true) },
    { label: '검사 이력', sub: '', go: () => (location.hash = '#/history') },
  ]

  return (
    <>
      {/* 팝업은 zoom 건 div 밖에 — showModal로 최상위에 떠 페이지 높이에 안 들어가니 줄여도 글자만 작아진다 (!153 리뷰 2-2) */}
      <HomeNotice home={data} />
      {tools && <ToolStatusDialog onClose={() => setTools(false)} />}
      {busy && <RunBusyDialog onClose={closeBusy} />}
      <div ref={page} className={s.page}>
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
            {banner && (
              <div className={s.notice} role="status">
                {updating === 'started' ? (
                  <span>업데이트 중이에요 · 1분쯤 뒤 다시 로그인해 주세요</span>
                ) : (
                  <>
                    <span>{banner.text}</span>
                    {banner.button && (
                      <Button size="sm" arrow={false} disabled={banner.disabled || inUpdate} onClick={runUpdate}>
                        {updating === 'starting' ? '업데이트 준비 중…' : '업데이트'}
                      </Button>
                    )}
                    {updating === 'stuck' && (
                      <span className={s.muted}>
                        업데이트가 끝나지 않았어요 · <code className={s.cmd}>docker logs ddalkkak-update</code>로 이유를 볼 수 있어요
                      </span>
                    )}
                    {banner.hint && <span className={s.muted}>{banner.hint}</span>}
                    {banner.command && (
                      <>
                        <code className={s.cmd}>{banner.command}</code>
                        <Button variant="outline" size="sm" onClick={() => copy(banner.command!, 'compose')}>
                          {copied === 'compose' ? '복사됨' : '복사'}
                        </Button>
                      </>
                    )}
                  </>
                )}
              </div>
            )}
            {service?.starterOutdated && (
              // 업그레이드 전에 허용한 앱 — 토큰을 몰라 API 목록 · 측정값이 401이라 검사가 멈춘다 (-221)
              <div className={s.notice} role="status">
                <span>
                  {service.name}의 스타터킷이 옛 버전이에요 · v1.1.0 이상으로 올리고 앱을 다시 켠 뒤 딸깍 검사에서 다시 허용해 주세요
                </span>
                <code className={s.cmd}>{KIT_DEP}</code>
                <Button variant="outline" size="sm" onClick={() => copy(KIT_DEP, 'dep')}>
                  {copied === 'dep' ? '복사됨' : '복사'}
                </Button>
              </div>
            )}
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
      </div>
    </>
  )
}
