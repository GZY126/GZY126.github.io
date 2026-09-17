import os
import json, re, urllib.request, time

CONFIG = {
    'endpoint': 'https://ark.cn-beijing.volces.com/api/v3/chat/completions',
    'api_key': os.environ.get('ARK_API_KEY', ''),
    'model': 'doubao-seed-2-1-pro-260628',
}

topics = ['苹果', '小猫', '叶公好龙']

# 改进后的提示词——强调简笔画线条风格
system_prompt = """你是一个简笔画SVG资源查找器。用户会描述一个事物，你需要找一个单线条简笔画（line drawing / doodle / sketch）SVG文件的直接下载URL。

## 严格要求
1. 必须是单线条简笔画风格，不要填充色块、不要图标、不要logo、不要彩色插画
2. 适合的SVG特征：只有黑色轮廓线、stroke线条、无fill或fill=none、类似手绘草图
3. 返回JSON格式：{"name":"英文名","svg_url":"https://..."}
4. svg_url 必须是可直接下载的.svg文件地址
5. 只返回JSON，不要任何解释。"""

for topic in topics:
    print(f'处理: {topic}')
    payload = {
        'model': CONFIG['model'],
        'messages': [
            {'role': 'system', 'content': system_prompt},
            {'role': 'user', 'content': f'请为"{topic}"找一个单线条简笔画SVG下载URL。只要线条画风格，不要填充图标。返回JSON。'}
        ],
        'temperature': 0.3,
    }
    
    print(f'  调用豆包 API...')
    req = urllib.request.Request(
        CONFIG['endpoint'],
        data=json.dumps(payload).encode('utf-8'),
        headers={
            'Content-Type': 'application/json',
            'Authorization': f'Bearer {CONFIG["api_key"]}',
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            data = json.loads(resp.read().decode('utf-8'))
    except Exception as e:
        print(f'  API 调用失败: {e}')
        continue
    
    content = data['choices'][0]['message']['content']
    print(f'  原始返回: {content[:300]}')
    
    m = re.search(r'```(?:json)?\s*([\s\S]*?)```', content)
    js = m.group(1) if m else content.strip()
    
    try:
        result = json.loads(js)
        url = result.get('svg_url', '')
        name = result.get('name', '')
        print(f'  解析: name={name}, url={url}')
    except json.JSONDecodeError:
        print(f'  不是有效JSON: {js[:100]}')
        continue
    
    if not url:
        print(f'  无URL')
        continue
    
    time.sleep(2)
    try:
        req2 = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req2, timeout=30) as resp2:
            svg = resp2.read()
        fname = f'test_svg_{topic}.svg'
        with open(fname, 'wb') as f:
            f.write(svg)
        
        svg_text = svg.decode('utf-8', errors='replace')
        paths = len(re.findall(r'<path[^>]*\sd\s*=', svg_text, re.I))
        # 检查是否有填充
        has_fill = 'fill=' in svg_text and 'fill="none"' not in svg_text and 'fill:none' not in svg_text
        vb_match = re.search(r'viewBox\s*=\s*["\']([^"\']+)["\']', svg_text, re.I)
        vb = vb_match.group(1) if vb_match else '未知'
        
        print(f'  [OK] {fname} ({len(svg)} bytes)')
        print(f'  viewBox={vb}, path={paths}个, 有填充色块={has_fill}')
        if has_fill:
            print(f'  !! 这个SVG有填充色块，不是纯线条画')
    except Exception as e:
        print(f'  下载失败: {e}')
    
    print()
