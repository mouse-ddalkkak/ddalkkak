import { useState } from 'react'
import { useApp } from '../app/AppContext'
import type { ScreenId } from '../app/Layout'
import { logout } from '../api/auth'
import { getHome } from '../api/home'
import { usePolling } from '../hooks/usePolling'
import logo from '../assets/img/hamster-logo.png'
import NavTab from './NavTab'
import Dialog from './Dialog'
import AiForm from './AiForm'
import RunChip from './RunChip'
import RunBusyDialog from './RunBusyDialog'
import { useScan } from '../hooks/useScan'
import s from './TopBar.module.css'

// 공통 헤더 (screens.md 공통 틀 · F-HOME-04). 0-3 이후에만 탭 · AI 표시 · admin ▾
const BEFORE_LOGIN: (ScreenId | null)[] = [null, '0-0', '0-1', '0-2']
const CHECK: (ScreenId | null)[] = ['3', '4', '5']
const HISTORY: (ScreenId | null)[] = ['6', '7', '8']
const SPARKS: [string, string, number][] = [
  ['3%', '22%', 12], ['14%', '64%', 10], ['34%', '18%', 10], ['52%', '70%', 12], ['71%', '24%', 10], ['91%', '58%', 12],
]

export default function TopBar({ screen }: { screen: ScreenId | null }) {
  const { ai, user, clearSession, setHome } = useApp()
  const [menu, setMenu] = useState(false)
  const [aiOpen, setAiOpen] = useState(false)
  const { scan, busy, closeBusy } = useScan()
  const authed = !BEFORE_LOGIN.includes(screen)
  // 0-3은 연결 전까지 5초마다 직접 부른다 (screens.md 0-3)
  const watchHome = authed && screen !== '0-3'

  // 홈 데이터는 여기 한 곳에서 10초마다 받는다 — 칩은 모든 화면, 1 홈 · 5 서버 상태가 같은 값을 쓴다 (api.md "폴링 주기").
  // 화면이 바뀌면 바로 다시 받는다 — 결과(6)를 연 뒤 칩이 10초 동안 '검사 완료'로 남지 않게
  usePolling(
    async (alive) => {
      if (!watchHome) return true
      const d = await getHome()
      if (alive()) setHome(d)
    },
    10_000,
    [watchHome, screen],
  )

  // "AI 확인 중…"은 AI 설정 응답을 기다리는 동안
  const aiLabel = ai === null ? 'AI 확인 중…' : ai.connected ? 'AI 연결됨' : 'AI 없음'

  const onLogout = async () => {
    setMenu(false)
    // 서버가 꺼져 로그아웃 요청이 실패해도 화면 세션은 지운다
    try {
      await logout()
    } catch {
      // 문구는 래퍼가 띄웠다
    }
    clearSession()
    location.hash = '#/login'
  }

  return (
    <header className={s.header}>
      {SPARKS.map(([l, t, z]) => (
        <span key={l} className={s.spark} style={{ left: l, top: t, width: z, height: z }} />
      ))}
      <div className={s.inner}>
        <a href={authed ? '#/' : undefined} className={s.logo}>
          <img src={logo} alt="딸깍 로고" className={s.logoImg} />
          <span className={s.logoText}>딸깍</span>
        </a>
        {authed && (
          <nav className={s.tabs}>
            {/* 홈 딸깍 검사와 같다 — 저장한 코드 연결로 바로 준비, 없으면 0-3 ④. 검사 중이면 알린다 (-214 · -245) */}
            <NavTab
              href="#/"
              on={CHECK.includes(screen)}
              onClick={(e) => {
                e.preventDefault()
                scan()
              }}
            >
              검사
            </NavTab>
            <NavTab href="#/history" on={HISTORY.includes(screen)}>이력</NavTab>
          </nav>
        )}
        {authed && (
          <div className={s.right}>
            {watchHome && <RunChip />}
            {/* AI 칩을 누르면 admin ▾ "AI 연결"과 같은 팝업 (-214) */}
            <button type="button" className={s.aiChip} data-on={ai?.connected ? '' : undefined} onClick={() => setAiOpen(true)}>
              <span className={s.aiDot} />
              {aiLabel}
            </button>
            <div className={s.adminWrap}>
              <button type="button" className={s.admin} onClick={() => setMenu((m) => !m)}>
                <span className={s.avatar}>{(user ?? 'admin').slice(0, 1).toUpperCase()}</span>
                {user ?? 'admin'} ▾
              </button>
              {menu && (
                <div className={s.menu}>
                  <button
                    type="button"
                    className={s.menuItem}
                    onClick={() => {
                      setMenu(false)
                      setAiOpen(true)
                    }}
                  >
                    AI 연결
                  </button>
                  <button type="button" className={s.menuItem} onClick={onLogout}>
                    로그아웃
                  </button>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
      {/* admin ▾ "AI 연결" — 0-3 ④와 같은 폼 (F-AI-01). 연결 확인이 성공한 값만 저장 */}
      {aiOpen && (
        <Dialog title="AI 연결 · 선택" onClose={() => setAiOpen(false)}>
          <AiForm />
        </Dialog>
      )}
      {busy && <RunBusyDialog onClose={closeBusy} />}
    </header>
  )
}
