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

/** 화면 ID (screens.md §1). 2-a · 2-b는 같은 페이지라 '2' 하나로 다룬다 */
export type ScreenId = '0-0' | '0-1' | '0-2' | '0-3' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8'

// 하단 바 키 힌트 (functional-spec F-HOME-05 표). 키 동작은 화면마다 hooks/useKeys
const HINTS: Record<ScreenId, [string, string][]> = {
  '0-0': [['ENTER', '시작하기']],
  '0-1': [['ENTER', '로그인하고 시작하기']],
  '0-2': [['ENTER', '로그인']],
  '0-3': [['ESC', '나중에'], ['ENTER', '연결 허용 → 홈으로']],
  '1': [['↑ ↓', '이동'], ['ENTER', '선택']],
  '2': [['ESC', '취소 (홈)'], ['ENTER', '다음']],
  '3': [['ESC', '뒤로'], ['ENTER', '기본 범위로']],
  '4': [['ESC', '이전'], ['ENTER', '딸깍']],
  '5': [['ESC', '중단'], ['ENTER', '다시 시도 (오류 팝업)']],
  '6': [['ESC', '홈'], ['ENTER', '프롬프트 만들기']],
  '7': [['ESC', '결과로'], ['ENTER', '프롬프트 만들기']],
  '8': [['ESC', '홈'], ['ENTER', '최신 결과']],
}

// 흐름 화면 상단 단계 표시줄 (Pixel Flow 공용)
const FLOW = ['코드 연결', '검사 범위 분석', '검사 범위 선택', '검사 진행', '완료']
const FLOW_AT: Partial<Record<ScreenId, number>> = { '2': 0, '3': 1, '4': 2, '5': 3, '6': 4 }
const NUMS = ['①', '②', '③', '④', '⑤']

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
        <BottomBar hints={screen ? HINTS[screen] : []} home={screen === '1'} />
      </div>
    </>
  )
}
