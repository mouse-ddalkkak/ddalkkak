import { useRef, useState } from 'react'
import type { Home } from '../../api/home'
import { disconnectService } from '../../api/services'
import { ApiError } from '../../api/client'
import Dialog from '../../components/Dialog'
import Button from '../../components/Button'
import ResumeDialog, { later } from '../../components/ResumeDialog'
import { needsConfirm } from '../onboarding/onboardingStep'
import { useApp } from '../../app/AppContext'
import s from './HomeNotice.module.css'

/**
 * 홈 안내 자리 (-214). 연결 해제 안내(-223 · `service.disconnect`)와 이어서 검사 확인(-228 · `run.state: interrupted`)이 같이 쓴다.
 * 해제 안내는 이 자리에서 여는 모달이다(screens.md 1 홈). 둘이 같이 오면 겹쳐 띄우지 않고 해제 안내를 먼저, 닫으면 이어서 검사 확인을 띄운다.
 */
export default function HomeNotice({ home }: { home: Home }) {
  // 해제 안내 "나중에"는 이 화면에서만 닫는다 — 다음 폴링이 다시 열지 않게
  const [skipped, setSkipped] = useState(false)
  // 이어서 검사 확인을 닫으면 다시 그린다 — "나중에"는 later에 남아 다음 폴링이 다시 열지 않는다
  const [, setClosed] = useState(0)
  const [err, setErr] = useState<string | null>(null)
  const busy = useRef(false)
  const { setHome } = useApp()
  const svc = home.service
  const run = home.run
  if (!svc || !needsConfirm(svc) || skipped) {
    if (run?.state !== 'interrupted' || !run.runId || later.has(run.runId)) return null
    return <ResumeDialog runId={run.runId} at={run.interruptedAt?.name ?? null} onClose={() => setClosed((n) => n + 1)} />
  }

  const disconnect = async () => {
    if (busy.current) return
    busy.current = true
    try {
      await disconnectService(svc.id)
      // 상단 바 폴링(10초) 전에 홈으로 돌아와도 해제한 서비스로 이 안내를 다시 띄우지 않게 (!134 리뷰 2-2)
      setHome({ ...home, service: null })
      location.hash = '#/onboarding/2'
    } catch (e) {
      if (e instanceof ApiError && e.code === 'RUN_IN_PROGRESS') setErr('검사가 끝나거나 중단한 뒤 해제할 수 있어요')
    } finally {
      busy.current = false
    }
  }

  return (
    <Dialog
      title={`${svc.name}에 연결할 수 없어요`}
      onClose={() => setSkipped(true)}
      actions={
        <>
          <span className={s.grow} />
          <Button variant="outline" arrow={false} onClick={() => setSkipped(true)}>
            나중에
          </Button>
          <Button onClick={disconnect}>연결 해제</Button>
        </>
      }
    >
      <span className={s.text}>30초마다 확인했는데 3번 연속 닿지 않았어요. 연결을 해제하면 다른 서비스를 연결할 수 있어요</span>
      {err && <span className={s.warn}>{err}</span>}
    </Dialog>
  )
}
