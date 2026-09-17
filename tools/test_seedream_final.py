import os
import json, urllib.request, base64

API_KEY = os.environ.get('ARK_API_KEY', '')
ENDPOINT = 'https://ark.cn-beijing.volces.com/api/v3/images/generations'
MODEL = 'doubao-seedream-5-0-pro-260628'

prompts = {
    'cat': "极简简笔画，一只小猫，纯黑色单线条轮廓，白色背景，无填充无阴影，儿童简笔画教材风格，只有最简单的几笔勾勒出猫的外形",
    'apple': "极简简笔画，一个苹果，纯黑色单线条轮廓，白色背景，无填充无阴影，只画最基础的外形加一个梗",
}

for tag, prompt in prompts.items():
    print(f"[{tag}] 生成中...")
    payload = {
        "model": MODEL,
        "prompt": prompt,
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
                if url:
                    req2 = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
                    with urllib.request.urlopen(req2, timeout=60) as resp2:
                        data = resp2.read()
                    fname = f'seedream_{tag}.png'
                    with open(fname, 'wb') as f:
                        f.write(data)
                    print(f"  [OK] {fname} ({len(data)} bytes)")
                elif b64:
                    fname = f'seedream_{tag}.png'
                    with open(fname, 'wb') as f:
                        f.write(base64.b64decode(b64))
                    print(f"  [OK] {fname}")
        else:
            print(f"  返回: {json.dumps(result)[:300]}")
    except Exception as e:
        print(f"  [FAIL] {e}")
    print()

print("完成！")
