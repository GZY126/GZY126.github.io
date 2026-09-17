import os
import json, urllib.request, base64

API_KEY = os.environ.get('ARK_API_KEY', '')
ENDPOINT = 'https://ark.cn-beijing.volces.com/api/v3/images/generations'

# 尝试各种可能的 Seedream 模型名
models_to_try = [
    "doubao-seedream-5.0-pro-260628",
    "doubao-seedream-5.0-260128",
    "doubao-seedream-4.5-251128",
    "doubao-seedream-4.0-250828",
    "doubao-seedream-3.0-t2i-250415",
]

prompt = "极简简笔画，一只小猫，只有黑色轮廓线条，纯白背景，无阴影无填充，儿童简笔画风格，单线条勾勒"

for model in models_to_try:
    print(f"测试模型: {model}")
    payload = {
        "model": model,
        "prompt": prompt,
        "n": 1,
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
                    fname = f'test_seedream_{model}_{i}.png'
                    with open(fname, 'wb') as f:
                        f.write(base64.b64decode(b64))
                    print(f"  [OK] 已保存: {fname}")
                elif url:
                    print(f"  [OK] URL: {url[:80]}...")
                    try:
                        req2 = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
                        with urllib.request.urlopen(req2, timeout=60) as resp2:
                            data2 = resp2.read()
                        fname = f'test_seedream_{model}_{i}.png'
                        with open(fname, 'wb') as f:
                            f.write(data2)
                        print(f"  [OK] 已保存: {fname}")
                    except Exception as e2:
                        print(f"  下载失败: {e2}")
                break  # 成功就跳出
        else:
            print(f"  返回: {json.dumps(result)[:200]}")
            break
    except urllib.error.HTTPError as e:
        body = e.read().decode('utf-8', errors='replace')
        err = json.loads(body) if body else {}
        msg = err.get('error', {}).get('message', f'HTTP {e.code}')
        print(f"  [FAIL] {msg[:200]}")
    except Exception as e:
        print(f"  [FAIL] {e}")
    print()

print("完成！")
