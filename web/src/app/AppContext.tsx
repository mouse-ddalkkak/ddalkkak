import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { setHandlers } from '../api/client'
import { getMe } from '../api/auth'
import { getAiSettings, type AiSettings } from '../api/ai'

// 공유 상태: 로그인 사용자 · AI 표시 · 하단 바 메시지 (docs/web.md §4)
type Ctx = {
  user: string | null
  /** null = 아직 응답 전 → 상단 바 "AI 확인 중…" */
  ai: AiSettings | null
  /** 로그인 확인 뒤(앱 시작 auth/me 성공 · 0-2 로그인 성공) 사용자 · AI 표시를 다시 채운다 */
  loadSession: () => Promise<void>
  setAi: (ai: AiSettings) => void
  clearSession: () => void
  message: string | null
  showMessage: (text: string, opts?: { sticky?: boolean }) => void
  clearMessage: () => void
}

const AppCtx = createContext<Ctx | null>(null)

export const useApp = () => useContext(AppCtx)!

const MESSAGE_MS = 1800 // screens.md §3

export function AppProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<string | null>(null)
  const [ai, setAi] = useState<AiSettings | null>(null)
  const [temp, setTemp] = useState<string | null>(null)
  const [sticky, setSticky] = useState<string | null>(null)
  const timer = useRef<number>(undefined)

  // 보통 메시지는 1.8초 뒤 사라진다. sticky(서버 꺼짐)는 clearMessage()까지 남고, 보통 메시지가 사라지면 다시 보인다
  const showMessage = useCallback((text: string, opts?: { sticky?: boolean }) => {
    if (opts?.sticky) {
      setSticky(text)
      return
    }
    setTemp(text)
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setTemp(null), MESSAGE_MS)
  }, [])
  const clearMessage = useCallback(() => setSticky(null), [])

  const loadSession = useCallback(async () => {
    const me = await getMe()
    setUser(me.username)
    setAi(null)
    try {
      setAi(await getAiSettings())
    } catch {
      // 실패 문구는 래퍼가 띄웠다. 표시는 "AI 확인 중…"으로 남는다
    }
  }, [])
  const clearSession = useCallback(() => {
    setUser(null)
    setAi(null)
  }, [])

  // 래퍼 ↔ Context 연결: 처음 그려질 때 한 번 (§3)
  const wired = useRef(false)
  if (!wired.current) {
    wired.current = true
    setHandlers({ showMessage, clearMessage, onUnauthorized: () => (location.hash = '#/login') })
  }
  useEffect(() => () => window.clearTimeout(timer.current), [])

  return (
    <AppCtx.Provider
      value={{ user, ai, loadSession, setAi, clearSession, message: temp ?? sticky, showMessage, clearMessage }}
    >
      {children}
    </AppCtx.Provider>
  )
}
