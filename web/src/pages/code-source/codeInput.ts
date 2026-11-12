// 0-3 ④ 고른 방법 → POST /api/code-source 본문 · 실행 파일 후보 (F-CODE-01 · F-CODE-05). React 없는 순수 모듈이라 web/test/codeInput.test.ts가 돌린다
import type { CodeArtifact, CodeArtifacts, CodeSource, CodeSourceInput } from '../../api/codeSource'

export type Key = 'git' | 'jar' | 'zip' | 'local' | 'none'
/** path = 내 컴퓨터 폴더(직접 적음) · jar = 실행 파일 후보에서 고른 path(입력칸 없음, F-CODE-05) */
export type Fields = { repo: string; branch: string; token: string; path: string; jar: string; zip: File | null }

// openapi uploadCodeZip: 서버는 200MB를 넘으면 413, 크게 넘으면 응답 없이 연결을 끊는다 → 올리기 전에 본다
export const ZIP_MAX = 200 * 1024 * 1024

const orNull = (v: string) => v.trim() || null

/** 고른 방법의 요청 본문. 빈 칸이거나 본문이 없는 방법(ZIP 업로드)이면 null */
export function codeInput(sel: Key | null, f: Omit<Fields, 'zip'>): CodeSourceInput | null {
  if (sel === 'none') return { type: 'none' }
  if (sel === 'git') {
    const repo = f.repo.trim()
    if (!repo) return null
    return { type: 'github', ref: /^https?:\/\//.test(repo) ? repo : `https://${repo}`, branch: orNull(f.branch), token: orNull(f.token) }
  }
  if (sel === 'jar') return f.jar ? { type: 'jar', ref: f.jar } : null
  if (sel === 'local') {
    const path = f.path.trim()
    return path ? { type: 'local', ref: path } : null
  }
  return null
}

/** "연결"을 누를 수 있나 */
export const canConnect = (sel: Key | null, f: Fields) => (sel === 'zip' ? !!f.zip : !!codeInput(sel, f))

/** ZIP 파일을 올리기 전에 막을 이유 */
export function zipProblem(file: { name: string; size: number }): string | null {
  if (!/\.zip$/i.test(file.name)) return 'ZIP 파일만 올릴 수 있어요'
  if (file.size > ZIP_MAX) return '200MB가 넘는 ZIP은 올릴 수 없어요'
  return null
}

/** 저장한 방식 → 처음 골라 둘 카드와 칸 (홈 "바꾸기"로 왔을 때) */
export function initialPick(last: Pick<CodeSource, 'type' | 'ref' | 'branch'> | null): { sel: Key | null; fields: Partial<Fields> } {
  if (!last) return { sel: null, fields: {} }
  if (last.type === 'github') return { sel: 'git', fields: { repo: last.ref ?? '', branch: last.branch ?? '' } }
  if (last.type === 'jar') return { sel: 'jar', fields: { jar: last.ref ?? '' } }
  if (last.type === 'local') return { sel: 'local', fields: { path: last.ref ?? '' } }
  return { sel: last.type, fields: {} }
}

const baseName = (path: string) => path.split(/[\\/]/).pop() ?? path

/** "order-api.jar · 실행 중 · 46MB · 오늘 09:40". today = 'YYYY-MM-DD' (서버 시각 그대로 비교) */
export function artifactLine(a: CodeArtifact, today: string) {
  const at = a.modifiedAt && /^(\d{4}-(\d{2})-(\d{2}))T(\d{2}:\d{2})/.exec(a.modifiedAt)
  return [
    baseName(a.path),
    a.from === 'running' ? '실행 중' : '작업 폴더',
    a.sizeBytes != null && `${Math.round(a.sizeBytes / 1024 / 1024)}MB`,
    at && (at[1] === today ? `오늘 ${at[4]}` : `${at[2]}/${at[3]} ${at[4]}`),
  ]
    .filter(Boolean)
    .join(' · ')
}

/** 기본으로 고를 파일 — 저장한 파일 → 실행 중인 파일 → 고를 수 있는 첫 파일 */
export function defaultArtifact(list: CodeArtifact[], savedPath: string | null): string | null {
  const ok = list.filter((a) => a.readable)
  return (ok.find((a) => a.path === savedPath) ?? ok.find((a) => a.from === 'running') ?? ok[0])?.path ?? null
}

/** 목록 아래 안내 (screens.md 0-3 ④) */
export function artifactNote(res: CodeArtifacts): string | null {
  if (res.workDir === null) return '스타터킷을 새 버전으로 올리면 후보를 찾아요'
  return res.artifacts.length ? null : '찾은 JAR · WAR가 없어요'
}
