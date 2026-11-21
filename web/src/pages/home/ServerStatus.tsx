import type { Home } from '../../api/home'
import Gauge from '../../components/Gauge'
import Led from '../../components/Led'
import { dateOnly, timeSec, uptime } from '../../utils/format'
import { gaugeColor, scoreColor } from '../../utils/score'
import { gaugeSubs, homeGauges, serverState, shownHealth } from './homeState'
import s from './Home.module.css'

/**
 * 서버 상태 박스 — 1 홈 · 5 검사 진행이 같이 쓴다 (-214). 값은 스타터킷 생존 신호, 앱 연결 전에는 딸깍이 도는 곳을 잰 값(-256).
 * 끊겨도 게이지 3개는 회색(빈칸 · "--")으로 남기고, 무엇을 재는지는 그대로 보여 준다 (F-CONN-07)
 */
export default function ServerStatus({ home, className }: { home: Home; className?: string }) {
  const { service } = home
  const health = shownHealth(home)
  const subs = gaugeSubs(health, !service)
  const gauges = homeGauges(health).map((g, i) => ({ ...g, d: subs[i], color: g.v == null ? scoreColor(null) : gaugeColor(g.v) }))
  const state = serverState(service)
  const tone = state.ok ? 'green-400' : state.idle ? 'ink-400' : 'coral-400'
  return (
    <section className={`${s.side} ${className ?? ''}`}>
      <div className={s.sideHead}>
        <span className={s.sideTitle}>서버 상태</span>
        <span className={s.state} style={{ color: `var(--${tone})` }}>
          <Led color={tone} blink={!state.idle} />
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
          <span className={s.gaugeSub}>{g.d}</span>
        </div>
      ))}
      {health && (
        <span className={s.uptime}>
          {service ? '가동' : '딸깍 가동'} {uptime(health.uptimeSec)} · 갱신 {dateOnly(health.serverTime)} {timeSec(health.serverTime)} KST
        </span>
      )}
    </section>
  )
}
