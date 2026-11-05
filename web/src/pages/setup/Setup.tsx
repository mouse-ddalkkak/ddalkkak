import { useState } from 'react'
import { ackSetup, getSetup } from '../../api/setup'
import { useLoad } from '../../hooks/useLoad'
import { useCopy } from '../../hooks/useCopy'
import { useKeys } from '../../hooks/useKeys'
import Button from '../../components/Button'
import Checkbox from '../../components/Checkbox'
import TitleFrame from '../../components/TitleFrame'
import { HamsterSprite } from '../../components/HamsterWalk'
import s from './Setup.module.css'

// 0-1 설치 — 자동 생성 관리자 계정 (F-INST-03). "저장했어요" 체크 전에는 버튼 비활성
export default function Setup() {
  const { data } = useLoad(getSetup, [])
  const [saved, setSaved] = useState(false)
  const { copied, copy } = useCopy()
  const [busy, setBusy] = useState(false)

  const next = async () => {
    setBusy(true)
    try {
      await ackSetup()
      location.hash = '#/login'
    } catch {
      setBusy(false)
    }
  }
  useKeys({ Enter: () => saved && !busy && next() })

  return (
    <div className={s.wrap}>
      <TitleFrame className={s.card}>
        <span className={s.hero}>딸깍</span>
        <HamsterSprite height={112} />
      </TitleFrame>
      <section className={s.box}>
        <span className={s.lead}>관리자 계정을 자동으로 만들었어요. 로그인할 때 쓰세요.</span>
        <div className={s.field}>
          <span className={s.label}>아이디</span>
          <span className={s.value}>{data?.username ?? ''}</span>
        </div>
        <div className={s.field}>
          <span className={s.label}>비밀번호 · 자동 생성</span>
          <div className={s.pwRow}>
            <span className={`${s.value} ${s.pw}`}>{data?.oneTimePassword ?? ''}</span>
            <Button variant="outline" className={s.copy} onClick={() => data?.oneTimePassword && copy(data.oneTimePassword)} disabled={!data?.oneTimePassword}>
              {copied ? '복사됨' : '복사'}
            </Button>
          </div>
        </div>
        <div className={s.warn}>
          <span className={s.warnTitle}>지금 한 번만 보여요</span>
          <span className={s.warnText}>창을 닫으면 다시 볼 수 없어요. 터미널 로그에도 같은 값이 출력돼 있어요.</span>
        </div>
        <Checkbox checked={saved} onChange={setSaved}>
          저장했어요
        </Checkbox>
        <Button disabled={!saved || busy} onClick={next} className={s.go}>
          로그인하고 시작하기
        </Button>
      </section>
    </div>
  )
}
