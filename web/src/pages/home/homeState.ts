// 1 홈 서버 상태 (F-CONN-07) — web/test/homeState.test.ts
import type { Home } from '../../api/home'
import type { ConnectRequest } from '../../api/services'

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

/** "딸깍 검사"가 갈 곳 (F-HOME-01). 끊겼는데 같은 앱의 연결 요청이 와 있으면 토큰을 잃은 앱의 다시 허용이라 0-3 (-211) */
export function scanTarget(service: Home['service'], waiting: boolean) {
  return !service || (!service.connected && waiting) ? '#/onboarding' : '#/code'
}

/** LED 옆 문구. 연결된 적 없는 서비스는 앱이 꺼졌는지 몰라 "연결 끊김"만 */
export function serverState(service: Home['service']) {
  if (service?.connected) return { ok: true, text: '정상' }
  return { ok: false, text: service ? '연결 끊김 · 앱이 꺼져 있어요' : '연결 끊김' }
}
