import os
import json, urllib.request, base64, time

# 你需要去 https://siliconflow.cn 注册，获取 API Key
API_KEY = os.environ.get('SILICONFLOW_API_KEY', '')

print("=" * 60)
print("测试 1：列出可用模型")
print("=" * 60)
try:
    req = urllib.request.Request(
        'https://api.siliconflow.cn/v1/models',
        headers={'Authorization': f'Bearer {API_KEY}'}
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        models = json.loads(resp.read().decode('utf-8'))
    
    # 筛选图片生成模型
    if 'data' in models:
        for m in models['data']:
            mid = m.get('id', '')
            if any(kw in mid.lower() for kw in ['flux', 'sd', 'stable', 'kolors', 'image', 'sdxl']):
                print(f"  {mid}")
except Exception as e:
    print(f"  失败: {e}")

print()
print("=" * 60)
print("测试 2：Kolors 生成简笔画")
print("=" * 60)

payload = {
    "model": "Kwai-Kolors/Kolors",
    "prompt": "A simple black and white line drawing sketch of a cute cat, minimal outline style, single continuous line, no color, no shading, no fill, pure line art like a children's coloring book page",
    "image_size": "1024x1024",
    "batch_size": 1,
    "num_inference_steps": 20,
    "guidance_scale": 7.5,
}

try:
    req = urllib.request.Request(
        'https://api.siliconflow.cn/v1/images/generations',
        data=json.dumps(payload).encode('utf-8'),
        headers={
            'Content-Type': 'application/json',
            'Authorization': f'Bearer {API_KEY}',
        },
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        result = json.loads(resp.read().decode('utf-8'))
    
    if 'images' in result:
        for i, img in enumerate(result['images']):
            url = img.get('url', '')
            b64 = img.get('image', img.get('b64_json', ''))
            if b64:
                fname = f'test_kolors_cat_{i}.png'
                with open(fname, 'wb') as f:
                    f.write(base64.b64decode(b64))
                print(f"  [OK] 已保存: {fname}")
            if url:
                print(f"  [OK] URL: {url}")
                # 下载 URL
                try:
                    req2 = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
                    with urllib.request.urlopen(req2, timeout=60) as resp2:
                        img_data = resp2.read()
                    fname = f'test_kolors_cat_{i}.png'
                    with open(fname, 'wb') as f:
                        f.write(img_data)
                    print(f"  [OK] 从URL下载已保存: {fname}")
                except Exception as e2:
                    print(f"  下载URL失败: {e2}")
    else:
        print(f"  返回: {json.dumps(result, indent=2)[:500]}")
except Exception as e:
    print(f"  失败: {e}")

print()
print("=" * 60)
print("测试 3：FLUX.1-schnell 生成简笔画（推荐，免费额度多）")
print("=" * 60)

payload = {
    "model": "black-forest-labs/FLUX.1-schnell",
    "prompt": "simple black and white line drawing of an apple, minimal sketch, clean outline only, no color no fill no shading, pure line art",
    "image_size": "1024x1024",
    "batch_size": 1,
    "num_inference_steps": 4,  # schnell 只需 4 步
}

try:
    req = urllib.request.Request(
        'https://api.siliconflow.cn/v1/images/generations',
        data=json.dumps(payload).encode('utf-8'),
        headers={
            'Content-Type': 'application/json',
            'Authorization': f'Bearer {API_KEY}',
        },
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        result = json.loads(resp.read().decode('utf-8'))
    
    if 'images' in result:
        for i, img in enumerate(result['images']):
            url = img.get('url', '')
            b64 = img.get('image', img.get('b64_json', ''))
            if b64:
                fname = f'test_flux_apple_{i}.png'
                with open(fname, 'wb') as f:
                    f.write(base64.b64decode(b64))
                print(f"  [OK] 已保存: {fname}")
            if url:
                print(f"  [OK] URL: {url}")
                try:
                    req2 = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
                    with urllib.request.urlopen(req2, timeout=60) as resp2:
                        img_data = resp2.read()
                    fname = f'test_flux_apple_{i}.png'
                    with open(fname, 'wb') as f:
                        f.write(img_data)
                    print(f"  [OK] 从URL下载已保存: {fname}")
                except Exception as e2:
                    print(f"  下载URL失败: {e2}")
    else:
        print(f"  返回: {json.dumps(result, indent=2)[:500]}")
except Exception as e:
    print(f"  失败: {e}")

print()
print("完成！请打开生成的 PNG 文件查看效果。")
print("如果没有图片生成，说明 API Key 不对或需要先在 siliconflow.cn 注册获取。")
