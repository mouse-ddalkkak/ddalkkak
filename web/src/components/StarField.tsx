import s from './StarField.module.css'

// 0-0 · 0-1 · 0-2: 작은 점 별이 천천히 올라감 / 그 밖: 흩뿌린 4각 별이 깜빡임 (Pixel Flow fx)
const SPARKLES: [string, string, number, number][] = [
  ['7%', '24%', 34, 0], ['18%', '61%', 22, 1.2], ['27%', '13%', 18, 2.6], ['36%', '78%', 30, 0.6], ['44%', '33%', 16, 3.1],
  ['52%', '88%', 20, 1.8], ['58%', '18%', 42, 0.3], ['66%', '54%', 18, 2.2], ['73%', '29%', 28, 3.6], ['81%', '72%', 22, 0.9],
  ['88%', '16%', 18, 2.9], ['93%', '47%', 32, 1.5], ['12%', '86%', 20, 3.3], ['3%', '52%', 16, 2], ['22%', '38%', 14, 4.1],
  ['31%', '50%', 24, 1], ['40%', '8%', 20, 2.4], ['48%', '64%', 14, 3.8], ['62%', '82%', 26, 0.2], ['70%', '9%', 16, 1.6],
  ['77%', '46%', 14, 4.4], ['85%', '90%', 24, 2.7], ['96%', '28%', 16, 0.7], ['9%', '6%', 18, 3.5], ['55%', '42%', 12, 1.3],
  ['34%', '94%', 16, 4.6],
]

let seed = 7
const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647
function layer(n: number, size: number, colors: string[]) {
  const pts: string[] = []
  for (let i = 0; i < n; i++) {
    const x = Math.round((rnd() * 2400) / size) * size
    const y = Math.round((rnd() * 1200) / size) * size
    const c = colors[i % colors.length]
    pts.push(`${x}px ${y}px 0 ${c}`, `${x}px ${y + 1200}px 0 ${c}`)
  }
  return pts.join(',')
}
const LAYERS = [
  { size: 4, shadow: layer(140, 4, ['var(--white)', 'var(--ink-300)']), dur: 120, op: 0.5 },
  { size: 6, shadow: layer(60, 6, ['var(--cyan-300)', 'var(--white)']), dur: 80, op: 0.8 },
  { size: 8, shadow: layer(16, 8, ['var(--cyan-100)']), dur: 50, op: 1 },
]

export default function StarField({ kind }: { kind: 'landing' | 'sparkles' }) {
  if (kind === 'landing')
    return (
      <div className={`${s.field} ${s.landing}`} aria-hidden>
        {LAYERS.map((l) => (
          <div
            key={l.size}
            className={s.layer}
            style={{ width: l.size, height: l.size, boxShadow: l.shadow, opacity: l.op, animationDuration: `${l.dur}s` }}
          />
        ))}
      </div>
    )
  return (
    <div className={s.field} aria-hidden>
      {SPARKLES.map(([left, top, size, delay], i) => (
        <span
          key={i}
          className={s.spark}
          style={{
            left, top, width: size, height: size, marginLeft: -size / 2, marginTop: -size / 2,
            filter: `drop-shadow(0 0 ${Math.round(size / 6)}px var(--star-glow))`,
            animationDuration: `${3.2 + (i % 4) * 0.7}s`, animationDelay: `${delay}s`,
          }}
        >
          <span className={s.core} />
          <span className={s.inner} />
        </span>
      ))}
    </div>
  )
}
