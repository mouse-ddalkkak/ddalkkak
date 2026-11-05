import type { ReactNode } from 'react'

/** 작은 칩: fill(채움) · line(테두리) · plain(글자만). color는 토큰 변수 이름 */
export default function Badge({ kind = 'fill', color = 'ink-700', children }: { kind?: 'fill' | 'line' | 'plain'; color?: string; children: ReactNode }) {
  const c = `var(--${color})`
  const style =
    kind === 'fill'
      ? { background: c, color: color === 'ink-700' ? 'var(--white)' : 'var(--ink-900)', padding: '4px 8px' }
      : kind === 'line'
        ? { border: `4px solid ${c}`, color: c, padding: '2px 8px' }
        : { color: c }
  return <span style={{ font: 'var(--type-small)', letterSpacing: 2, whiteSpace: 'nowrap', ...style }}>{children}</span>
}
