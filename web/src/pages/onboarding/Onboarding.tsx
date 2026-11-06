import { useRef, useState } from 'react'
import { approveRequest, getConnectRequests, ignoreRequest, type ConnectRequest } from '../../api/services'
import { usePolling } from '../../hooks/usePolling'
import { useApp } from '../../app/AppContext'
import { useCopy } from '../../hooks/useCopy'
import { useKeys } from '../../hooks/useKeys'
import PageTitle from '../../components/PageTitle'
import StepChips, { type Step } from '../../components/StepChips'
import Panel from '../../components/Panel'
import Button from '../../components/Button'
import Led from '../../components/Led'
import AiForm from '../../components/AiForm'
import { ago } from '../../utils/format'
import s from './Onboarding.module.css'

// 0-3 온보딩 · 스타터킷 — 연결 요청 5초마다 (api.md "폴링 주기")
// 의존성 한 줄 · 안내문은 고정 문구 (F-CONN-01: 3주 범위 실제 동작은 Spring Boot만, 다른 탭은 "준비 중")
const SPRING = {
  file: 'build.gradle',
  // JitPack 이 공개 저장소 태그로 빌드한 좌표 (docs/starter-kit.md §6). build.gradle 끝에 그대로 붙여도 되게 블록째
  line: "repositories { maven { url 'https://jitpack.io' } }\ndependencies { implementation 'com.github.mouse-ddalkkak:ddalkkak:v1.1.0' }",
  prompt: [
    '이 프로젝트에 딸깍 스타터킷(com.github.mouse-ddalkkak:ddalkkak, 저장소 https://jitpack.io)을 설치해 주세요.',
    '1) 먼저 프로젝트의 빌드 도구(Gradle/Maven)와 설정 파일(build.gradle / build.gradle.kts / pom.xml),\nJava 버전, Spring Boot 버전을 확인해 주세요.',
    "2) 저장소 줄 maven { url 'https://jitpack.io' } 과 의존성 com.github.mouse-ddalkkak:ddalkkak:v1.1.0 을 추가해 주세요.\nbuild.gradle.kts면 Kotlin 문법(maven(\"https://jitpack.io\") · implementation(\"…\")), pom.xml이면 <repository> · <dependency>로 넣어 주세요.\n지원: Spring Boot 3.2 이상 · Java 17 이상 · Spring MVC 앱만 (아니면 설치하지 말고 알려 주세요)\n코드가 실행 중인 버전과 같은지 비교하려면 커밋 정보 플러그인도 넣어 주세요: Gradle id 'com.gorylenko.gradle-git-properties' version '4.0.1' · Maven io.github.git-commit-id:git-commit-id-maven-plugin (parent가 spring-boot-starter-parent가 아니면 revision goal 실행과 generateGitPropertiesFile=true도)\n다른 설정 파일이나 키는 필요 없어요.",
    '3) 빌드가 통과하는지 확인하고 서비스를 재시작해 주세요.\n바뀐 파일과 재시작 명령을 알려주세요.',
  ],
}
const FRAMEWORKS = ['Spring Boot', 'Node.js', 'Python', 'Go']

export default function Onboarding() {
  const { ai, showMessage } = useApp()
  const [requests, setRequests] = useState<ConnectRequest[]>([])
  const [fw, setFw] = useState('Spring Boot')
  const { copied, copy: copyText } = useCopy<'line' | 'kit'>()
  const [didCopy, setDidCopy] = useState(false) // 단계 표시 ①→② (복사됨 표시는 1.6초 뒤 사라져도 단계는 유지)
  // 허용 · 무시는 이 화면 상태로 둔다 (무시는 서버에 저장되지 않는다)
  // 허용한 요청은 다음 폴링에서 빠지므로(대기 중인 요청만 온다) 요청 객체째 둔다
  const [approved, setApproved] = useState<(ConnectRequest & { name: string }) | null>(null)
  const [ignored, setIgnored] = useState<string[]>([])

  usePolling(
    async (alive) => {
      const list = await getConnectRequests()
      if (alive()) setRequests([...list].sort((a, b) => b.requestedAt.localeCompare(a.requestedAt)))
    },
    5_000,
    [],
  )

  const visible = requests.filter((r) => !ignored.includes(r.id))
  const detected = visible.find((r) => r.javaVersion || r.buildTool) ?? approved

  const copy = async (what: 'line' | 'kit', text: string) => {
    if (await copyText(text, what)) setDidCopy(true)
  }

  const approving = useRef(false) // ENTER 연타에도 허용 요청은 한 번
  const approve = async (r: ConnectRequest) => {
    if (approving.current) return
    approving.current = true
    try {
      const res = await approveRequest(r.id)
      setApproved({ ...r, name: res.name })
      showMessage(`${res.name} 연결됨`)
    } catch {
      // 404 · 409 문구는 래퍼가 띄웠다
    } finally {
      approving.current = false
    }
  }
  const ignore = async (r: ConnectRequest) => {
    try {
      await ignoreRequest(r.id)
      setIgnored((x) => [...x, r.id])
      showMessage('요청을 무시했어요 · 다시 뜨면 허용할 수 있어요')
    } catch {
      // 래퍼가 띄웠다
    }
  }

  const step1 = didCopy || visible.length > 0 || approved !== null
  const step2 = visible.length > 0 || approved !== null
  const aiOk = !!ai?.connected
  const steps: Step[] = [
    { label: '① 한 줄 추가', state: step1 ? 'done' : 'on' },
    { label: '② 재시작', state: step2 ? 'done' : step1 ? 'on' : 'todo' },
    { label: '③ 연결 허용', state: approved ? 'done' : step2 ? 'on' : 'todo' },
    { label: approved || aiOk ? '④ AI 연결 · 선택' : '④ AI 연결', state: aiOk ? 'done' : approved ? 'on' : 'todo' },
  ]
  const isSpring = fw === 'Spring Boot'
  const card = approved ?? visible[0]
  // ESC 나중에(홈) · ENTER 연결 허용 → 허용한 뒤 한 번 더 누르면 홈으로
  useKeys({
    Escape: () => (location.hash = '#/'),
    Enter: () => (approved ? (location.hash = '#/') : card && approve(card)),
  })

  return (
    <div className={s.wrap}>
      <PageTitle
        title="검사할 서비스에 딸깍 스타터킷을 넣어주세요"
        sub={
          <>
            서비스 런타임에 붙는 작은 라이브러리예요
            <br />
            설정 없음, 한 줄이면 끝
          </>
        }
      />
      <StepChips steps={steps} doneMark="after" />
      <div className={s.grid}>
        <Panel className={s.kit}>
          <div className={s.tabs}>
            {FRAMEWORKS.map((f) => (
              <button key={f} type="button" className={s.tab} data-on={f === fw ? '' : undefined} onClick={() => setFw(f)}>
                {f}
              </button>
            ))}
            <span className={s.grow} />
            {detected && (detected.javaVersion || detected.buildTool) && (
              <span className={s.caption}>
                감지됨: {[detected.javaVersion && `Java ${detected.javaVersion}`, detected.buildTool].filter(Boolean).join(' · ')}
              </span>
            )}
          </div>
          {isSpring ? (
            <>
              <span className={s.stepTitle}>1. 저장소 줄 · 의존성 한 줄 · {SPRING.file}</span>
              <div className={s.lineRow}>
                <code className={s.line}>
                  <span className={s.prompt}>&gt;</span>
                  {SPRING.line}
                </code>
                <Button variant="outline" size="sm" onClick={() => copy('line', SPRING.line)}>
                  {copied === 'line' ? '복사됨' : '복사'}
                </Button>
              </div>
              <span className={s.caption}>키·설정 파일 필요 없어요. 같은 서버의 딸깍(:7777)을 스스로 찾아요</span>
              <div className={s.dash} />
              <div className={s.kitHead}>
                <span className={s.kitTitle}>직접 하기 어렵다면 — AI 코딩 도구에 붙여넣기</span>
                <Button variant="accent" size="sm" onClick={() => copy('kit', SPRING.prompt.join('\n'))}>
                  {copied === 'kit' ? '복사됨' : '프롬프트 복사'}
                </Button>
              </div>
              <div className={s.kitText}>
                {SPRING.prompt.map((t) => (
                  <span key={t}>{t}</span>
                ))}
              </div>
            </>
          ) : (
            <span className={s.caption}>준비 중</span>
          )}
        </Panel>

        <div className={s.col}>
          <Panel>
            <span className={s.stepTitle}>2. 서비스 재시작 → 3. 연결 요청이 뜨면 허용</span>
            {card && (
              <div className={s.request}>
                <div className={s.reqHead}>
                  <span className={s.led}>
                    <Led color="green-400" />
                  </span>
                  <div className={s.reqText}>
                    <span className={s.reqTitle}>{approved ? `${approved.name}와 연결됐어요` : `${card.appName}가 연결을 요청했어요`}</span>
                    <span className={s.caption}>
                      {card.host}:{card.port} · {card.framework} {card.frameworkVersion} · PID {card.pid} · {ago(card.requestedAt)}
                    </span>
                  </div>
                </div>
                <div className={s.reqActions}>
                  {approved ? (
                    <span className={s.done}>
                      <span className={s.doneDot} />✓ {approved.name} 연결됨
                    </span>
                  ) : (
                    <>
                      <Button onClick={() => approve(card)}>이 서비스 연결 허용</Button>
                      <Button variant="text" onClick={() => ignore(card)}>
                        아니에요
                      </Button>
                    </>
                  )}
                </div>
              </div>
            )}
          </Panel>
          <Panel>
            <div className={s.aiHead}>
              <span className={s.stepTitle}>4. AI 연결 · 선택</span>
              <span className={s.caption}>없으면 기본 범위와 기본 틀로 검사해요</span>
            </div>
            <AiForm />
          </Panel>
        </div>
      </div>
      <div className={s.foot}>
        <span className={s.grow} />
        {approved ? (
          <Button onClick={() => (location.hash = '#/')}>홈으로</Button>
        ) : (
          <Button disabled arrow={false}>
            허용하면 홈으로
          </Button>
        )}
      </div>
    </div>
  )
}
