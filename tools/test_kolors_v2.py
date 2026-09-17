import os
import json, urllib.request, base64, time

API_KEY = os.environ.get('SILICONFLOW_API_KEY', '')
ENDPOINT = 'https://api.siliconflow.cn/v1/images/generations'

# 不同风格的简笔画 prompt
tests = {
    # 直接用中文"简笔画"关键词
    'cn_simple': "纯黑色线条简笔画，一只小猫，只有轮廓线，无任何颜色填充，无阴影，无背景，白色底。儿童简笔画教材风格，就像用签字笔在白纸上随手画出来的线条。",
    
    # 强调 stick figure / doodle
    'doodle': "A cute doodle of a cat, stick figure style, simple black outline only, white background, no fill no color no shading. Like a child's drawing with a pen. Very minimal, just a few strokes.",
    
    # 强调 coloring book 风格
    'coloring_book': "A cat line art, coloring book page style, pure black and white, thick clean outlines, no fill, no shading, no color, empty inside the lines. Like a printable coloring sheet for kids.",
    
    # 强调 icon / clipart
    'clipart': "A simple cat clipart, black outline only, flat icon style, no color no gradient no fill, pure line art on white background. Minimalist logo style.",
    
    # 强调 whiteboard drawing
    'whiteboard': "A cat drawn on a whiteboard with a black marker, simple clean lines, no colors, no fill, no shading, just the outline. Very basic sketch.",
    
    # 中文 + 否定词
    'cn_negative': "极简线条画，一只猫，纯黑色轮廓，白色背景。不要素描，不要阴影，不要填充，不要上色，不要写实，不要细节。只要最简单的轮廓线。类似于幼儿园简笔画。",
}

for tag, prompt in tests.items():
    print(f"[{tag}] {prompt[:80]}...")
    payload = {
        "model": "Kwai-Kolors/Kolors",
        "prompt": prompt,
        "image_size": "1024x1024",
        "batch_size": 1,
        "num_inference_steps": 20,
        "guidance_scale": 7.5,
    }
    try:
        req = urllib.request.Request(
            ENDPOINT,
            data=json.dumps(payload).encode('utf-8'),
            headers={'Content-Type': 'application/json', 'Authorization': f'Bearer {API_KEY}'},
        )
        with urllib.request.urlopen(req, timeout=120) as resp:
            result = json.loads(resp.read().decode('utf-8'))
        
        for img in result.get('images', []):
            url = img.get('url', '')
            if url:
                req2 = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
                with urllib.request.urlopen(req2, timeout=60) as resp2:
                    data = resp2.read()
                fname = f'test_kolors_{tag}.png'
                with open(fname, 'wb') as f:
                    f.write(data)
                print(f"  [OK] {fname} ({len(data)} bytes)")
    except Exception as e:
        print(f"  [FAIL] {e}")
    time.sleep(2)

print("\n完成！对比 test_kolors_*.png 看哪个最像简笔画。")
