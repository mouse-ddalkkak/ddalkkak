# docs/openapi.yaml 예시 -> contracts/4-screen/<화면>.json (④) · contracts/{1-starter,2-scenarios,3-reports}/<호출>.json (①②③)
# python scripts/screen_contracts.py   (Node 필요 · redocly로 $ref를 푼다)
# ④ 엔드포인트가 어느 화면에도 없거나 2xx 예시가 없으면 실패한다
import json, pathlib, shutil, subprocess, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

# 모든 화면에 공통인 오류 → common.json 한 곳에만 둔다 (HTTP 코드 → openapi components/responses 이름)
COMMON_ERRORS = {'401': 'NotLoggedIn', '400': 'BadRequest', '500': 'ServerError'}
COMMON_CODES = {'NOT_LOGGED_IN', 'BAD_REQUEST', 'SERVER_ERROR'}

SCREENS = {
    'common': ('공통 · 상단 바(AI 표시 · admin ▾ AI 연결 · 로그아웃)',
               ['GET /api/auth/me', 'POST /api/auth/logout', 'GET /api/ai-settings', 'PUT /api/ai-settings', 'DELETE /api/ai-settings', 'POST /api/ai-settings/check']),
    '0-1-setup': ('0-1 설치', ['GET /api/setup', 'POST /api/setup/ack']),
    '0-2-login': ('0-2 로그인', ['POST /api/auth/login']),
    '0-3-onboarding': ('0-3 온보딩 (① 스타터킷 → ② 서비스 연결 → ③ AI → ④ 코드 연결)',
                      ['GET /api/services/requests', 'POST /api/services/requests/{id}/approve', 'POST /api/services/requests/{id}/ignore',
                       'POST /api/services/{id}/disconnect', 'GET /api/ai-settings', 'PUT /api/ai-settings', 'DELETE /api/ai-settings', 'POST /api/ai-settings/check', 'GET /api/home',
                       'GET /api/code-source', 'GET /api/code-source/artifacts', 'POST /api/code-source', 'POST /api/code-source/zip',
                       'GET /api/code-source/jobs/{jobId}']),
    '1-home': ('1 홈 (10초마다 · 도구 상태 팝업 · 새 버전 띠 · 딸깍 검사는 저장한 코드 연결이 있으면 바로 준비, 없으면 0-3 ④)',
               ['GET /api/home', 'GET /api/services/requests', 'POST /api/services/{id}/disconnect', 'GET /api/tools',
                'GET /api/code-source', 'POST /api/runs/prepare', 'POST /api/runs/resume', 'POST /api/runs/{id}/finish-partial',
                'GET /api/update', 'POST /api/update']),
    '3-wait': ('3 검사 범위 분석 (준비 중)', ['POST /api/runs/prepare', 'GET /api/runs/prepare/{id}', 'POST /api/runs/prepare/{id}/use-default']),
    '4-scope': ('4 검사 범위 선택', ['GET /api/runs/prepare/{id}/scope', 'GET /api/runs/prepare/{id}/apis', 'PUT /api/runs/prepare/{id}/scope',
                                   'GET /api/runs/prepare/{id}/auth', 'PUT /api/runs/prepare/{id}/auth', 'POST /api/runs']),
    '5-run': ('5 검사 진행',
              ['GET /api/runs/{id}/events', 'GET /api/runs/{id}/progress', 'POST /api/runs/{id}/stop',
               'POST /api/runs/{id}/retry', 'POST /api/runs/{id}/finish-partial', 'POST /api/runs/resume', 'GET /api/runs/{id}/logs']),
    '6-result': ('6 결과', ['GET /api/runs/{id}', 'PATCH /api/runs/{id}', 'POST /api/runs/{id}/report', 'POST /api/runs/{id}/prompts']),
    '7-issue': ('7 문제 상세 + 수정 요청문', ['GET /api/runs/{id}/issues/{issueId}', 'POST /api/runs/{id}/prompts', 'GET /api/runs/{id}/logs']),
    '8-history': ('8 이력', ['GET /api/runs', 'PATCH /api/runs/{id}']),
}

# ①②③ 화면이 없는 호출 → 호출 1개 = 파일 1개, 이름은 경로 마지막 조각 (POST /ai/reports → reports.json).
# 공통 오류 파일이 없어 400도 각 파일에 둔다
CALLS = {
    '① 스타터킷': 'contracts/1-starter',
    '② 시나리오': 'contracts/2-scenarios',
    '③ 결과·점수': 'contracts/3-reports',
}

def samples(media):
    """[(이름|None, 값)] — 기본(example · schema.example · 배열이면 [items.example]) + 이름 붙은 examples"""
    s = media.get('schema', {})
    out = []
    if 'example' in media:
        out.append((None, media['example']))
    elif 'example' in s:
        out.append((None, s['example']))
    elif s.get('type') == 'array' and 'example' in s.get('items', {}):
        out.append((None, [s['items']['example']]))
    return out + [(k, v['value']) for k, v in media.get('examples', {}).items()]


def call(op, variant, skip_codes=COMMON_CODES):
    out = {'summary': op['summary'], 'owner': op['x-owner']}
    headers = {p['name']: {'required': p.get('required', False), **({'enum': p['schema']['enum']} if 'enum' in p['schema'] else {'type': p['schema']['type']})}
               for p in op.get('parameters', []) if p['in'] == 'header'}
    if headers:
        out['headers'] = headers
    for ct, media in op.get('requestBody', {}).get('content', {}).items():
        if ct == 'multipart/form-data':
            out['request'] = {'multipart': list(media['schema']['properties'])}
            continue
        for name, v in samples(media):
            out['request' + (f' {name}' if name else '')] = v
    res = {}
    for code, r in op['responses'].items():
        content = r.get('content')
        if not content:
            res[code] = None
            continue
        for ct, media in content.items():
            got = samples(media)
            if ct == 'text/event-stream':
                out['events'] = [{'event': n, 'data': v} for n, v in got]
                continue
            if code.startswith('2') and variant:
                got = [(n, v) for n, v in got if n == variant]
            if not got and code.startswith('2'):
                sys.exit(f'예시 없음: {op["operationId"]} {code}')
            for name, v in got:
                if isinstance(v, dict) and str(v.get('code')) in skip_codes:
                    continue  # 공통 규칙 → common.json
                res[code + (f' {name}' if name and not variant else '')] = v
    if res:
        out['responses'] = res
    return out


def bundle():
    with tempfile.TemporaryDirectory() as tmp:
        path = pathlib.Path(tmp) / 'api.json'
        npx = shutil.which('npx') or sys.exit('npx가 없어요 · Node를 설치하세요')
        r = subprocess.run([npx, '-y', '@redocly/cli@1.34.3', 'bundle', 'docs/openapi.yaml',
                            '--dereferenced', '--ext', 'json', '-o', str(path)], cwd=ROOT, capture_output=True, encoding='utf-8', errors='replace')
        if r.returncode:
            sys.exit(r.stderr or r.stdout)
        return json.loads(path.read_text(encoding='utf-8'))


def tagged(d, tag):
    return {f'{m.upper()} {p}': op for p, item in d['paths'].items() for m, op in item.items()
            if isinstance(op, dict) and tag in op.get('tags', [])}


def call_files(d, tag):
    files = {}
    for k, op in tagged(d, tag).items():
        name = k.rsplit('/', 1)[-1] + '.json'
        if name in files:
            sys.exit(f'{tag} 파일 이름이 겹침: {name}')
        doc = {'source': f'docs/openapi.yaml {tag[0]} 예시에서 뽑음 · 고칠 때는 openapi.yaml을 고치고 python scripts/screen_contracts.py',
               'calls': {k: call(op, '', skip_codes=frozenset())}}
        files[name] = json.dumps(doc, ensure_ascii=False, indent=2) + '\n'
    return files


def write(out_dir, files):
    """다 만든 뒤에만 바꾼다. 이름이 바뀌거나 빠진 파일의 옛 것도 지운다"""
    out = ROOT / out_dir
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob('*.json'):
        old.unlink()
    for name, text in files.items():
        (out / name).write_text(text, encoding='utf-8', newline='\n')


def main():
    d = bundle()
    ops = tagged(d, '④ 화면')
    used = set()
    files = {}
    for fname, (title, keys) in SCREENS.items():
        doc = {'screen': title, 'source': 'docs/openapi.yaml ④ 예시에서 뽑음 · 고칠 때는 openapi.yaml을 고치고 python scripts/screen_contracts.py', 'calls': {}}
        if fname == 'common':
            doc['errors'] = {code: d['components']['responses'][name]['content']['application/json']['example']
                             for code, name in COMMON_ERRORS.items()}
            doc['errors']['note'] = '401은 0-1·0-2 외 모든 /api/* → 화면은 0-2로. 400 · 500은 모든 /api/*'
        for key in keys:
            k, _, variant = key.partition('#')
            if k not in ops:
                sys.exit(f'openapi에 없음: {k}')
            used.add(k)
            doc['calls'][k] = call(ops[k], variant)
        files[f'{fname}.json'] = json.dumps(doc, ensure_ascii=False, indent=2) + '\n'
    missing = set(ops) - used
    if missing:
        sys.exit(f'화면에 안 들어간 ④ 엔드포인트: {sorted(missing)}')
    others = {out_dir: call_files(d, tag) for tag, out_dir in CALLS.items()}
    write('contracts/4-screen', files)
    for out_dir, texts in others.items():
        write(out_dir, texts)
    print(f'4-screen: {len(SCREENS)} files, {len(ops)} ops covered · '
          + ' · '.join(f'{p.rsplit("/", 1)[-1]}: {len(t)} files' for p, t in others.items()))


if __name__ == '__main__':
    main()
