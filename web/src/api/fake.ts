// 가짜 응답 — contracts/를 import하는 유일한 파일 (docs/web.md §6)
// client.ts가 가짜 모드(VITE_FAKE=1)일 때만 동적으로 불러와 배포 빌드에는 들어가지 않는다
// 응답은 동작에 따라 바뀌지 않는다. 상태를 기억하지 않으므로 새로고침해도 같은 URL · ?fake= 면 같은 응답이다

type Contract = { calls: Record<string, { responses?: Record<string, unknown>; events?: { event: string; data: unknown }[] }> }

const files = import.meta.glob('../../../contracts/4-screen/*.json', { eager: true, import: 'default' }) as Record<string, Contract>

type Variant = { name: string; status: number; body: unknown }

// "METHOD 경로" → 응답 변형들. 같은 API가 여러 파일에 있으면 openapi 한 곳에서 뽑은 같은 값이라 처음 것만 쓴다
// GET /api/code-source도 응답 이름(previous · noSaved)으로 고른다 (-224 전에는 2-a · 2-b 파일 이름)
const calls = new Map<string, Variant[]>()
for (const contract of Object.values(files)) {
  for (const [key, call] of Object.entries(contract.calls)) {
    const responses = Object.entries(call.responses ?? {})
    if (calls.has(key) || responses.length === 0) continue
    calls.set(
      key,
      responses.map(([label, body]) => {
        const [code, ...rest] = label.split(' ')
        return { name: rest.length ? rest.join(' ') : code, status: Number(code), body }
      }),
    )
  }
}

// 템플릿 경로 중 고정 칸이 가장 많이 맞는 것 (/api/runs/prepare/x 가 /api/runs/{id} 가 아니라 prepare 쪽으로)
function match(method: string, path: string): { key: string; variants: Variant[] } | null {
  const segs = path.split('?')[0].split('/')
  let best: { key: string; variants: Variant[]; fixed: number } | null = null
  for (const [key, variants] of calls) {
    const [m, tpl] = key.split(' ')
    if (m !== method) continue
    const t = tpl.split('/')
    if (t.length !== segs.length) continue
    let fixed = 0
    const ok = t.every((part, i) => {
      if (part.startsWith('{')) return true
      fixed++
      return part === segs[i]
    })
    if (ok && (!best || fixed > best.fixed)) best = { key, variants, fixed }
  }
  return best
}

function pick(key: string, variants: Variant[]): Variant {
  const wanted = (new URLSearchParams(location.search).get('fake') ?? '').split(',').filter(Boolean)
  for (const name of wanted) {
    const hit = variants.find((v) => v.name === name)
    if (hit) return hit
  }
  // 0-0 · 0-1은 ?fake=first 로 본다. 기본이 first면 새로고침마다 0-0으로 간다
  if (key === 'GET /api/setup') return variants.find((v) => v.name === 'acked')!
  // 3 "기본 범위로 바로 가기"는 누르면 4로 가야 한다. 첫 2xx(200)는 done: false라 3에 머문다 (web.md §7)
  if (key === 'POST /api/runs/prepare/{id}/use-default') return variants.find((v) => v.name === 'done')!
  // 5 중단은 1로 가야 한다. 첫 2xx(running)는 SSE done을 기다리는데 가짜 SSE는 done {status: done}이라 6으로 간다
  if (key === 'POST /api/runs/{id}/stop') return variants.find((v) => v.name === 'stopped')!
  // 오류 팝업 "여기까지 결과 보기"는 6으로 가야 한다. 첫 2xx(running)는 SSE done을 기다린다 (-143)
  if (key === 'POST /api/runs/{id}/finish-partial') return variants.find((v) => v.name === 'partial')!
  return variants.find((v) => v.status >= 200 && v.status < 300) ?? variants[0]
}

export async function fakeFetch(method: string, path: string): Promise<Response> {
  const found = match(method, path)
  const time = new Date().toISOString().slice(11, 23)
  if (!found) {
    console.info(`[fake] ${time} ${method} ${path} → 예시 없음`)
    return new Response(null, { status: 502 })
  }
  const v = pick(found.key, found.variants)
  console.info(`[fake] ${time} ${method} ${path} → ${v.status} ${v.name}`)
  if (v.body === null || v.status === 204) return new Response(null, { status: v.status })
  if (typeof v.body === 'string') return new Response(v.body, { status: v.status, headers: { 'Content-Type': 'text/plain' } })
  return new Response(JSON.stringify(v.body), { status: v.status, headers: { 'Content-Type': 'application/json' } })
}

// 가짜 SSE (화면 5) — 5-run.json의 events를 브라우저 안에서 1초 간격으로 재생한다 (docs/web.md §6)
// 기본 progress → log → done, ?fake=error 면 progress → log → error에서 멈춤
const events = Object.values(files).flatMap((c) => c.calls['GET /api/runs/{id}/events']?.events ?? [])

export function fakeEvents(path: string): EventSource {
  const wantError = (new URLSearchParams(location.search).get('fake') ?? '').split(',').includes('error')
  const order = wantError ? ['progress', 'log', 'error'] : ['progress', 'log', 'done']
  const target = new EventTarget()
  const timers: number[] = []
  const src = Object.assign(target, {
    readyState: 0,
    close() {
      src.readyState = 2
      timers.forEach(clearTimeout)
    },
  })
  const send = (name: string, data?: unknown) => {
    if (src.readyState === 2) return
    console.info(`[fake] ${new Date().toISOString().slice(11, 23)} SSE ${path} → ${name}`)
    target.dispatchEvent(data === undefined ? new Event(name) : new MessageEvent(name, { data: JSON.stringify(data) }))
  }
  timers.push(
    window.setTimeout(() => {
      src.readyState = 1
      send('open')
    }),
  )
  order.forEach((name, i) => {
    const e = events.find((x) => x.event === name)
    if (e) timers.push(window.setTimeout(() => send(name, e.data), (i + 1) * 1000))
  })
  return src as unknown as EventSource
}
