import { useRef, useState } from 'react'
import { getHome, type Home as HomeData } from '../../api/home'
import { usePolling } from '../../hooks/usePolling'
import { useKeys } from '../../hooks/useKeys'
import { pressable } from '../../utils/keys'
import TitleFrame from '../../components/TitleFrame'
import Gauge from '../../components/Gauge'
import Led from '../../components/Led'
import ScoreRow from './ScoreRow'
import ClockPill from './ClockPill'
import { gb, shortDateTime, dateOnly, uptime } from '../../utils/format'
import { gaugeColor, scoreColor } from '../../utils/score'
import { homeGauges, serverState } from './homeState'
import s from './Home.module.css'

// 1 홈 — GET /api/home 10초마다 (api.md "폴링 주기")
export default function Home() {
  const [data, setData] = useState<HomeData>()
  const [sel, setSel] = useState(0)
  const menuRef = useRef<{ go: () => void }[]>([])
  const itemRefs = useRef<(HTMLDivElement | null)[]>([])
  // ↑↓ 이동 · ENTER 선택 (F-HOME-05). Tab으로 메뉴에 포커스가 있으면 포커스도 같이 옮겨 ENTER가 고른 항목을 연다
  const move = (d: number) => {
    const n = Math.max(0, Math.min(menuRef.current.length - 1, sel + d))
    setSel(n)
    if (itemRefs.current.includes(document.activeElement as HTMLDivElement)) itemRefs.current[n]?.focus()
  }
  useKeys({ ArrowUp: () => move(-1), ArrowDown: () => move(1), Enter: () => menuRef.current[sel]?.go() })

  usePolling(
    async (alive) => {
      const d = await getHome()
      if (alive()) setData(d)
    },
    10_000,
    [],
  )

  if (!data) return null
  const { service, health, lastRun } = data

  const menu = [
    {
      label: '딸깍 검사',
      sub: '① 코드 연결 → ② 검사 범위 → 시작',
      // 연결된 서비스가 없으면 0-3 (F-HOME-01 예외)
      go: () => (location.hash = service ? '#/code' : '#/onboarding'),
    },
    ...(lastRun ? [{ label: '지난 결과', sub: shortDateTime(lastRun.at), go: () => (location.hash = `#/runs/${lastRun.runId}`) }] : []),
    { label: '검사 이력', sub: '', go: () => (location.hash = '#/history') },
  ]
  menuRef.current = menu

  // 끊겨도 게이지 3개는 회색(빈칸 · "--")으로 남긴다 (F-CONN-07)
  const subs = health ? ['', `${gb(health.mem.usedMb)} / ${gb(health.mem.totalMb)} GB`, `${health.disk.usedGb} / ${health.disk.totalGb} GB`] : []
  const gauges = homeGauges(health).map((g, i) => ({ ...g, d: subs[i] ?? '', color: g.v == null ? scoreColor(null) : gaugeColor(g.v) }))
  const state = serverState(service)

  return (
    <div className={s.wrap}>
      {/* 지난 검사 — 검사가 없으면 카드 숨김 (F-HOME-03) */}
      {lastRun ? (
        <section className={s.side}>
          <div className={s.sideHead}>
            <span className={s.sideTitle}>지난 검사</span>
            <span className={s.muted}>{shortDateTime(lastRun.at)}</span>
          </div>
          <div className={s.scores}>
            <ScoreRow name="성능" value={lastRun.scores.perf} />
            <ScoreRow name="보안" value={lastRun.scores.sec} />
            <ScoreRow name="코드" value={lastRun.scores.code} />
          </div>
          <div className={s.summary}>
            {lastRun.topDanger && (
              <div className={s.sumRow}>
                <span className={s.danger}>위험</span>
                <span>{lastRun.topDanger}</span>
              </div>
            )}
            {lastRun.topWarn && (
              <div className={s.sumRow}>
                <span className={s.warn}>주의</span>
                <span>{lastRun.topWarn}</span>
              </div>
            )}
            <div className={s.sumRow}>
              <span className={s.pass}>통과</span>
              <span>
                {lastRun.passed}개
                {lastRun.skipped.count > 0 && (
                  <>
                    {' '}· 미검사 {lastRun.skipped.count}
                    {lastRun.skipped.reason && <span className={s.muted}> ({lastRun.skipped.reason})</span>}
                  </>
                )}
              </span>
            </div>
          </div>
        </section>
      ) : (
        <div className={s.sideGap} />
      )}

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
        </TitleFrame>
        <div className={s.menu}>
          {menu.map((m, i) => (
            <div key={m.label} ref={(el) => void (itemRefs.current[i] = el)} className={s.item} onMouseEnter={() => setSel(i)} onFocus={() => setSel(i)} {...pressable(m.go)}>
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

      <section className={s.side}>
        <div className={s.sideHead}>
          <span className={s.sideTitle}>서버 상태</span>
          <span className={s.state} style={{ color: `var(--${state.ok ? 'green' : 'coral'}-400)` }}>
            <Led color={state.ok ? 'green-400' : 'coral-400'} />
            {state.text}
          </span>
        </div>
        {gauges.map((g) => (
          <div key={g.k} className={s.gauge}>
            <div className={s.gaugeHead}>
              <span>{g.k}</span>
              <span className={s.grow} />
              <span className={s.gaugeVal} style={{ color: g.color }}>
                {g.v == null ? '--' : `${g.v}%`}
              </span>
            </div>
            <Gauge pct={g.v ?? 0} cells={10} color={g.color} />
            {g.d && <span className={s.gaugeSub}>{g.d}</span>}
          </div>
        ))}
        {health && (
          <span className={s.uptime}>
            가동 {uptime(health.uptimeSec)} · {dateOnly(health.serverTime)} KST
          </span>
        )}
      </section>
    </div>
  )
}
