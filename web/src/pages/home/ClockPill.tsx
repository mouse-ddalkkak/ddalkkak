import { koDateTime } from '../../utils/format'
import s from './ClockPill.module.css'

/** 홈 상단 시계 — 서버 시각(health.serverTime)의 날짜와 시간. 10초 폴링마다 바뀐다 */
export default function ClockPill({ iso }: { iso: string }) {
  return <span className={s.pill}>{koDateTime(iso)}</span>
}
