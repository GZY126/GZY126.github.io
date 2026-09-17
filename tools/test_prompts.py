import os
import json, urllib.request, base64, time

API_KEY = os.environ.get('SILICONFLOW_API_KEY', '')

def generate(prompt, topic, tag):
    """调用硅基流动 Kolors 生成图片"""
    payload = {
        "model": "Kwai-Kolors/Kolors",
        "prompt": prompt,
        "image_size": "1024x1024",
        "batch_size": 1,
        "num_inference_steps": 20,
        "guidance_scale": 7.5,
    }
    
    print(f'  [{tag}] 生成中...')
    req = urllib.request.Request(
        'https://api.siliconflow.cn/v1/images/generations',
        data=json.dumps(payload).encode('utf-8'),
        headers={
            'Content-Type': 'application/json',
            'Authorization': f'Bearer {API_KEY}',
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            result = json.loads(resp.read().decode('utf-8'))
    except Exception as e:
        print(f'  [{tag}] API调用失败: {e}')
        return
    
    if 'images' in result:
        for i, img in enumerate(result['images']):
            url = img.get('url', '')
            b64 = img.get('image', img.get('b64_json', ''))
            if url:
                try:
                    req2 = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
                    with urllib.request.urlopen(req2, timeout=60) as resp2:
                        img_data = resp2.read()
                    fname = f'test_{tag}_{topic}.png'
                    with open(fname, 'wb') as f:
                        f.write(img_data)
                    print(f'  [{tag}] [OK] 已保存: {fname} ({len(img_data)} bytes)')
                except Exception as e2:
                    print(f'  [{tag}] 下载失败: {e2}')
            elif b64:
                fname = f'test_{tag}_{topic}.png'
                with open(fname, 'wb') as f:
                    f.write(base64.b64decode(b64))
                print(f'  [{tag}] [OK] 已保存: {fname}')
    else:
        print(f'  [{tag}] 返回: {json.dumps(result, indent=2)[:300]}')


# ======================== 提示词测试集 ========================

topics = ['苹果', '小猫', '叶公好龙']

prompts = {
    # 方案1：英语详细版 - 强调 children's coloring book 风格
    'v1_en_coloring': lambda t: (
        f"A simple black and white line drawing of {t}, "
        "children's coloring book page style, pure clean outline only, no color, "
        "no shading, no fill, no gradient, no texture, no background details, "
        "thick uniform black lines on pure white background, minimal detail, "
        "single object centered, like a page from a coloring book for toddlers, "
        "simple enough for a 3-year-old to color"
    ),
    
    # 方案2：中文详细版 - 强调简笔画风格
    'v2_cn_jiandao': lambda t: (
        f"一幅{t}的简笔画，纯黑色线条，纯白色背景。"
        "极简风格，只有轮廓线，没有阴影，没有填充色块，没有渐变色，没有纹理。"
        "线条粗细均匀，简洁流畅，类似儿童简笔画教材中的插图。"
        "画面干净，主体居中，没有背景装饰。"
        "就像用一支黑色马克笔在白纸上随手画出的草图。"
    ),
    
    # 方案3：技术描述版 - 用绘图术语
    'v3_technical': lambda t: (
        f"Technical line drawing of {t}, wireframe style, "
        "vector line art, monochrome black lines on white, "
        "no raster shading, no halftone, no cross-hatching, "
        "outline only, SVG-like appearance, "
        "single stroke weight throughout, "
        "minimalist contour drawing, "
        "resembling a scientific illustration with only essential lines"
    ),
    
    # 方案4：stick figure / doodle 风格
    'v4_doodle': lambda t: (
        f"A quick doodle sketch of {t}, hand-drawn style, "
        "simple scribble lines, casual pen drawing, "
        "black ink on white paper, rough but recognizable outline, "
        "no erasing marks, no shading, no coloring, "
        "like someone quickly sketched it with a ballpoint pen, "
        "very simple and minimal, just a few strokes"
    ),
    
    # 方案5：one-line 一笔画风格
    'v5_oneline': lambda t: (
        f"A single continuous line drawing of {t}, one line art, "
        "minimalist one-line sketch, black contour on white, "
        "no lifting the pen, flowing elegant single stroke, "
        "no shading no fill no color, pure outline art, "
        "Picasso-style continuous line drawing, "
        "very simple recognizable silhouette"
    ),
    
    # 方案6：中文+英文混合，强调"不要素描"
    'v6_mixed': lambda t: (
        f"A {t} in simple line art style. Black outline only. No shading. No color. "
        f"NOT a realistic sketch. NOT a detailed drawing. "
        f"This should look like a simple icon or emoji outline, "
        f"just the essential shape of a {t} with minimal lines. "
        f"Like a pictogram or pictograph. "
        f"Clean black lines on pure white. Minimalist flat design. "
        f"Only the most basic recognizable contour. "
        f"NOT photorealistic, NOT a painting, NOT an illustration with details."
    ),
    
    # 方案7：反向提示词 + 正面约束
    'v7_negative_style': lambda t: (
        f"A minimalist black and white line icon of {t}. "
        f"Flat design, simple outline, no details, no texture, no depth. "
        f"This is NOT a realistic rendering, NOT a sketch with shading, "
        f"NOT a detailed illustration, NOT a photograph, NOT a 3D render. "
        f"It is just the simplest possible recognizable outline shape. "
        f"Like a traffic sign symbol or a restroom icon - pure silhouette in black lines. "
        f"White background, black lines only, very clean and simple."
    ),
}

# ======================== 运行测试 ========================
# 只测试"小猫"（节省 API 调用次数），看哪个 prompt 最像简笔画

test_topic = '小猫'

for tag, prompt_fn in prompts.items():
    print()
    print(f'{"="*60}')
    print(f'测试: {tag} - 主题: {test_topic}')
    print(f'{"="*60}')
    print(f'  Prompt: {prompt_fn(test_topic)[:150]}...')
    generate(prompt_fn(test_topic), test_topic, tag)
    time.sleep(2)  # 避免限流

print()
print('全部完成！请对比 test_v*_小猫.png 看哪个最像简笔画。')
