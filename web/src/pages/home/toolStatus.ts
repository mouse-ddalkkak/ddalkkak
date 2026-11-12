// 1 홈 도구 상태 팝업 한 줄씩 (F-HOME-01 · openapi getTools, -229). React 없는 순수 모듈 — web/test/toolStatus.test.ts
import type { Tool } from '../../api/home'

// 딸깍이 구성하는 도구 — 응답 순서와 상관없이 늘 이 세 줄. 응답에 없으면 "구성 안 됨"으로 둔다
const TOOLS: { key: Tool['name']; name: string }[] = [
  { key: 'k6', name: 'k6' },
  { key: 'zap', name: 'ZAP' },
  { key: 'semgrep', name: 'Semgrep' },
]
const BADGE: Record<Tool['state'], string> = {
  docker_unavailable: 'Docker 꺼짐',
  not_installed: '미설치',
  not_runnable: '실행 불가',
  recent_error: '최근 오류',
  ok: '정상',
}

export type ToolRow = {
  name: string
  image: string | null
  badge: string
  /** 정상(초록)으로 보여도 되나. 정상이 아닌 도구를 정상처럼 보이지 않게 (screens.md 1) */
  ok: boolean
  installed: string
  runnable: string
  error: { stage: '받기' | '실행'; reason: string; cause: string; href: string; at: string } | null
  fix: string | null
}

/** tools null = 응답을 못 받음 */
export function toolRows(tools: Tool[] | null): ToolRow[] {
  return TOOLS.map(({ key, name }) => {
    const t = tools?.find((x) => x.name === key)
    if (!t) {
      const badge = tools ? '구성 안 됨' : '확인 못 함'
      return { name, image: null, badge, ok: false, installed: '확인 못 함', runnable: '확인 못 함', error: null, fix: tools ? '딸깍이 이 도구를 구성하지 않았어요' : null }
    }
    const docker = t.state !== 'docker_unavailable'
    return {
      name,
      image: t.image,
      badge: BADGE[t.state],
      ok: t.state === 'ok',
      installed: !docker ? '확인 못 함' : t.installed ? '설치됨' : '미설치',
      runnable: !docker || !t.installed ? '확인 못 함' : t.runnable ? '실행 가능' : '실행 불가',
      error: t.lastError && {
        stage: t.lastError.stage === 'pull' ? '받기' : '실행',
        reason: t.lastError.reason,
        cause: t.lastError.cause,
        href: `#/runs/${encodeURIComponent(t.lastError.runId)}`,
        at: t.lastError.at,
      },
      fix: t.fix,
    }
  })
}
