import { useApp } from '../app/AppContext'
import Dialog from './Dialog'
import Button from './Button'
import s from './RunBusyDialog.module.css'

/**
 * 검사 중에 새 검사를 시작하려 할 때 (홈 딸깍 검사 · 409 RUN_IN_PROGRESS) — 하단 바 한 줄 대신 팝업으로 (-214).
 * 새 회차를 만들지 않고, 돌고 있는 회차의 5로 보낸다
 */
export default function RunBusyDialog({ onClose }: { onClose: () => void }) {
  const { home } = useApp()
  const runId = home?.run?.state === 'running' ? home.run.runId : null
  return (
    <Dialog
      title="이미 검사 중이에요"
      onClose={onClose}
      actions={
        <>
          {/* 상단 탭에서 연 팝업은 TopBar에 남아 있어 화면이 바뀌어도 닫히지 않는다 — 먼저 닫고 옮긴다 (!133 리뷰) */}
          {runId && (
            <Button
              onClick={() => {
                onClose()
                location.hash = `#/runs/${runId}/progress`
              }}
            >
              진행 화면 보기
            </Button>
          )}
          <Button variant="outline" onClick={onClose}>
            닫기
          </Button>
        </>
      }
    >
      <p className={s.body}>검사는 한 번에 하나씩 해요. 지금 검사가 끝나면 새로 시작할 수 있어요.</p>
    </Dialog>
  )
}
