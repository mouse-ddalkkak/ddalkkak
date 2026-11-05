// 화면 키 판단 (functional-spec F-HOME-05) — React 없는 순수 모듈이라 web/test/keys.test.ts가 돌린다
export type PageKey = 'Enter' | 'Escape' | 'ArrowUp' | 'ArrowDown'

type Target = { tagName?: string; isContentEditable?: boolean; getAttribute?: (name: string) => string | null } | null
export type KeyLike = {
  key: string
  target: Target | EventTarget | null
  isComposing?: boolean
  repeat?: boolean
  ctrlKey?: boolean
  altKey?: boolean
  metaKey?: boolean
  shiftKey?: boolean
}

const KEYS: string[] = ['Enter', 'Escape', 'ArrowUp', 'ArrowDown']
const TYPING = ['INPUT', 'TEXTAREA', 'SELECT']
const CLICKABLE = ['BUTTON', 'A', 'SUMMARY']

/** 화면이 쓸 키. 입력칸 · 열린 팝업 · 한글 조합 · 수정 키는 무시, 버튼 위 ENTER는 그 버튼 몫 */
export function pageKey(e: KeyLike, dialogOpen: boolean): PageKey | null {
  if (!KEYS.includes(e.key) || dialogOpen) return null
  if (e.isComposing || e.ctrlKey || e.altKey || e.metaKey || e.shiftKey) return null
  if (e.repeat && (e.key === 'Enter' || e.key === 'Escape')) return null
  const t = e.target as Target
  const tag = t?.tagName ?? ''
  if (TYPING.includes(tag) || t?.isContentEditable) return null
  if (e.key === 'Enter' && (CLICKABLE.includes(tag) || t?.getAttribute?.('role') === 'button')) return null
  return e.key as PageKey
}

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
