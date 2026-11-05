// 서버가 준 시각("2026-11-07T14:35:00+09:00")을 브라우저 시간대와 상관없이 서버 시각 그대로 보여 준다

const parts = (iso: string) => {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(iso)
  return m ? { y: m[1], mo: m[2], d: m[3], h: m[4], mi: m[5] } : null
}

/** 10/18 14:02 */
export function shortDateTime(iso: string) {
  const p = parts(iso)
  return p ? `${p.mo}/${p.d} ${p.h}:${p.mi}` : iso
}

/** 10월 21일 14:35 */
export function koDateTime(iso: string) {
  const p = parts(iso)
  return p ? `${Number(p.mo)}월 ${Number(p.d)}일 ${p.h}:${p.mi}` : iso
}

/** 2026-10-21 */
export function dateOnly(iso: string) {
  const p = parts(iso)
  return p ? `${p.y}-${p.mo}-${p.d}` : iso
}

/** 14:35 */
export function timeOnly(iso: string) {
  const p = parts(iso)
  return p ? `${p.h}:${p.mi}` : iso
}

/** 방금 · N분 전 · N시간 전 · N일 전. 미래 시각(가짜 데이터 날짜가 오늘보다 뒤)은 "방금" */
export function ago(iso: string, now = Date.now()) {
  const sec = Math.max(0, (now - Date.parse(iso)) / 1000)
  if (!Number.isFinite(sec) || sec < 60) return '방금'
  if (sec < 3600) return `${Math.floor(sec / 60)}분 전`
  if (sec < 86400) return `${Math.floor(sec / 3600)}시간 전`
  return `${Math.floor(sec / 86400)}일 전`
}

/** 가동 3일 14시간 */
export function uptime(sec: number) {
  const d = Math.floor(sec / 86400)
  const h = Math.floor((sec % 86400) / 3600)
  return d > 0 ? `${d}일 ${h}시간` : `${h}시간 ${Math.floor((sec % 3600) / 60)}분`
}

const trim = (n: number) => (Math.round(n * 10) / 10).toString()
/** 5018MB → 4.9 */
export const gb = (mb: number) => trim(mb / 1024)

/** 14:41:08 */
export const timeSec = (iso: string) => /T(\d{2}:\d{2}:\d{2})/.exec(iso)?.[1] ?? iso
