import { useState } from 'react'
import { useApp } from '../app/AppContext'
import { getCodeSource } from '../api/codeSource'
import { useLoad } from '../hooks/useLoad'
import { useScan } from '../hooks/useScan'
import { codeLine, scanButton } from '../pages/home/homeState'
import Dialog from './Dialog'
import Button from './Button'
import RunBusyDialog from './RunBusyDialog'
import s from './RunBusyDialog.module.css'

/**
 * 상단 '검사 전' 칩을 누르면 (-245) — 지금 서비스 · 코드 연결을 보여 주고, 그 상태로 갈 곳 버튼 하나.
 * 저장한 코드 연결이 있으면 "딸깍 검사 시작"(바로 준비), 없으면 0-3 ④, 서비스가 없으면 0-3
 */
export default function ScanDialog({ onClose }: { onClose: () => void }) {
  const { home } = useApp()
  const code = useLoad(getCodeSource, [])
  const { scan, busy, closeBusy } = useScan()
  const service = home?.service ?? null
  const last = code.data?.last ?? null
  const { target, label } = scanButton(service, code.data && last !== null, code.failed)
  const [going, setGoing] = useState(false)
  // 상단 바에 남는 팝업이라 옮길 때 닫는다 (!133 리뷰). 검사 중이면 닫지 않고 "이미 검사 중이에요"로 바꾼다
  const go = async () => {
    if (target === 'prepare') {
      // 도는 동안 버튼을 끈다 — 다시 누르면 둘째 scan()이 바로 undefined라 팝업이 닫혀 busy 팝업이 안 떴다 (!145 리뷰)
      setGoing(true)
      if ((await scan()) !== 'busy') onClose()
      return
    }
    onClose()
    if (target) location.hash = target
  }

  if (busy)
    return (
      <RunBusyDialog
        onClose={() => {
          closeBusy()
          onClose()
        }}
      />
    )
  return (
    <Dialog
      title="검사 시작"
      onClose={onClose}
      actions={
        <>
          <Button onClick={go} disabled={!target || going}>
            {label}
          </Button>
          <Button variant="outline" onClick={onClose}>
            닫기
          </Button>
        </>
      }
    >
      <p className={s.body}>
        서비스 · {service ? `${service.name} · ${service.connected ? '연결됨' : '연결 끊김'}` : '연결된 서비스 없음'}
        <br />
        코드 · {code.data ? codeLine(last) : code.failed ? '확인 못 함' : '확인 중…'}
      </p>
    </Dialog>
  )
}
