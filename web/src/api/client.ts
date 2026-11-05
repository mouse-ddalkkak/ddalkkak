// fetch 래퍼 하나 — 화면 · 기능 함수는 fetch를 직접 쓰지 않는다 (docs/web.md §3)
// 가짜/실제 분기는 여기 한 곳 (§6). 가짜 모드면 fake.ts가 같은 모양의 Response를 돌려줘 아래 처리를 그대로 탄다

export class ApiError extends Error {
  constructor(public status: number, public code: string | null, message: string) {
    super(message)
  }
}

type Handlers = {
  showMessage: (text: string, opts?: { sticky?: boolean }) => void
  clearMessage: () => void
  onUnauthorized: () => void
}

let handlers: Handlers = { showMessage: () => {}, clearMessage: () => {}, onUnauthorized: () => {} }

/** app/AppContext.tsx가 처음 그려질 때 한 번 넘겨 준다 (api/는 app/을 import하지 않는다) */
export function setHandlers(h: Handlers) {
  handlers = h
}

// screens.md §3 "서버 꺼짐"
const OFFLINE = '딸깍 서버에 연결할 수 없어요'

export type CallOptions = {
  body?: unknown
  form?: FormData
  /** 화면이 따로 처리할 오류 code — 하단 바에 띄우지 않고 던지기만 한다 */
  handle?: string[]
}

// 401이어도 0-2로 보내지 않는 곳 (설치 확인 · 로그인 자체)
const noRedirect = (method: string, path: string) =>
  path.startsWith('/api/setup') || (method === 'POST' && path === '/api/auth/login')

export const IS_FAKE = import.meta.env.VITE_FAKE === '1'

async function send(method: string, path: string, opts: CallOptions): Promise<Response> {
  if (IS_FAKE) {
    const { fakeFetch } = await import('./fake')
    return fakeFetch(method, path)
  }
  const init: RequestInit = { method, credentials: 'same-origin' }
  if (opts.form) init.body = opts.form
  else if (opts.body !== undefined) {
    init.body = JSON.stringify(opts.body)
    init.headers = { 'Content-Type': 'application/json' }
  }
  return fetch(path, init)
}

/** SSE 열기 (화면 5). 가짜 모드면 fake.ts가 같은 방식(addEventListener)의 재생 객체를 준다 */
export async function openEvents(path: string): Promise<EventSource> {
  if (IS_FAKE) {
    const { fakeEvents } = await import('./fake')
    return fakeEvents(path)
  }
  return new EventSource(path)
}

function offline(): never {
  handlers.showMessage(OFFLINE, { sticky: true })
  throw new ApiError(0, null, OFFLINE)
}

export async function api<T>(method: string, path: string, opts: CallOptions = {}): Promise<T> {
  let res: Response
  try {
    res = await send(method, path, opts)
  } catch {
    offline()
  }

  if (res.ok) {
    handlers.clearMessage()
    if (res.status === 204) return undefined as T
    const type = res.headers.get('Content-Type') ?? ''
    if (type.includes('application/json')) return (await res.json()) as T
    return (await res.text()) as T
  }

  let err: { code?: unknown; message?: unknown } | null = null
  try {
    err = await res.json()
  } catch {
    err = null
  }
  // 본문이 {code, message}가 아니면 서버 꺼짐 (개발 중 vite 프록시 5xx · 배포 fetch 실패 둘 다)
  if (!err || typeof err.code !== 'string' || typeof err.message !== 'string') offline()
  handlers.clearMessage()

  const e = new ApiError(res.status, err.code as string, err.message as string)
  if (res.status === 401 && !noRedirect(method, path)) {
    handlers.onUnauthorized()
    throw e
  }
  if (!opts.handle?.includes(e.code!)) handlers.showMessage(e.message)
  throw e
}
