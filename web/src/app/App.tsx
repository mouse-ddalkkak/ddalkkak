import { Fragment, useEffect, useState, type ReactNode } from 'react'
import { AppProvider, useApp } from './AppContext'
import Layout, { type ScreenId } from './Layout'
import { getSetup } from '../api/setup'
import { ApiError } from '../api/client'
import Intro from '../pages/intro/Intro'
import Setup from '../pages/setup/Setup'
import Login from '../pages/login/Login'
import Onboarding from '../pages/onboarding/Onboarding'
import Home from '../pages/home/Home'
import CodeSource from '../pages/code-source/CodeSource'
import Prepare from '../pages/prepare/Prepare'
import Scope from '../pages/scope/Scope'
import Run from '../pages/run/Run'
import Result from '../pages/result/Result'
import Issue from '../pages/issue/Issue'
import History from '../pages/history/History'

// hash 라우트 표 (docs/web.md §2). 화면 ID 13개 ↔ 페이지 12개: 2-a · 2-b는 #/code 한 페이지가 응답으로 나눈다
const ROUTES: [RegExp, ScreenId, (m: string[]) => ReactNode][] = [
  [/^\/intro$/, '0-0', () => <Intro />],
  [/^\/setup$/, '0-1', () => <Setup />],
  [/^\/login$/, '0-2', () => <Login />],
  [/^\/onboarding$/, '0-3', () => <Onboarding />],
  [/^\/$/, '1', () => <Home />],
  [/^\/code$/, '2', () => <CodeSource />],
  [/^\/prepare\/([^/]+)$/, '3', (m) => <Prepare runId={m[1]} />],
  [/^\/prepare\/([^/]+)\/scope$/, '4', (m) => <Scope runId={m[1]} />],
  [/^\/runs\/([^/]+)\/progress$/, '5', (m) => <Run runId={m[1]} />],
  [/^\/runs\/([^/]+)$/, '6', (m) => <Result runId={m[1]} />],
  [/^\/runs\/([^/]+)\/issues\/([^/]+)$/, '7', (m) => <Issue runId={m[1]} issueId={m[2]} />],
  [/^\/history$/, '8', () => <History />],
]

const currentPath = () => decodeURI(location.hash.replace(/^#/, '')) || '/'

function resolve(path: string) {
  for (const [re, id, render] of ROUTES) {
    const m = re.exec(path)
    if (m) return { id, node: render(m) }
  }
  return null
}

function Router() {
  const { loadSession } = useApp()
  const [path, setPath] = useState(currentPath)
  const [ready, setReady] = useState(false)

  useEffect(() => {
    const onHash = () => setPath(currentPath())
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  // 강제 이동 ① 시작 때 한 번 (§2). 이 확인이 끝날 때까지 pages를 그리지 않는다
  useEffect(() => {
    let cancelled = false
    const before = currentPath()
    const firstScreens = before === '/intro' || before === '/setup'
    ;(async () => {
      try {
        const setup = await getSetup()
        if (setup.needsAck) {
          location.hash = '#/intro'
          return
        }
        try {
          await loadSession()
          if (firstScreens) location.hash = '#/'
        } catch (e) {
          // 401이면 래퍼가 이미 #/login 으로 보냈다
          if (e instanceof ApiError && e.status === 401 && firstScreens) location.hash = '#/login'
        }
      } catch {
        // 서버 꺼짐 — 하단 바 문구는 래퍼가 띄웠다
      } finally {
        if (!cancelled) {
          setPath(currentPath())
          setReady(true)
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [loadSession])

  const route = resolve(path)
  useEffect(() => {
    if (ready && !route) location.replace('#/')
  }, [ready, route])

  if (!ready || !route) return <Layout screen={null}>{null}</Layout>
  // key: 경로(회차 id 포함)가 바뀌면 화면을 새로 그려 폴링 · 입력값을 버린다. 공통 틀(배경 움직임)은 그대로 둔다
  return (
    <Layout screen={route.id}>
      <Fragment key={path}>{route.node}</Fragment>
    </Layout>
  )
}

export default function App() {
  return (
    <AppProvider>
      <Router />
    </AppProvider>
  )
}
