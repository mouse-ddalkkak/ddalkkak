/** 지금 zoom k에서 높이 h인 요소가 over만큼 넘칠 때 다음 zoom. 넘치지 않으면 그대로, 최소 min (-259) */
export function nextZoom(k: number, h: number, over: number, min = 0.6): number {
  if (over <= 0 || h <= 0) return k
  return Math.max(min, (k * (h - over)) / h)
}
