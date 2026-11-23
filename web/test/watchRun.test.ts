// 화면 5 SSE 연결 규칙 (docs/web.md §3 "SSE (화면 5)") — cd web && npm test
import { test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { watchRun } from '../src/pages/run/watchRun.ts'

const CONNECTING = 0
const OPEN = 1
const CLOSED = 2

class FakeSource extends EventTarget {
  readyState = CONNECTING
  closed = false
  close() {
    this.closed = true
    this.readyState = CLOSED
  }
  open() {
    this.readyState = OPEN
    this.dispatchEvent(new Event('open'))
  }
  send(type: string, data: unknown) {
    this.dispatchEvent(new MessageEvent(type, { data: JSON.stringify(data) }))
  }
  /** EventSource 자체 연결 오류: data 없는 Event */
  fail(state: number) {
    this.readyState = state
    this.dispatchEvent(new Event('error'))
  }
}

const snapshot = (status = 'running') => ({
  status,
  pct: 42,
  leftMin: 13,
  steps: [
    { step: 'sec', name: '1 보안', state: 'done', items: [] },
    {
      step: 'perf',
      name: '2 성능',
      state: 'running',
      items: [
        { key: 'perf-1', title: '주문 목록 피크', state: 'done' },
        { key: 'perf-2', title: '주문 생성 → 조회 흐름', state: 'running' },
      ],
    },
  ],
  logs: [],
})

const flush = () => new Promise((r) => setImmediate(r))

function setup(progress: () => Promise<unknown> = async () => snapshot()) {
  const sources: FakeSource[] = []
  const calls = { get: 0, snapshot: [] as unknown[], progress: [] as unknown[], log: [] as unknown[], error: [] as unknown[], leave: [] as string[] }
  const stop = watchRun({
    open: async () => {
      const s = new FakeSource()
      sources.push(s)
      return s
    },
    getProgress: async () => {
      calls.get++
      return progress() as never
    },
    on: {
      snapshot: (p) => calls.snapshot.push(p),
      progress: (d) => calls.progress.push(d),
      log: (d) => calls.log.push(d),
      error: (d) => calls.error.push(d),
      leave: (st) => calls.leave.push(st),
    },
  })
  return { sources, calls, stop }
}

test('열리면 GET /progress로 스냅샷을 받는다', async () => {
  const { sources, calls } = setup()
  await flush()
  sources[0].open()
  await flush()
  assert.equal(calls.get, 1)
  assert.equal(calls.snapshot.length, 1)
})

test('progress: step·item이 같으면 그대로, 바뀌면 GET /progress 다시', async () => {
  const { sources, calls } = setup()
  await flush()
  sources[0].open()
  await flush()
  sources[0].send('progress', { pct: 43, leftMin: 12, step: 'perf', item: 'perf-2' })
  await flush()
  assert.deepEqual(calls.progress, [{ pct: 43, leftMin: 12, step: 'perf', item: 'perf-2' }])
  assert.equal(calls.get, 1)
  sources[0].send('progress', { pct: 50, leftMin: 10, step: 'perf', item: 'perf-3' })
  await flush()
  assert.equal(calls.get, 2)
})

test('늦게 온 GET /progress 응답은 버린다 (마지막에 보낸 요청만 반영)', async () => {
  const pending: ((v: unknown) => void)[] = []
  const { sources, calls } = setup(() => new Promise((r) => pending.push(r)))
  await flush()
  sources[0].open() // GET #1
  await flush()
  sources[0].send('progress', { pct: 50, leftMin: 10, step: 'perf', item: 'perf-3' }) // GET #2
  await flush()
  const fresh = { ...snapshot(), pct: 50 }
  pending[1](fresh)
  await flush()
  pending[0](snapshot())
  await flush()
  assert.deepEqual(calls.snapshot, [fresh])
})

test('log는 그대로 넘기고, data 있는 error는 오류 팝업 (GET 안 부름)', async () => {
  const { sources, calls } = setup()
  await flush()
  sources[0].open()
  await flush()
  sources[0].send('log', { ts: 't', tag: 'k6', text: 'ERRO x' })
  sources[0].send('error', { step: 'perf', item: 'perf-2', title: 't', ts: 't', text: '서버가 응답을 멈췄어요', lines: [] })
  await flush()
  assert.equal(calls.log.length, 1)
  assert.equal(calls.error.length, 1)
  assert.equal(calls.get, 1)
  assert.equal(sources[0].closed, false)
})

test('error 뒤 다시 시도: 새로 열지 않고 같은 스트림으로 progress · error · done을 이어 받는다 (-156)', async () => {
  const stopEv = { step: 'perf', item: 'perf-2', title: 't', ts: 't', text: '서버가 응답을 멈췄어요', lines: [] }
  const { sources, calls } = setup()
  await flush()
  sources[0].open()
  await flush()
  sources[0].send('error', stopEv)
  sources[0].send('progress', { pct: 43, leftMin: 12, step: 'perf', item: 'perf-2' }) // POST /retry 뒤 서버가 같은 스트림으로
  sources[0].send('log', { ts: 't', tag: 'k6', text: 'perf-2 다시 시도' })
  sources[0].send('error', stopEv) // 또 멈춤 → 팝업 다시 (F-STOP-03)
  sources[0].send('done', { status: 'partial' })
  await flush()
  assert.equal(sources.length, 1)
  assert.equal(calls.progress.length, 1)
  assert.equal(calls.log.length, 1)
  assert.equal(calls.error.length, 2)
  assert.deepEqual(calls.leave, ['partial'])
})

test('done이면 닫고 status로 이동 (한 번만)', async () => {
  const { sources, calls } = setup()
  await flush()
  sources[0].open()
  await flush()
  sources[0].send('done', { status: 'done' })
  sources[0].send('done', { status: 'done' })
  await flush()
  assert.equal(sources[0].closed, true)
  assert.deepEqual(calls.leave, ['done'])
})

test('스냅샷 status가 running이 아니면 닫고 이동', async () => {
  const { sources, calls } = setup(async () => snapshot('stopped'))
  await flush()
  sources[0].open()
  await flush()
  assert.equal(sources[0].closed, true)
  assert.deepEqual(calls.leave, ['stopped'])
  assert.equal(calls.snapshot.length, 0)
})

test('연결 오류 · CLOSED → GET /progress, 3초 뒤 다시 확인하고 running이면 새로 연다', async () => {
  mock.timers.enable({ apis: ['setTimeout'] })
  try {
    let down = true
    const { sources, calls } = setup(async () => {
      if (down) throw new Error('offline')
      return snapshot()
    })
    await flush()
    sources[0].open()
    await flush()
    sources[0].fail(CLOSED)
    await flush()
    assert.equal(calls.get, 2)
    mock.timers.tick(3000) // 서버 꺼짐 → 3초 뒤 다시
    await flush()
    assert.equal(calls.get, 3)
    assert.equal(sources.length, 1)
    down = false
    mock.timers.tick(3000)
    await flush()
    await flush()
    assert.equal(sources.length, 2)
  } finally {
    mock.timers.reset()
  }
})

test('연결 오류 · CONNECTING → GET /progress만, 다시 여는 타이머 없음', async () => {
  mock.timers.enable({ apis: ['setTimeout'] })
  try {
    const { sources, calls } = setup()
    await flush()
    sources[0].open()
    await flush()
    sources[0].fail(CONNECTING)
    await flush()
    assert.equal(calls.get, 2)
    mock.timers.tick(10000)
    await flush()
    assert.equal(sources.length, 1)
  } finally {
    mock.timers.reset()
  }
})

test('stop() 뒤에는 닫히고 콜백이 오지 않는다', async () => {
  const { sources, calls, stop } = setup()
  await flush()
  sources[0].open()
  await flush()
  stop()
  assert.equal(sources[0].closed, true)
  sources[0].send('log', { ts: 't', tag: 'k6', text: 'x' })
  sources[0].send('done', { status: 'done' })
  await flush()
  assert.equal(calls.log.length, 0)
  assert.deepEqual(calls.leave, [])
})
