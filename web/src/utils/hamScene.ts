// 0-0 햄스터 연출 타임라인 (Pixel Flow runHamScene). 좌표 계산은 components/HamsterScene.tsx

/** 타임라인 끝(초). 여기서 멈춰 햄스터가 버튼 위에 머문다 */
export const SCENE_END = 10.6

/** 실제 경과 rt초 → 타임라인 초. 걷기(실제 wd초)를 타임라인 0~2.4초에 맞춘다 */
export function sceneTime(rt: number, wd: number): number {
  return Math.min(rt < wd ? (rt / wd) * 2.4 : rt - wd + 2.4, SCENE_END)
}

/** 버튼이 눌리는 목표 깊이(px). 첫 착지 · 둘째 착지 · 콩 */
export function pressDepth(t: number): number {
  return (t > 8.1 ? 7 : 0) + (t > 9.3 ? 10 : 0) + (t > 10.25 ? 8 : 0)
}
