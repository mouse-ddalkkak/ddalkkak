// 화면 5 SSE 연결 규칙 (docs/web.md §3 "SSE (화면 5)") — React 밖에 두어 web/test/watchRun.test.ts가 가짜 EventSource로 돌린다
// type import만 둔다 (node --test가 이 파일을 그대로 실행한다)
import type { Progress, ProgressEvent, LogEvent, StopEvent } from '../../api/runs'

export type Source = Pick<EventSource, 'addEventListener' | 'close' | 'readyState'>

type Deps = {
  open: () => Promise<Source>
  getProgress: () => Promise<Progress>
  on: {
    snapshot: (p: Progress) => void
    progress: (d: ProgressEvent) => void
    log: (d: LogEvent) => void
    /** 검사 실패 이벤트 → 오류 팝업 */
    error: (d: StopEvent) => void
    /** 검사가 끝났다 (done 이벤트 · 스냅샷 status가 running이 아님) */
    leave: (status: string) => void
  }
}

const CLOSED = 2 // EventSource.CLOSED — node에는 EventSource 전역이 없어 숫자로
const RETRY_MS = 3000

/** 연결을 열고 이벤트를 넘긴다. 돌려준 함수를 부르면 닫고 타이머를 지운다 */
export function watchRun({ open, getProgress, on }: Deps): () => void {
  let src: Source | null = null
  let stopped = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let at = '' // 지금 단계 · 항목 ("perf/perf-2") — 바뀌면 트리를 다시 받는다
  let seq = 0 // GET /progress 순번 — 늦게 온 옛 스냅샷이 새 트리 · 로그를 덮지 않게

  const end = (status: string) => {
    if (stopped) return
    stop()
    on.leave(status)
  }

  /** GET /progress. running이면 스냅샷을 넘기고 true. 실패 · 더 새 요청이 있으면 false (문구는 래퍼가 띄웠다) */
  const refresh = async () => {
    const n = ++seq
    try {
      const p = await getProgress()
      if (stopped) return false
      if (p.status !== 'running') {
        end(p.status)
        return false
      }
      if (n !== seq) return false
      const step = p.steps.find((s) => s.state === 'running')
      at = `${step?.step ?? ''}/${step?.items.find((i) => i.state === 'running')?.key ?? ''}`
      on.snapshot(p)
      return true
    } catch {
      return false
    }
  }

  // CLOSED면 브라우저가 다시 붙지 않는다 → 3초마다 GET /progress, running이면 새로 연다
  const retryLater = () => {
    if (stopped || timer !== undefined) return
    timer = setTimeout(async () => {
      timer = undefined
      if (await refresh()) connect()
      else if (!stopped) retryLater()
    }, RETRY_MS)
  }

  const data = (e: Event) => JSON.parse((e as MessageEvent).data)

  const connect = async () => {
    const s = await open()
    if (stopped) return s.close()
    src = s
    s.addEventListener('open', () => {
      if (src === s) refresh()
    })
    s.addEventListener('progress', (e) => {
      if (src !== s) return
      const d: ProgressEvent = data(e)
      on.progress(d)
      const next = `${d.step}/${d.item ?? ''}`
      if (next !== at) {
        at = next
        refresh()
      }
    })
    s.addEventListener('log', (e) => {
      if (src === s) on.log(data(e))
    })
    s.addEventListener('error', (e) => {
      if (src !== s) return
      // data가 있으면 서버가 보낸 검사 실패, 없으면 EventSource 연결 오류
      if ((e as MessageEvent).data) return on.error(data(e))
      refresh()
      if (s.readyState === CLOSED) {
        s.close()
        src = null
        retryLater()
      }
    })
    s.addEventListener('done', (e) => {
      if (src === s) end(data(e).status)
    })
  }

  function stop() {
    stopped = true
    clearTimeout(timer)
    src?.close()
    src = null
  }

  connect()
  return stop
}
