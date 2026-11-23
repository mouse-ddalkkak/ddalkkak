"""OpenAI 호환 주소(CUSTOM_BASE_URL)에서 쓸 수 있는 모델 ID 목록을 보여 준다 (손으로 돌리는 도우미).

    cd ai
    python scripts/list_models.py            # 전부
    python scripts/list_models.py luna sol   # 이름에 luna 또는 sol이 든 것만

CUSTOM_BASE_URL · CUSTOM_API_KEY를 환경변수로 넣어 둔다. 키는 출력하지 않는다.
"""
import os
import sys

import openai


def main() -> int:
    base_url, key = os.environ.get("CUSTOM_BASE_URL"), os.environ.get("CUSTOM_API_KEY")
    if not base_url or not key:
        print("CUSTOM_BASE_URL · CUSTOM_API_KEY를 먼저 환경변수로 넣어 주세요")
        return 2
    words = [w.lower() for w in sys.argv[1:]]
    try:
        ids = sorted(m.id for m in openai.OpenAI(api_key=key, base_url=base_url, timeout=15, max_retries=0).models.list())
    except Exception as e:  # 키가 메시지에 섞이지 않게 종류만 보여 준다
        print(f"목록을 못 받았어요 · {type(e).__name__} · HTTP {getattr(e, 'status_code', '-')}")
        return 1
    shown = [i for i in ids if not words or any(w in i.lower() for w in words)]
    print(f"모델 {len(ids)}개 중 {len(shown)}개")
    for i in shown:
        print(" ", i)
    return 0


if __name__ == "__main__":
    sys.exit(main())
