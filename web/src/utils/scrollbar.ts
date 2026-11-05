// 오버레이 스크롤바 막대 크기 · 위치 — web/test/scrollbar.test.ts

/** 스크롤이 필요 없으면 null. 높이 = max(40, 화면² / 문서), 위치 = scrollTop / (문서 - 화면) × (화면 - 막대) */
export function thumbBox(view: number, doc: number, scrollTop: number): { height: number; top: number } | null {
  if (doc <= view) return null
  const height = Math.max(40, (view * view) / doc)
  return { height, top: (scrollTop / (doc - view)) * (view - height) }
}
