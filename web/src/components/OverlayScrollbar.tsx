import { useEffect, useRef } from 'react'
import { thumbBox } from '../utils/scrollbar'
import s from './OverlayScrollbar.module.css'

// 화면 위에 겹치는 얇은 스크롤바. 기본 스크롤바는 base.css에서 숨겨 페이지마다 레이아웃이 좌우로 밀리지 않게 한다
// 스크롤 · 막대에 마우스를 올리면 보이고, 마지막 동작 1초 뒤 사라진다 (드래그 · 올려 둔 동안은 계속 보임)
export default function OverlayScrollbar() {
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const thumb = ref.current!
    const root = document.documentElement
    let hideTimer = 0
    let hovering = false
    let drag: { y: number; scroll: number } | null = null
    let thumbH = 0

    const layout = () => {
      const box = thumbBox(innerHeight, root.scrollHeight, scrollY)
      thumb.style.display = box ? '' : 'none'
      if (!box) return
      thumbH = box.height
      thumb.style.height = `${box.height}px`
      thumb.style.transform = `translateY(${box.top}px)`
    }
    const show = () => {
      thumb.style.opacity = '1'
      clearTimeout(hideTimer)
      hideTimer = window.setTimeout(() => {
        if (!drag && !hovering) thumb.style.opacity = '0'
      }, 1000)
    }
    const onScroll = () => {
      layout()
      show()
    }
    const onEnter = () => {
      hovering = true
      show()
    }
    const onLeave = () => {
      hovering = false
      show()
    }
    const onDown = (e: PointerEvent) => {
      e.preventDefault()
      thumb.setPointerCapture(e.pointerId)
      drag = { y: e.clientY, scroll: scrollY }
      show()
    }
    const onMove = (e: PointerEvent) => {
      if (!drag) return
      const range = innerHeight - thumbH
      if (range > 0) scrollTo(0, drag.scroll + ((e.clientY - drag.y) * (root.scrollHeight - innerHeight)) / range)
    }
    const onUp = () => {
      drag = null
      show()
    }

    const observer = new ResizeObserver(layout)
    observer.observe(document.body)
    addEventListener('scroll', onScroll, { passive: true })
    addEventListener('resize', layout)
    thumb.addEventListener('pointerenter', onEnter)
    thumb.addEventListener('pointerleave', onLeave)
    thumb.addEventListener('pointerdown', onDown)
    thumb.addEventListener('pointermove', onMove)
    thumb.addEventListener('pointerup', onUp)
    thumb.addEventListener('pointercancel', onUp)
    layout()
    return () => {
      observer.disconnect()
      removeEventListener('scroll', onScroll)
      removeEventListener('resize', layout)
      thumb.removeEventListener('pointerenter', onEnter)
      thumb.removeEventListener('pointerleave', onLeave)
      thumb.removeEventListener('pointerdown', onDown)
      thumb.removeEventListener('pointermove', onMove)
      thumb.removeEventListener('pointerup', onUp)
      thumb.removeEventListener('pointercancel', onUp)
      clearTimeout(hideTimer)
    }
  }, [])

  return (
    <div className={s.track} aria-hidden>
      <div ref={ref} className={s.thumb} />
    </div>
  )
}
