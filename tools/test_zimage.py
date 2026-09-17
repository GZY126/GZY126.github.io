import os
import json, urllib.request, time

API_KEY = os.environ.get('SILICONFLOW_API_KEY', '')
ENDPOINT = 'https://api.siliconflow.cn/v1/images/generations'

models = ['Tongyi-MAI/Z-Image-Turbo', 'Tongyi-MAI/Z-Image']

for model in models:
    print(f"测试: {model}")
    t0 = time.time()
    payload = {
        "model": model,
        "prompt": "极简简笔画，纯黑色单线条，白色背景，无填充无阴影，一只小猫，儿童简笔画风格，只有几笔简单轮廓线",
        "image_size": "1024x1024",
        "batch_size": 1,
        "num_inference_steps": 4,
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
        with urllib.request.urlopen(req, timeout=60) as resp:
            result = json.loads(resp.read().decode('utf-8'))
        elapsed = time.time() - t0
        
        for img in result.get('images', []):
            url = img.get('url', '')
            if url:
                req2 = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
                with urllib.request.urlopen(req2, timeout=30) as resp2:
                    data = resp2.read()
                fname = f'test_zimage_{model.replace("/","_")}.png'
                with open(fname, 'wb') as f:
                    f.write(data)
                print(f"  [OK] {fname} ({len(data)} bytes) 耗时: {elapsed:.1f}s")
    except Exception as e:
        elapsed = time.time() - t0
        print(f"  [FAIL] {e} (耗时: {elapsed:.1f}s)")
    print()
