// div 버튼 · 5 오류 팝업 Tab — React 없는 순수 모듈이라 web/test/keys.test.ts가 돌린다

/** div를 버튼처럼: Tab으로 오고 ENTER · 스페이스로 누른다. 안쪽 버튼에서 온 키는 그 버튼 몫 */
export function pressable(fn: () => void) {
  return {
    role: 'button' as const,
    tabIndex: 0,
    onClick: fn,
    onKeyDown: (e: { key: string; target: unknown; currentTarget: unknown; preventDefault: () => void }) => {
      if ((e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget) {
        e.preventDefault()
        fn()
      }
    },
  }
}

/**
 * 5 오류 팝업 Tab 가두기: 옮길 버튼, 브라우저에 맡길 때는 null. 포커스가 팝업 밖(body 포함)이어도 끌어온다.
 * 팝업 안은 버튼 목록이 아니라 popup.contains로 본다 — 스크롤이 생긴 로그 칸처럼 버튼이 아닌 곳도 팝업 안이다 (-194)
 */
export function trapTab<T>(
  popup: { querySelectorAll(sel: 'button'): Iterable<T>; contains(node: unknown): boolean },
  active: unknown,
  shift: boolean,
): T | null {
  const btns = [...popup.querySelectorAll('button')]
  if (popup.contains(active) && active !== (shift ? btns[0] : btns.at(-1))) return null
  return (shift ? btns.at(-1) : btns[0]) ?? null
}
