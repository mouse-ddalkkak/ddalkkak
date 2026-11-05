import { useEffect, useRef } from 'react'
import { pageKey, type PageKey } from '../utils/keys'

/** 화면 키 (F-HOME-05 · Layout 하단 바 힌트와 같은 표). 넘긴 키만 쓰고, 처리하면 기본 동작(스크롤 등)을 막는다 */
export function useKeys(keys: Partial<Record<PageKey, () => void>>) {
  const latest = useRef(keys)
  latest.current = keys
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const k = pageKey(e, !!document.querySelector('dialog[open]'))
      const fn = k && latest.current[k]
      if (!fn) return
      e.preventDefault()
      fn()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])
}
