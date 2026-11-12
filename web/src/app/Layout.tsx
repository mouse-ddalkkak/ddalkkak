import type { ReactNode } from 'react'
import TopBar from '../components/TopBar'
import BottomBar from '../components/BottomBar'
import PixelBand from '../components/PixelBand'
import StarField from '../components/StarField'
import RocketFleet from '../components/RocketFleet'
import HamsterWalk from '../components/HamsterWalk'
import HamsterScene from '../components/HamsterScene'
import StepChips from '../components/StepChips'
import OverlayScrollbar from '../components/OverlayScrollbar'
import s from './Layout.module.css'

/** 화면 ID (screens.md §1). 2-a · 2-b는 없앴다 (-224) */
export type ScreenId = '0-0' | '0-1' | '0-2' | '0-3' | '1' | '3' | '4' | '5' | '6' | '7' | '8'

// 흐름 화면 상단 단계 표시줄 (Pixel Flow 공용)
// 코드 연결은 0-3 ④로 옮겨 흐름에서 뺐다 (-245)
const FLOW = ['검사 범위 분석', '검사 범위 선택', '검사 진행', '완료']
const FLOW_AT: Partial<Record<ScreenId, number>> = { '3': 0, '4': 1, '5': 2, '6': 3 }
const NUMS = ['①', '②', '③', '④']

const LANDING: (ScreenId | null)[] = ['0-0', '0-1', '0-2']

export default function Layout({ screen, children }: { screen: ScreenId | null; children: ReactNode }) {
  const intro = screen === '0-0'
  const flowAt = screen ? FLOW_AT[screen] : undefined
  // 스크롤바는 .root(zoom .8) 밖에 둬서 크기가 줄지 않게 한다
  return (
    <>
      <OverlayScrollbar />
      <div className={s.root}>
        {LANDING.includes(screen) ? <StarField kind="landing" /> : <StarField kind="sparkles" />}
        {!intro && <RocketFleet />}
        <TopBar screen={screen} />
        <PixelBand side="top" />
        <main className={s.main} style={{ paddingTop: screen === '1' ? 40 : 60 }}>
          {flowAt !== undefined && (
            <StepChips
              className={s.flow}
              steps={FLOW.map((n, i) =>
                i < flowAt ? { label: n, state: 'done' } : { label: `${NUMS[i]} ${n}`, state: i === flowAt ? 'on' : 'todo' },
              )}
            />
          )}
          {children}
        </main>
        <PixelBand side="bottom">{intro ? <HamsterScene /> : <HamsterWalk />}</PixelBand>
        <BottomBar />
      </div>
    </>
  )
}
