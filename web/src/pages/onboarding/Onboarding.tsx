import { useRef, useState } from 'react'
import { approveRequest, disconnectService, getConnectRequests, ignoreRequest, type ConnectRequest } from '../../api/services'
import { usePolling } from '../../hooks/usePolling'
import { useApp } from '../../app/AppContext'
import { useCopy } from '../../hooks/useCopy'
import PageTitle from '../../components/PageTitle'
import StepChips from '../../components/StepChips'
import Panel from '../../components/Panel'
import Button from '../../components/Button'
import Led from '../../components/Led'
import AiForm from '../../components/AiForm'
import CodeConnect from '../code-source/CodeSource'
import Dialog from '../../components/Dialog'
import { ApiError } from '../../api/client'
import { ago } from '../../utils/format'
import { getHome, type Home } from '../../api/home'
import { canNext, connectedNormally, kitEnv, needsConfirm, nextHash, prevHash, redirectHome, stepChips, type OnbStep } from './onboardingStep'
import s from './Onboarding.module.css'

// 0-3 온보딩 — 한 화면에 한 단계 ① 스타터킷 추가 → ② 서비스 연결 → ③ AI 키 추가 → ④ 소스코드 연결 (-222)
// 단계는 주소 #/onboarding/N. 연결 요청은 어느 단계든 5초마다 (api.md "폴링 주기"), 홈은 연결 전까지 같이
// ① 은 공개 저장소 ddalkkak-starter 의 스킬 · 플러그인 설치 명령과 프롬프트 (F-CONN-01, -226). 다른 탭은 "준비 중"
// 프롬프트는 그 저장소 prompt.md 와 같은 글이다. 스타터킷 좌표를 바꾸면 둘 다 고친다 (docs/starter-kit.md §6)
const STARTER_REPO = 'https://github.com/mouse-ddalkkak/ddalkkak-starter'
const SPRING = {
  tools: [
    {
      key: 'claude',
      name: 'Claude Code',
      install: 'claude plugin marketplace add mouse-ddalkkak/ddalkkak-starter\nclaude plugin install ddalkkak-starter@ddalkkak-starter',
      run: '/ddalkkak-starter:add-starter-kit',
    },
    {
      key: 'codex',
      name: 'Codex',
      install: 'codex plugin marketplace add mouse-ddalkkak/ddalkkak-starter\ncodex plugin add ddalkkak-starter@ddalkkak-starter',
      run: '$add-starter-kit',
    },
  ] as const,
  prompt: [
    '이 프로젝트에 딸깍 스타터킷(com.github.mouse-ddalkkak:ddalkkak, 저장소 https://jitpack.io)을 설치해 주세요.',
    '1) 먼저 프로젝트의 빌드 도구(Gradle/Maven)와 설정 파일(build.gradle / build.gradle.kts / pom.xml),\nJava 버전, Spring Boot 버전을 확인해 주세요.',
    "2) 저장소 줄 maven { url 'https://jitpack.io' } 과 의존성 com.github.mouse-ddalkkak:ddalkkak:v1.2.0 을 추가해 주세요.\nbuild.gradle.kts면 Kotlin 문법(maven(\"https://jitpack.io\") · implementation(\"…\")), pom.xml이면 <repository> · <dependency>로 넣어 주세요.\n지원: Spring Boot 3.2 이상 · Java 17 이상 · Spring MVC 앱만 (아니면 설치하지 말고 알려 주세요)\n코드가 실행 중인 버전과 같은지 비교하려면 커밋 정보 플러그인도 넣어 주세요: Gradle id 'com.gorylenko.gradle-git-properties' version '4.0.1' · Maven io.github.git-commit-id:git-commit-id-maven-plugin (parent가 spring-boot-starter-parent가 아니면 revision goal 실행과 generateGitPropertiesFile=true도). git 저장소가 아니면 이 플러그인은 빼 주세요.\n다른 설정 파일이나 키는 필요 없어요.",
    '3) 빌드가 통과하는지 확인하고 서비스를 재시작해 주세요.\n바뀐 파일과 재시작 명령을 알려주세요.',
  ],
}
const FRAMEWORKS = ['Spring Boot', 'Node.js', 'Python', 'Go']
const TITLES: Record<OnbStep, { title: string; sub: string }> = {
  1: { title: '검사할 서비스에 딸깍 스타터킷을 넣어주세요', sub: '서비스 런타임에 붙는 작은 라이브러리예요 · 설정 없음, AI 코딩 도구가 넣어 줘요' },
  2: { title: '서비스를 재시작하고 연결을 허용해 주세요', sub: '스타터킷이 딸깍을 찾아 연결을 요청해요' },
  3: { title: 'AI 키를 추가해 주세요', sub: '없으면 기본 범위와 기본 틀로 검사해요 · 건너뛰어도 돼요' },
  4: { title: '소스코드를 연결해 주세요', sub: '한 번 연결하면 검사할 때마다 이 방식으로 최신 코드를 가져와요' },
}

export default function Onboarding({ step }: { step: OnbStep }) {
  const { showMessage, home: appHome, setHome: setAppHome } = useApp()
  const [requests, setRequests] = useState<ConnectRequest[]>([])
  const [fw, setFw] = useState('Spring Boot')
  const { copied, copy } = useCopy<'claude' | 'codex' | 'kit'>()
  // 새로고침 뒤에도 연결 여부를 알도록 (허용한 서비스는 대기 목록에서 빠진다). 아래 폴링이 연결 전까지 받는다
  const [home, setHome] = useState<Home>()
  // 허용 · 무시는 이 화면 상태로 둔다 (무시는 서버에 저장되지 않는다)
  // 허용한 요청은 다음 폴링에서 빠지므로(대기 중인 요청만 온다) 요청 객체째 둔다
  const [approved, setApproved] = useState<(ConnectRequest & { name: string }) | null>(null)
  const [ignored, setIgnored] = useState<string[]>([])
  const [picked, setPicked] = useState<string>() // ② 목록에서 고른 요청. 없으면 맨 위
  const entered = useRef(false) // 들어올 때 한 번만 홈으로 보낼지 본다 (단계를 옮겨도 이 화면은 다시 그려지지 않는다)

  const loadRequests = async (alive: () => boolean = () => true) => {
    const list = await getConnectRequests()
    if (alive()) setRequests([...list].sort((a, b) => b.requestedAt.localeCompare(a.requestedAt)))
  }

  usePolling(
    async (alive) => {
      await loadRequests(alive)
      // 허용된 적 있는 앱이 다시 켜지면 카드 없이 바로 연결된다(-211) · 해제 안내(-223)도 home에서 본다
      const h = await getHome()
      if (!alive()) return
      setHome(h)
      if (!entered.current) {
        entered.current = true
        // 정상 연결 중 ① · ② 주소로 들어오면 홈 (F-CONN-08, -223)
        if (redirectHome(step, h.service)) location.hash = '#/'
      }
    },
    5_000,
    [],
  )

  const visible = requests.filter((r) => !ignored.includes(r.id))
  const detected = visible.find((r) => r.javaVersion || r.buildTool) ?? approved

  // 409 ALREADY_CONNECTED 모달 (-213). 허용하려던 요청을 둬 "기존 연결 해제" 뒤 다시 허용한다 (-223)
  const [already, setAlready] = useState<ConnectRequest | null>(null)
  const [discErr, setDiscErr] = useState<string | null>(null) // 해제 409 문구 (검사 중)
  const disconnecting = useRef(false)
  const approving = useRef(false) // 연타에도 허용 요청은 한 번
  const approve = async (r: ConnectRequest) => {
    if (approving.current) return
    approving.current = true
    try {
      const res = await approveRequest(r.id)
      setApproved({ ...r, name: res.name })
      showMessage(`${res.name} 연결됨`)
    } catch (e) {
      // 이미 연결된 서비스가 있으면(3주 범위는 1개) 하단 바 대신 모달로. 404 등은 래퍼가 띄웠다
      if (e instanceof ApiError && e.code === 'ALREADY_CONNECTED') setAlready(r)
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

  /** 사용자가 확인한 해제 (F-CONN-09). 성공하면 true — 검사 중이면 409 문구를 그 자리에 둔다 */
  const disconnect = async () => {
    const svc = home?.service
    if (!svc || disconnecting.current) return false
    disconnecting.current = true
    try {
      await disconnectService(svc.id)
      setDiscErr(null)
      setApproved(null)
      setHome((h) => (h ? { ...h, service: null } : h))
      // 상단 바 폴링(10초) 전에 홈으로 가도 해제한 서비스로 해제 안내를 다시 띄우지 않게 (!134 리뷰 2-2)
      if (appHome) setAppHome({ ...appHome, service: null })
      showMessage(`${svc.name} 연결을 해제했어요`)
      await loadRequests()
      return true
    } catch (e) {
      if (e instanceof ApiError && e.code === 'RUN_IN_PROGRESS') setDiscErr('검사가 끝나거나 중단한 뒤 해제할 수 있어요')
      return false
    } finally {
      disconnecting.current = false
    }
  }
  const disconnectAndApprove = async (r: ConnectRequest) => {
    if (await disconnect()) {
      setAlready(null)
      await approve(r)
    }
  }

  // 이 화면에서 허용했거나 정상 연결된 서비스 (-222: ② 다음 · 나중에 기준). 해제 확인이 필요하면 연결로 치지 않는다 (-223)
  const connectedName = approved?.name ?? (connectedNormally(home?.service ?? null) ? home!.service!.name : null)
  const ask = !approved && needsConfirm(home?.service ?? null) ? home!.service! : null
  const env = kitEnv(home?.kitUrl) // 딸깍이 7777이 아니면 ① 안내 (-151)
  const isSpring = fw === 'Spring Boot'
  const chosen = visible.find((r) => r.id === picked) ?? visible[0]

  return (
    <div className={s.wrap}>
      <PageTitle title={TITLES[step].title} sub={TITLES[step].sub} />
      <StepChips steps={stepChips(step)} doneMark="after" />
      {step === 1 && (
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
              {SPRING.tools.map((t) => (
                <div key={t.key} className={s.tool}>
                  <span className={s.stepTitle}>{t.name} · 스킬 설치</span>
                  <div className={s.lineRow}>
                    <code className={s.line}>
                      <span className={s.prompt}>&gt;</span>
                      {t.install}
                    </code>
                    <Button variant="outline" size="sm" aria-label={`${t.name} 복사`} onClick={() => copy(t.install, t.key)}>
                      {copied === t.key ? '복사됨' : '복사'}
                    </Button>
                  </div>
                  <span className={s.caption}>
                    설치한 뒤 검사할 서비스 폴더에서 {t.name}를 열고 <code className={s.cmd}>{t.run}</code> 실행
                  </span>
                </div>
              ))}
              {env ? (
                <span className={s.caption}>
                  딸깍이 7777이 아닌 포트로 열려 있어요. 검사할 서비스에 환경 변수{' '}
                  <code className={s.cmd}>{env}</code>을 넣어 주세요
                </span>
              ) : (
                <span className={s.caption}>키·설정 파일 필요 없어요. 같은 서버의 딸깍(:7777)을 스스로 찾아요</span>
              )}
              <div className={s.dash} />
              <div className={s.kitHead}>
                <span className={s.kitTitle}>다른 AI 코딩 도구라면 — 붙여넣기</span>
                <Button variant="accent" size="sm" onClick={() => copy(SPRING.prompt.join('\n'), 'kit')}>
                  {copied === 'kit' ? '복사됨' : '프롬프트 복사'}
                </Button>
              </div>
              <div className={s.kitText}>
                {SPRING.prompt.map((t) => (
                  <span key={t}>{t}</span>
                ))}
              </div>
              <span className={s.caption}>
                직접 넣는 줄과 딸깍 버전별 호환 표는{' '}
                <a className={s.link} href={STARTER_REPO} target="_blank" rel="noreferrer">
                  github.com/mouse-ddalkkak/ddalkkak-starter
                </a>
              </span>
            </>
          ) : (
            <span className={s.caption}>준비 중</span>
          )}
        </Panel>
      )}
      {step === 2 && (
        <Panel>
          <div className={s.listHead}>
            <span className={s.stepTitle}>서비스 재시작 → 연결할 서비스를 골라 허용</span>
            <span className={s.grow} />
            {!connectedName && !ask && (
              <Button variant="outline" size="sm" arrow={false} onClick={() => loadRequests().catch(() => {})}>
                다시 찾기
              </Button>
            )}
          </div>
          {connectedName ? (
            <span className={s.done}>
              <span className={s.doneDot} />✓ {connectedName} 연결됨
            </span>
          ) : ask ? (
            // 연결 점검 3번 연속 실패 → 사용자가 확인해야만 해제 (F-CONN-09)
            <div className={s.request} data-warn="">
              <span className={s.reqTitle}>{ask.name}에 3번 연속 닿지 않았어요</span>
              <span className={s.caption}>연결을 해제하면 다른 서비스를 연결할 수 있어요</span>
              {discErr && <span className={s.warn}>{discErr}</span>}
              <div className={s.reqActions}>
                <Button onClick={() => disconnect()}>연결 해제</Button>
              </div>
            </div>
          ) : visible.length === 0 ? (
            <span className={s.caption}>연결 요청을 기다리고 있어요</span>
          ) : (
            <>
              {/* 스타터킷이 hello를 보낸 앱이 곧 연결할 수 있는 서비스 — 최근 것부터 (F-CONN-03) */}
              <div className={s.list} role="radiogroup" aria-label="연결할 수 있는 서비스">
                {visible.map((r) => (
                  <button
                    key={r.id}
                    type="button"
                    role="radio"
                    aria-checked={r.id === chosen?.id}
                    className={s.row}
                    data-on={r.id === chosen?.id ? '' : undefined}
                    onClick={() => setPicked(r.id)}
                  >
                    <Led color={r.id === chosen?.id ? 'green-400' : 'ink-600'} />
                    <span className={s.rowName}>{r.appName}</span>
                    <span className={s.caption}>
                      {r.host}:{r.port} · {r.framework} {r.frameworkVersion} · PID {r.pid} · {ago(r.requestedAt)}
                    </span>
                  </button>
                ))}
              </div>
              {chosen && (
                <div className={s.reqActions}>
                  <Button onClick={() => approve(chosen)}>이 서비스 연결</Button>
                  <Button variant="text" onClick={() => ignore(chosen)}>
                    아니에요
                  </Button>
                </div>
              )}
            </>
          )}
        </Panel>
      )}
      {step === 3 && (
        <Panel>
          <AiForm />
        </Panel>
      )}
      {step === 4 && (
        // ④ 소스코드 연결 — 검사를 시작할 때는 코드 연결 화면을 거치지 않는다 (-224 · -245)
        <Panel>
          <CodeConnect />
        </Panel>
      )}
      <div className={s.foot}>
        {step > 1 && (
          <Button variant="outline" arrow={false} onClick={() => (location.hash = prevHash(step))}>
            이전
          </Button>
        )}
        {!connectedName && (
          <Button variant="text" arrow={false} onClick={() => (location.hash = '#/')}>
            나중에
          </Button>
        )}
        <span className={s.grow} />
        {canNext(step, !!connectedName) ? (
          <Button onClick={() => (location.hash = nextHash(step))}>{step === 4 ? '홈으로' : '다음'}</Button>
        ) : (
          <Button disabled arrow={false}>
            허용하면 다음
          </Button>
        )}
      </div>
      {already && (
        <Dialog
          title="이미 연결된 서비스가 있어요"
          onClose={() => setAlready(null)}
          actions={
            <>
              <span className={s.grow} />
              <Button variant="outline" onClick={() => setAlready(null)}>
                닫기
              </Button>
              {/* 끈 앱 자리(같은 포트)에 다른 앱을 띄우면 점검이 성공해 해제 안내가 안 뜬다 → 끊겼으면 여기서 해제 (-223) */}
              {home?.service && !home.service.connected && (
                <Button variant="outline" arrow={false} onClick={() => disconnectAndApprove(already)}>
                  기존 연결 해제
                </Button>
              )}
              <Button onClick={() => (location.hash = '#/')}>홈으로</Button>
            </>
          }
        >
          <span className={s.dlgText}>지금은 서비스를 하나만 연결할 수 있어요. 홈에서 연결된 서비스를 확인해 주세요.</span>
          {discErr && <span className={s.warn}>{discErr}</span>}
        </Dialog>
      )}
    </div>
  )
}
