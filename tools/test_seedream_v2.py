import os
import json, urllib.request, base64

API_KEY = os.environ.get('ARK_API_KEY', '')
ENDPOINT = 'https://ark.cn-beijing.volces.com/api/v3/images/generations'

# 火山引擎 Seedream 5.0 的可能模型名
models = [
    "doubao-seedream-5-0-260128",
    "doubao-seedream-5.0-260128",
    "seedream-5.0",
    "doubao-seedream-5-0",
    "ep-20250101000000-xxxxx",  # 端点ID格式
]

# 也试一下直接列出所有可用模型
print("=== 列出可用模型 ===")
try:
    req = urllib.request.Request(
        'https://ark.cn-beijing.volces.com/api/v3/models',
        headers={'Authorization': f'Bearer {API_KEY}'}
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read().decode('utf-8'))
    if 'data' in data:
        for m in data['data']:
            mid = m.get('id', '')
            if any(kw in mid.lower() for kw in ['seedream', 'image', 't2i']):
                print(f"  [图片模型] {mid}")
            elif 'seedream' in mid.lower():
                print(f"  [种子] {mid}")
except Exception as e:
    print(f"  失败: {e}")

print()

# 测试生成
for model in models:
    print(f"测试: {model}")
    payload = {
        "model": model,
        "prompt": "简笔画，一只猫，黑色线条，白色背景",
        "size": "1024x1024",
    }
    try:
        req = urllib.request.Request(
            ENDPOINT,
            data=json.dumps(payload).encode('utf-8'),
            headers={
                'Content-Type': 'application/json',
                'Authorization': f'Bearer {API_KEY}',
            },
        )
        with urllib.request.urlopen(req, timeout=120) as resp:
            result = json.loads(resp.read().decode('utf-8'))
        if 'data' in result:
            for i, img in enumerate(result['data']):
                url = img.get('url', '')
                b64 = img.get('b64_json', '')
                if b64:
                    fname = f'seedream_test_{i}.png'
                    with open(fname, 'wb') as f:
                        f.write(base64.b64decode(b64))
                    print(f"  [OK] {fname}")
                if url:
                    print(f"  [OK] URL: {url[:100]}")
                    req2 = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
                    with urllib.request.urlopen(req2, timeout=60) as resp2:
                        data2 = resp2.read()
                    fname = f'seedream_test_{i}.png'
                    with open(fname, 'wb') as f:
                        f.write(data2)
                    print(f"  [OK] 下载: {fname} ({len(data2)} bytes)")
        else:
            print(f"  返回: {json.dumps(result)[:200]}")
    except urllib.error.HTTPError as e:
        body = e.read().decode('utf-8', errors='replace')
        try:
            err = json.loads(body)
            msg = err.get('error', {}).get('message', f'HTTP {e.code}')
        except:
            msg = f'HTTP {e.code}: {body[:100]}'
        print(f"  [FAIL] {msg}")
    except Exception as e:
        print(f"  [FAIL] {e}")
    print()
