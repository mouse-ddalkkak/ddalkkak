import { useEffect, useRef, useState } from 'react'
import { useApp } from '../app/AppContext'

// screens.md §3: 복사 버튼은 1.6초 동안 "복사됨", 하단 바에 "복사했어요"
const COPIED_MS = 1600

/** 복사 버튼. 버튼이 여럿이면 key로 어느 버튼이 "복사됨"인지 가린다. 복사하지 못하면 하단 바에 알리고 false */
export function useCopy<K extends string = 'copy'>() {
  const { showMessage } = useApp()
  const [copied, setCopied] = useState<K | null>(null)
  const timer = useRef<number | undefined>(undefined)
  useEffect(() => () => window.clearTimeout(timer.current), [])

  const copy = async (text: string, key = 'copy' as K) => {
    if (!(await writeClipboard(text))) {
      showMessage('복사하지 못했어요 · 직접 선택해 주세요')
      return false
    }
    setCopied(key)
    showMessage('복사했어요')
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setCopied(null), COPIED_MS)
    return true
  }
  return { copied, copy }
}

// http://<서버IP>:7777은 보안 컨텍스트가 아니라 navigator.clipboard가 없다 → 숨긴 textarea + execCommand('copy')
async function writeClipboard(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    // 아래 대체 복사
  }
  const back = document.activeElement as HTMLElement | null // 복사 버튼 포커스를 되돌린다 (Tab 이동이 처음으로 가지 않게)
  const ta = document.createElement('textarea')
  ta.value = text
  ta.setAttribute('readonly', '')
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.append(ta)
  ta.select()
  try {
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    ta.remove()
    back?.focus()
  }
}
