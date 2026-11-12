import { useRef } from 'react'
import { finishPartial, resumeRun } from '../api/runs'
import Dialog from './Dialog'
import Button from './Button'
import s from './RunBusyDialog.module.css'

/** "나중에"로 닫은 회차 — 창을 새로 열기 전까지 홈에서 다시 묻지 않는다. 상단 칩 "검사 멈춤"(→ 5)에서는 늘 묻는다 */
export const later = new Set<string>()

/**
 * 이어서 검사할까요? (-228, F-STOP-05) — 딸깍이 다시 켜져 멈춘 회차(home.run.state interrupted). 홈 · 5가 같이 쓴다.
 * 이어서 검사 → POST /api/runs/resume 뒤 5 · 여기까지 결과 보기 → POST /finish-partial 뒤 5(정리가 끝나면 SSE done으로 6) · 나중에 → 닫기.
 * onPicked는 이미 5에 있을 때 스트림을 다시 붙이라고 부른다 (주소가 같아 화면이 다시 그려지지 않는다)
 */
export default function ResumeDialog({
  runId,
  at,
  onClose,
  onPicked,
}: {
  runId: string
  at: string | null
  onClose: () => void
  onPicked?: () => void
}) {
  const busy = useRef(false) // 연타에도 한 번
  const pick = (call: (id: string) => Promise<{ status: string }>) => async () => {
    if (busy.current) return
    busy.current = true
    later.delete(runId) // 이어 간 회차가 또 멈추면 홈이 다시 묻게 (!143 리뷰 4)
    try {
      const { status } = await call(runId)
      // 이미 정리가 끝났으면(partial) 바로 6, 아니면 5에서 진행 · done을 기다린다. 홈은 주소가 바뀌어 팝업째 사라진다
      const next = status === 'running' ? `#/runs/${runId}/progress` : `#/runs/${runId}`
      if (location.hash === next) onPicked?.()
      else location.hash = next
    } catch {
      busy.current = false // 래퍼가 문구를 띄웠다 (이미 검사 중 · 앱이 꺼져 있어요)
    }
  }
  return (
    <Dialog
      title="이어서 검사할까요?"
      onClose={() => {
        later.add(runId)
        onClose()
      }}
      actions={
        <>
          <Button onClick={pick(resumeRun)}>이어서 검사</Button>
          <Button variant="outline" onClick={pick(finishPartial)}>
            여기까지 결과 보기
          </Button>
          <Button
            variant="outline"
            onClick={() => {
              later.add(runId)
              onClose()
            }}
          >
            나중에
          </Button>
        </>
      }
    >
      <p className={s.body}>
        딸깍이 다시 켜져서 검사가 {at ? `${at}에서 ` : ''}멈췄어요. 끝난 항목은 그대로 두고 멈춘 곳부터 이어서 검사할 수 있어요. 여기까지 결과를
        보면 끝나지 않은 항목은 미검사로 남아요.
      </p>
    </Dialog>
  )
}
