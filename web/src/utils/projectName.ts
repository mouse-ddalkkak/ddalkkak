// 8 · 6 프로젝트 이름 — 서버(PATCH /api/runs/{id})가 거절하는 값을 미리 거른다. 앞뒤 공백을 뗀 뒤 1~100자
export const PROJECT_NAME_MAX = 100

/** 보낼 값(공백을 뗀 이름). 비었거나 100자를 넘으면 null */
export function cleanProjectName(raw: string): string | null {
  const name = raw.trim()
  return name.length >= 1 && name.length <= PROJECT_NAME_MAX ? name : null
}
