/** 깜빡이는 네모 LED. 색은 토큰 변수 이름 (green-400 · cyan-300 · coral-400 …) */
export default function Led({ color, blink = true, size = 16, round = false }: { color: string; blink?: boolean; size?: number; round?: boolean }) {
  return (
    <span
      aria-hidden
      style={{
        display: 'inline-block',
        width: size,
        height: size,
        flex: 'none',
        borderRadius: round ? '50%' : 0,
        background: `var(--${color})`,
        boxShadow: `0 0 0 3px color-mix(in oklch, var(--${color}) 35%, transparent)`,
        animation: blink ? 'ttk-blink var(--dur-blink-led) steps(1) infinite' : undefined,
      }}
    />
  )
}
