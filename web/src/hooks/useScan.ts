import { useRef, useState } from 'react'
import { useApp } from '../app/AppContext'
import { getHome } from '../api/home'
import { getCodeSource } from '../api/codeSource'
import { startPrepare } from '../api/runs'
import { ApiError } from '../api/client'
import { scanTarget } from '../pages/home/homeState'

/**
 * 새 검사 시작 하나 — 홈 딸깍 검사 · 상단 탭 "검사" · 6 · 8 재검사 · 검사 전 칩 팝업 (F-HOME-01 · F-CODE-01, -245).
 * 코드 연결 화면을 거치지 않는다: 저장한 방식이 있으면 바로 준비(3), 없으면 0-3 ④. 검사 중이면 busy → 화면이 RunBusyDialog (-214)
 */
export function useScan() {
  const { setHome } = useApp()
  const [busy, setBusy] = useState(false)
  const going = useRef(false) // 연타에도 회차는 하나

  /** 검사 중이라 못 시작했으면 'busy' */
  const scan = async (waiting = false): Promise<'busy' | undefined> => {
    if (going.current) return
    going.current = true
    try {
      // 늘 새로 받는다 — 상단 바는 0-3에서 홈을 받지 않아, ② 연결 · ④ 코드 연결 직후 Context 값은 연결 전일 수 있다
      const h = await getHome()
      setHome(h) // busy 팝업 · 칩이 이 값으로 runId를 읽는다 — 폴링 사이에 시작된 검사도 "진행 화면 보기"가 뜬다 (!145 리뷰)
      if (h.run?.state === 'running') {
        setBusy(true)
        return 'busy'
      }
      const saved = h.service ? (await getCodeSource()).last !== null : false
      const target = scanTarget(h.service, waiting, saved)
      if (target !== 'prepare') {
        location.hash = target
        return
      }
      const { runId } = await startPrepare()
      location.hash = `#/prepare/${encodeURIComponent(runId)}`
    } catch (e) {
      if (e instanceof ApiError && e.code === 'RUN_IN_PROGRESS') {
        setBusy(true)
        return 'busy'
      }
      // 그 밖은 래퍼가 띄웠다
    } finally {
      going.current = false
    }
  }
  return { scan, busy, closeBusy: () => setBusy(false) }
}
