from dataclasses import dataclass, field


@dataclass(frozen=True)
class AiConfig:
    """한 요청 동안만 쓴다. AI 서버는 키를 저장하지 않고 로그에도 남기지 않는다"""
    provider: str  # claude · openai · custom(OpenAI 호환 주소)
    key: str = field(repr=False)  # 로그 · 예외 · 단언 메시지에 설정을 그대로 찍어도 키가 새지 않게 repr에서 뺀다 (-173)
    model: str | None = None
    base_url: str | None = None


def read_config(provider: str | None, base_url: str | None, key: str | None, model: str | None) -> AiConfig | None:
    """X-AI-* 헤더 → 설정. 키 헤더가 없거나 비어 있으면 None (AI 미연결 → 기본값으로 답한다)"""
    if not key or not key.strip():
        return None
    return AiConfig(provider=provider or "claude", key=key.strip(), model=model or None, base_url=base_url or None)
