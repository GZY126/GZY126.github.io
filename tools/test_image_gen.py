import os
import json, urllib.request, base64, time

API_KEY = os.environ.get('ARK_API_KEY', '')

print("=" * 60)
print("测试 1：列出可用模型")
print("=" * 60)
try:
    req = urllib.request.Request(
        'https://ark.cn-beijing.volces.com/api/v3/models',
        headers={'Authorization': f'Bearer {API_KEY}'}
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        models = json.loads(resp.read().decode('utf-8'))
    
    if isinstance(models, dict) and 'data' in models:
        for m in models['data']:
            print(f"  {m.get('id', '?')}")
    elif isinstance(models, list):
        for m in models:
            print(f"  {m.get('id', '?')}")
    else:
        print(f"  返回: {json.dumps(models, indent=2)[:500]}")
except Exception as e:
    print(f"  失败: {e}")

print()
print("=" * 60)
print("测试 2：尝试生成图片 (images/generations)")
print("=" * 60)
try:
    payload = {
        "model": "doubao-seed-2-1-pro-260628",
        "prompt": "a simple line drawing of an apple, black and white, minimal sketch style",
        "n": 1,
        "size": "512x512",
    }
    req = urllib.request.Request(
        'https://ark.cn-beijing.volces.com/api/v3/images/generations',
        data=json.dumps(payload).encode('utf-8'),
        headers={
            'Content-Type': 'application/json',
            'Authorization': f'Bearer {API_KEY}',
        },
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        result = json.loads(resp.read().decode('utf-8'))
    print(f"  成功: {json.dumps(result, indent=2)[:500]}")
except urllib.error.HTTPError as e:
    body = e.read().decode('utf-8', errors='replace')
    print(f"  HTTP {e.code}: {body[:500]}")
except Exception as e:
    print(f"  失败: {e}")

print()
print("=" * 60)
print("测试 3：尝试生成图片 (images/generations) - seed-2-1-image")
print("=" * 60)
try:
    payload = {
        "model": "doubao-seed-2-1-image-250628",
        "prompt": "a simple black and white line drawing of an apple, minimal sketch",
        "n": 1,
        "size": "512x512",
    }
    req = urllib.request.Request(
        'https://ark.cn-beijing.volces.com/api/v3/images/generations',
        data=json.dumps(payload).encode('utf-8'),
        headers={
            'Content-Type': 'application/json',
            'Authorization': f'Bearer {API_KEY}',
        },
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        result = json.loads(resp.read().decode('utf-8'))
    print(f"  成功!")
    if 'data' in result:
        for i, img in enumerate(result['data']):
            url = img.get('url', '')
            b64 = img.get('b64_json', '')
            if url:
                print(f"  图片URL: {url[:100]}")
            if b64:
                fname = f'test_gen_image_{i}.png'
                with open(fname, 'wb') as f:
                    f.write(base64.b64decode(b64))
                print(f"  已保存: {fname}")
    else:
        print(f"  返回: {json.dumps(result, indent=2)[:500]}")
except urllib.error.HTTPError as e:
    body = e.read().decode('utf-8', errors='replace')
    print(f"  HTTP {e.code}: {body[:500]}")
except Exception as e:
    print(f"  失败: {e}")

print()
print("=" * 60)
print("测试 4：试试 Stable Diffusion 兼容格式")
print("=" * 60)
try:
    payload = {
        "model": "doubao-seed-2-1-image-250628",
        "prompt": "simple line drawing of a cat, black and white, single continuous line",
        "n": 1,
        "size": "512x512",
        "response_format": "b64_json",
    }
    req = urllib.request.Request(
        'https://ark.cn-beijing.volces.com/api/v3/images/generations',
        data=json.dumps(payload).encode('utf-8'),
        headers={
            'Content-Type': 'application/json',
            'Authorization': f'Bearer {API_KEY}',
        },
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        result = json.loads(resp.read().decode('utf-8'))
    if 'data' in result:
        for i, img in enumerate(result['data']):
            b64 = img.get('b64_json', '')
            url = img.get('url', '')
            if b64:
                fname = f'test_cat_{i}.png'
                with open(fname, 'wb') as f:
                    f.write(base64.b64decode(b64))
                print(f"  已保存: {fname}")
            if url:
                print(f"  URL: {url[:100]}")
    else:
        print(f"  返回: {json.dumps(result, indent=2)[:500]}")
except urllib.error.HTTPError as e:
    body = e.read().decode('utf-8', errors='replace')
    print(f"  HTTP {e.code}: {body[:500]}")
except Exception as e:
    print(f"  失败: {e}")

print()
print("完成！")
