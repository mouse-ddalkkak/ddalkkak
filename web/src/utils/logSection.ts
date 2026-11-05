/**
 * 도구 원문 로그(GET /api/runs/{id}/logs, tool 없이 전부)에서 시나리오 하나의 구간만 — 7 관측 근거 (F-RES-06)
 * 머리줄은 openapi 약속대로 도구마다 `### {tool}`, 시나리오마다 `== {itemKey} …`(Semgrep은 한 번에 돌려 `== code-1,code-2 · exit=0`처럼 쉼표로 묶는다). 영역 → 도구(sec는 zap · auth 둘)를 화면이 몰라도 되게 전부 받아 자른다
 */
export function logSection(text: string, itemKey: string): string[] {
  const out: string[] = []
  let on = false
  for (const line of text.split('\n')) {
    if (line.startsWith('== ') || line.startsWith('### ')) {
      on = line.startsWith('== ') && line.slice(3).split(' ')[0].split(',').includes(itemKey)
      // 다시 시도하면 원본 로그에 같은 머리줄이 또 붙는다 — 마지막 시도만. 관리자 점검(AuthzCheck.addAdmin)은 ZAP 시도 뒤에 같은 키로 붙으니 비우지 않는다
      if (on && !line.endsWith(' · 권한 점검')) out.length = 0
    }
    if (on && line) out.push(line)
  }
  return out
}
