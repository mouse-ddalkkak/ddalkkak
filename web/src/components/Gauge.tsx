import s from './Gauge.module.css'

/** 칸 게이지 — 홈 10칸 · 3 · 5 진행률 20칸 */
export default function Gauge({ pct, cells, color, tall = false }: { pct: number; cells: number; color: string; tall?: boolean }) {
  const on = Math.round(pct / (100 / cells))
  return (
    <div className={`${s.gauge} ${tall ? s.tall : ''}`} role="meter" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
      {Array.from({ length: cells }, (_, i) => (
        <span key={i} className={s.cell} style={i < on ? { background: color } : undefined} />
      ))}
    </div>
  )
}
