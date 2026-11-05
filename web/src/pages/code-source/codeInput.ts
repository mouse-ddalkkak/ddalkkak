// 2-a · 2-b 고른 방법 → POST /api/code-source 본문 (F-CODE-01). React 없는 순수 모듈이라 web/test/codeInput.test.ts가 돌린다
import type { CodeSource, CodeSourceInput } from '../../api/codeSource'

export type Key = 'prev' | 'git' | 'jar' | 'zip' | 'local' | 'none'
export type Fields = { repo: string; branch: string; token: string; path: string; zip: File | null }

// openapi uploadCodeZip: 서버는 200MB를 넘으면 413, 크게 넘으면 응답 없이 연결을 끊는다 → 올리기 전에 본다
export const ZIP_MAX = 200 * 1024 * 1024

const orNull = (v: string) => v.trim() || null

/** 고른 방법의 요청 본문. 아직 못 보내면(빈 칸 · ZIP · 다시 못 쓰는 이전 설정) null → "다음" 비활성 */
export function codeInput(sel: Key | null, f: Omit<Fields, 'zip'>, last: Pick<CodeSource, 'type' | 'ref' | 'branch'> | null): CodeSourceInput | null {
  if (sel === 'none') return { type: 'none' }
  // ZIP은 이번 검사에만 (F-CODE-02) — 이전 설정이 ZIP이면 다시 쓸 수 없다
  if (sel === 'prev') return last && last.type !== 'zip' && last.type !== 'none' ? { type: last.type, ref: last.ref, branch: last.branch, token: null } : null
  if (sel === 'git') {
    const repo = f.repo.trim()
    if (!repo) return null
    return { type: 'github', ref: /^https?:\/\//.test(repo) ? repo : `https://${repo}`, branch: orNull(f.branch), token: orNull(f.token) }
  }
  if (sel === 'jar' || sel === 'local') {
    const path = f.path.trim()
    return path ? { type: sel, ref: path } : null
  }
  return null
}

/** ZIP 파일을 올리기 전에 막을 이유 */
export function zipProblem(file: { name: string; size: number }): string | null {
  if (!/\.zip$/i.test(file.name)) return 'ZIP 파일만 올릴 수 있어요'
  if (file.size > ZIP_MAX) return '200MB가 넘는 ZIP은 올릴 수 없어요'
  return null
}
