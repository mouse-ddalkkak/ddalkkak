// 1 홈 서버 상태 (F-CONN-07) — web/test/homeState.test.ts
import type { Home } from '../../api/home'

/** 게이지 3개는 늘 남긴다. 끊기면(health null) 값 없이 회색 */
export function homeGauges(health: Home['health']): { k: string; v: number | null }[] {
  return [
    { k: 'CPU', v: health && Math.round(health.cpu * 100) },
    { k: '메모리', v: health && Math.round((health.mem.usedMb / health.mem.totalMb) * 100) },
    { k: '디스크', v: health && Math.round((health.disk.usedGb / health.disk.totalGb) * 100) },
  ]
}

/** LED 옆 문구. 연결된 적 없는 서비스는 앱이 꺼졌는지 몰라 "연결 끊김"만 */
export function serverState(service: Home['service']) {
  if (service?.connected) return { ok: true, text: '정상' }
  return { ok: false, text: service ? '연결 끊김 · 앱이 꺼져 있어요' : '연결 끊김' }
}
