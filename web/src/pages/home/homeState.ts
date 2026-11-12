// 1 홈 서버 상태 (F-CONN-07) — web/test/homeState.test.ts
import type { Home } from '../../api/home'
import type { ConnectRequest } from '../../api/services'
import type { CodeSource } from '../../api/codeSource'

/** 게이지 3개는 늘 남긴다. 끊기면(health null) 값 없이 회색 */
export function homeGauges(health: Home['health']): { k: string; v: number | null }[] {
  return [
    { k: 'CPU', v: health && Math.round(health.cpu * 100) },
    { k: '메모리', v: health && Math.round((health.mem.usedMb / health.mem.totalMb) * 100) },
    { k: '디스크', v: health && Math.round((health.disk.usedGb / health.disk.totalGb) * 100) },
  ]
}

/** 끊긴 서비스를 다시 허용할 연결 요청이 와 있나. 서버의 다시 허용처럼 이름 · host · port가 다 같아야 한다 — 아니면 허용이 409 (-211) */
export function waitingReapprove(service: Home['service'], requests: Pick<ConnectRequest, 'appName' | 'host' | 'port'>[]) {
  return (
    !!service &&
    !service.connected &&
    requests.some((r) => r.appName === service.name && r.host === service.host && r.port === service.port)
  )
}

/**
 * "딸깍 검사"가 갈 곳 (F-HOME-01 · F-CODE-01). 끊겼는데 같은 앱의 연결 요청이 와 있으면 토큰을 잃은 앱의 다시 허용이라 0-3 ② (-211 · -222).
 * 코드 연결 화면을 거치지 않는다 — 저장한 방식이 없으면 0-3 ④, 있으면 'prepare'(바로 POST /api/runs/prepare, -224)
 */
export function scanTarget(service: Home['service'], waiting: boolean, saved: boolean) {
  if (!service) return '#/onboarding'
  if (!service.connected && waiting) return '#/onboarding/2'
  return saved ? 'prepare' : '#/onboarding/4'
}

/**
 * 검사 전 칩 "검사 시작" 팝업 버튼 (-245). saved = 저장한 코드 연결이 있나(아직 못 받았으면 undefined), failed = 못 받음.
 * 받는 동안은 "확인 중…"(꺼짐). 못 받았으면 저장했다고 보고 "딸깍 검사 시작"으로 살린다 — useScan이 홈 · 코드 연결을 새로 받아
 * 갈 곳을 다시 정한다 (!145 리뷰). 서비스가 없으면 코드 연결과 상관없이 0-3
 */
export function scanButton(service: Home['service'], saved: boolean | undefined, failed: boolean) {
  if (service && saved === undefined && !failed) return { target: null, label: '확인 중…' }
  const target = scanTarget(service, false, saved ?? true)
  const label = target === 'prepare' ? '딸깍 검사 시작' : target === '#/onboarding/4' ? '소스코드 연결하러 가기' : '서비스 연결하러 가기'
  return { target, label }
}

const HOSTS: Record<string, string> = { 'github.com': 'GitHub', 'gitlab.com': 'GitLab' }

/** 저장소 주소 → "GitHub a/b" · "GitLab a/b", 그 밖의 호스트는 "Git 호스트/a/b" — type github는 GitLab도 받는다 (F-CODE-01, !145 리뷰) */
function repoLabel(ref: string | null) {
  const url = (ref ?? '').replace(/^https?:\/\/(www\.)?/, '')
  const [host, ...path] = url.split('/')
  return HOSTS[host] ? `${HOSTS[host]} ${path.join('/')}` : `Git ${url}`.trim()
}

/** 홈 코드 줄 "코드 · …" (screens.md 1). 저장한 방식이 없으면 "코드 연결 안 함" */
export function codeLine(last: Pick<CodeSource, 'type' | 'ref' | 'branch'> | null) {
  if (!last) return '코드 연결 안 함'
  if (last.type === 'none') return '코드 없이 검사'
  if (last.type === 'github') return [repoLabel(last.ref), last.branch].filter(Boolean).join(' · ')
  if (last.type === 'jar') return `실행 파일 ${(last.ref ?? '').split(/[\\/]/).pop()}`
  return `${last.type === 'zip' ? 'ZIP' : '내 컴퓨터'} ${last.ref ?? ''}`.trim()
}

// node --test가 이 파일을 그대로 돌려서 utils/format의 gb를 import하지 않는다 (확장자 없는 import를 못 찾음)
const gb = (mb: number) => (Math.round((mb / 1024) * 10) / 10).toString()

/** 게이지 아래 줄 — 무엇을 잰 값인지 · 단위 (스타터킷이 시스템 전체 CPU · 메모리, 앱 작업 폴더가 있는 디스크를 잰다). 끊겨도 설명은 남긴다 */
export function gaugeSubs(health: Home['health']) {
  if (!health) return ['시스템 전체', '시스템 전체', '앱 작업 폴더 디스크']
  return [
    '시스템 전체',
    `${gb(health.mem.usedMb)} / ${gb(health.mem.totalMb)} GB · 시스템 전체`,
    `${health.disk.usedGb} / ${health.disk.totalGb} GB · 앱 작업 폴더 디스크`,
  ]
}

/** 지난 검사 위험 · 주의 — 서버가 영역마다 한 줄씩 줄바꿈으로 잇는다 (-214) */
export const lines = (text: string | null) => (text ? text.split('\n') : [])

/**
 * 상단 검사 상태 칩 (-214). 딸깍 재시작으로 멈춘 회차(interrupted)는 검사 멈춤 → 그 회차 5가 이어서 검사할지 묻는다 (-228).
 * 검사 전은 링크가 아니다(href null) — 누르면 지금 상태와 갈 곳을 보여 주는 "검사 시작" 팝업 (-245)
 */
export function runChip(service: Home['service'], run: Home['run'], lastRunId: string | null = null, hash = '') {
  if (run?.state === 'running' && run.runId) return { label: '검사 중', href: `#/runs/${run.runId}/progress`, state: 'running' }
  if (run?.state === 'interrupted' && run.runId) return { label: '검사 멈춤', href: `#/runs/${run.runId}/progress`, state: 'interrupted' }
  if (run?.state === 'done' && run.runId) return { label: '검사 완료', href: `#/runs/${run.runId}`, state: 'done' }
  // 결과(6 · 7)를 열면 서버는 바로 none을 주지만, 그 결과를 보고 있는 동안은 검사 완료로 둔다 — 다른 페이지로 옮기면 검사 전
  const viewing = lastRunId && (hash === `#/runs/${lastRunId}` || hash.startsWith(`#/runs/${lastRunId}/issues/`))
  if (viewing) return { label: '검사 완료', href: `#/runs/${lastRunId}`, state: 'done' }
  return { label: '검사 전', href: null, state: 'none' }
}

/** LED 옆 문구. 연결된 적 없는 서비스는 앱이 꺼졌는지 몰라 "연결 끊김"만 */
export function serverState(service: Home['service']) {
  if (service?.connected) return { ok: true, text: '정상' }
  return { ok: false, text: service ? '연결 끊김 · 앱이 꺼져 있어요' : '연결 끊김' }
}
