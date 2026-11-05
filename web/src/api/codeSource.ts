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
}
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
export const startCodeSource = (input: CodeSourceInput) =>
  api<{ jobId: string | null }>('POST', '/api/code-source', { body: input })
export const getCodeJob = (jobId: string) => api<CodeJob>('GET', `/api/code-source/jobs/${encodeURIComponent(jobId)}`)
/** ZIP 업로드 (multipart file) — 200MB는 화면이 올리기 전에 본다 (codeInput.zipProblem) */
export const uploadCodeZip = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return api<{ jobId: string }>('POST', '/api/code-source/zip', { form })
}
