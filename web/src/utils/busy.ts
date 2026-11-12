// 6 · 7 프롬프트 생성 중복 요청 막기 (-216) — 순수 모듈, web/test/busy.test.ts
// state만으로 막으면 다시 그리기 전 같은 태스크의 두 번째 클릭이 지나간다 → ref 같은 플래그로 먼저 막는다

/** 진행 중이면 바로 돌아가고, 아니면 setBusy(true) → fn → (실패해도) setBusy(false) */
export async function runOnce(flag: { current: boolean }, setBusy: (busy: boolean) => void, fn: () => Promise<void>) {
  if (flag.current) return
  flag.current = true
  setBusy(true)
  try {
    await fn()
  } finally {
    flag.current = false
    setBusy(false)
  }
}
