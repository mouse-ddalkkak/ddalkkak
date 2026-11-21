/** components/Select 후보 거르기 — 입력이 들어 있는 것만(대소문자 무시). 비었거나 후보와 똑같으면 전부 (-257) */
export function filterOptions(options: string[], text: string): string[] {
  const q = text.trim().toLowerCase()
  if (!q || options.includes(text)) return options
  return options.filter((o) => o.toLowerCase().includes(q))
}
