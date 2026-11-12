import { api } from './client'

export type CodeSource = {
  type: 'github' | 'jar' | 'zip' | 'local' | 'none'
  ref: string | null
  branch: string | null
  lastCommit: string | null
  lastCommitAt: string | null
  lastFetchedAt: string | null
  sameVersion: boolean | null
  decompiled: boolean
  /** ZIP · jar · war 파일 sha256 앞 12자 (-224) */
  fileHash: string | null
}
/** 0-3 ④ 실행 파일 후보 (F-CODE-05) */
export type CodeArtifact = {
  path: string
  kind: 'jar' | 'war'
  from: 'running' | 'workDir'
  readable: boolean
  reason: string | null
  sizeBytes: number | null
  modifiedAt: string | null
}
export type CodeArtifacts = { workDir: string | null; artifacts: CodeArtifact[] }
export type CodeSourceInput = {
  type: 'github' | 'jar' | 'local' | 'none'
  ref?: string | null
  branch?: string | null
  token?: string | null
}
export type CodeJob = {
  step: string
  done: boolean
  commit: string | null
  runningCommit: string | null
  sameVersion: boolean | null
  decompiled: boolean
  error: { code: string; message: string } | null
}

export const getCodeSource = () => api<{ last: CodeSource | null }>('GET', '/api/code-source')
/** 409 SERVICE_OFFLINE(연결된 서비스 없음)은 화면이 후보 자리에 안내한다 */
export const getCodeArtifacts = () => api<CodeArtifacts>('GET', '/api/code-source/artifacts', { handle: ['SERVICE_OFFLINE'] })
export const startCodeSource = (input: CodeSourceInput) =>
  api<{ jobId: string | null }>('POST', '/api/code-source', { body: input })
export const getCodeJob = (jobId: string) => api<CodeJob>('GET', `/api/code-source/jobs/${encodeURIComponent(jobId)}`)
/** ZIP 업로드 (multipart file) — 200MB는 화면이 올리기 전에 본다 (codeInput.zipProblem) */
export const uploadCodeZip = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return api<{ jobId: string }>('POST', '/api/code-source/zip', { form })
}
