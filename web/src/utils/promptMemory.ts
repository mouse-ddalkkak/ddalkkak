// 6 담은 문제 · 묶음 프롬프트 · 7 프롬프트를 화면을 오가도 유지 (-216) — 순수 모듈, web/test/promptMemory.test.ts
// 화면은 sessionStorage를 넘긴다: 새로고침해도 남고 탭을 닫으면 사라진다. 읽기 · 쓰기 실패(막힌 저장소 · 깨진 값)는 빈 값으로 본다

type Store = { getItem: (key: string) => string | null; setItem: (key: string, value: string) => void }
export type ResultMemory = { sel: string[]; prompt: { ids: string[]; text: string } | null }

const KEY = 'ddalkkak.prompt.'

export function promptMemory(store: Store | null) {
  const read = (key: string): unknown => {
    try {
      const v = store?.getItem(key)
      return v == null ? null : JSON.parse(v)
    } catch {
      return null
    }
  }
  const write = (key: string, value: unknown) => {
    try {
      store?.setItem(key, JSON.stringify(value))
    } catch {
      // 저장하지 못하면 화면 안에서만 유지된다
    }
  }
  return {
    result: (runId: string): ResultMemory => {
      const v = read(KEY + runId) as Partial<ResultMemory> | null
      return { sel: Array.isArray(v?.sel) ? v.sel : [], prompt: v?.prompt ?? null }
    },
    saveResult: (runId: string, v: ResultMemory) => write(KEY + runId, v),
    issue: (runId: string, issueId: string): string | null => {
      const v = read(`${KEY}${runId}.${issueId}`)
      return typeof v === 'string' ? v : null
    },
    saveIssue: (runId: string, issueId: string, text: string) => write(`${KEY}${runId}.${issueId}`, text),
  }
}

/** 화면이 쓰는 것 — sessionStorage에 닿지 못하면(막힌 브라우저 설정) null */
export const sessionMemory = () => {
  try {
    return promptMemory(window.sessionStorage)
  } catch {
    return promptMemory(null)
  }
}
