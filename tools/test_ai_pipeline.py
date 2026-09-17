import os
"""
SimpleDrawBot AI 管线测试脚本
在电脑上独立测试三个环节，无需 Android 设备

用法：
  python test_ai_pipeline.py

会依次测试：
  1. AI API 调用 → 看模型返回了什么
  2. SVG URL 下载 → 看下载的 SVG 内容
  3. SVG 解析 → 看解析出的坐标是否合理

修改下面 CONFIG 区域即可适配你的 API。
"""

import json
import re
import sys
import urllib.request
import urllib.error

# ======================== 配置 ========================
# 方案1：豆包 API
CONFIG_DOUBAO = {
    "endpoint": "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
    "api_key": os.environ.get('ARK_API_KEY', ''),
    "model": "doubao-seed-2-1-pro-260628",
}

# 方案2：DeepSeek（tbnx 中转）
CONFIG_DEEPSEEK = {
    "endpoint": "https://tbnx.plus7.plus/v1/chat/completions",
    "api_key": os.environ.get('SILICONFLOW_API_KEY', ''),
    "model": "deepseek-chat",
}

# 当前使用的配置（改这里切换）
CONFIG = CONFIG_DEEPSEEK  # DeepSeek 比豆包快很多

# 测试用的绘画主题
TEST_TOPICS = ["苹果", "小猫", "叶公好龙"]

SYSTEM_PROMPT = """你是一位简笔画资源助手。用户会描述一个事物，你需要找到一个能直接下载的简笔画SVG文件的URL。

## 核心要求
1. 返回JSON格式：{"name":"名称","svg_url":"https://..."}
2. svg_url 必须是可直接下载的 .svg 文件地址（不是网页地址）
3. 优先返回 svgrepo.com 或 openclipart.org 上的SVG资源
4. 只返回JSON，不要任何解释。"""

def call_ai(topic):
    """测试1：调用 AI API"""
    user_prompt = f'请为"{topic}"找一个简笔画SVG文件URL。返回JSON格式：{{"name":"英文名","svg_url":"https://..."}}。只返回JSON，不要任何解释。'
    payload = {
        "model": CONFIG["model"],
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt}
        ],
        "temperature": 0.3,
    }
    try:
        req = urllib.request.Request(
            CONFIG["endpoint"],
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {CONFIG['api_key']}",
            },
        )
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        content = data["choices"][0]["message"]["content"]
        
        # 提取 JSON
        json_match = re.search(r'```(?:json)?\s*([\s\S]*?)```', content)
        json_str = json_match.group(1) if json_match else content.strip()
        
        try:
            ai_result = json.loads(json_str)
            return True, content, ai_result.get("name", ""), ai_result.get("svg_url", "")
        except json.JSONDecodeError:
            return False, content, "", ""
    except Exception as e:
        return False, str(e), "", ""


def download_svg(url):
    """测试2：下载 SVG 文件"""
    if not url:
        return False, "无 URL", ""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            svg_content = resp.read().decode("utf-8", errors="replace")
        is_svg = "<svg" in svg_content.lower() or "<path" in svg_content.lower()
        return is_svg, f"下载成功 ({len(svg_content)}字符)", svg_content
    except urllib.error.HTTPError as e:
        return False, f"HTTP {e.code}", ""
    except Exception as e:
        return False, str(e), ""


def parse_svg(svg_content):
    """测试3：解析 SVG 坐标"""
    if not svg_content:
        return {"strokes": 0, "points": 0, "min_x": 0, "max_x": 0, "min_y": 0, "max_y": 0, "issues": ["无内容"]}
    
    # viewBox
    viewbox_match = re.search(r'viewBox\s*=\s*["\']([\d.\-\s]+)["\']', svg_content, re.I)
    if viewbox_match:
        parts = viewbox_match.group(1).strip().split()
        vb = [float(p) for p in parts]
    else:
        wm = re.search(r'width\s*=\s*["\'](\d+)', svg_content, re.I)
        hm = re.search(r'height\s*=\s*["\'](\d+)', svg_content, re.I)
        vb = [0, 0, float(wm.group(1)) if wm else 100, float(hm.group(1)) if hm else 100]
    
    scale_x = 1.0 / vb[2] if vb[2] > 0 else 0.01
    scale_y = 1.0 / vb[3] if vb[3] > 0 else 0.01
    offset_x = -vb[0] * scale_x
    offset_y = -vb[1] * scale_y
    
    paths = re.findall(r'<path[^>]*\sd\s*=\s*["\']([^"\']+)["\']', svg_content, re.I)
    polygons = re.findall(r'<polygon[^>]*\spoints\s*=\s*["\']([^"\']+)["\']', svg_content, re.I)
    
    total_strokes, total_points = 0, 0
    min_x, max_x, min_y, max_y = 1.0, 0.0, 1.0, 0.0
    
    for d in paths:
        nums = [float(x) for x in re.findall(r'[-\d.]+', d)]
        pts = []
        for i in range(0, len(nums) - 1, 2):
            x = max(0, min(1, nums[i] * scale_x + offset_x))
            y = max(0, min(1, nums[i + 1] * scale_y + offset_y))
            pts.append((x, y))
            min_x, max_x = min(min_x, x), max(max_x, x)
            min_y, max_y = min(min_y, y), max(max_y, y)
        if len(pts) >= 2:
            total_strokes += 1
            total_points += len(pts)
    
    for pts_str in polygons:
        nums = [float(x) for x in re.findall(r'[-\d.]+', pts_str)]
        pts = []
        for i in range(0, len(nums) - 1, 2):
            x = max(0, min(1, nums[i] * scale_x + offset_x))
            y = max(0, min(1, nums[i + 1] * scale_y + offset_y))
            pts.append((x, y))
            min_x, max_x = min(min_x, x), max(max_x, x)
            min_y, max_y = min(min_y, y), max(max_y, y)
        if len(pts) >= 2:
            total_strokes += 1
            total_points += len(pts)
    
    issues = []
    if total_strokes == 0:
        issues.append("没有有效笔画")
    if total_points < 10:
        issues.append(f"点数太少({total_points})")
    if max_x - min_x < 0.1:
        issues.append(f"x范围太窄({max_x-min_x:.2f})")
    if max_y - min_y < 0.1:
        issues.append(f"y范围太窄({max_y-min_y:.2f})")
    
    return {"strokes": total_strokes, "points": total_points,
            "min_x": min_x, "max_x": max_x, "min_y": min_y, "max_y": max_y,
            "viewbox": vb, "paths": len(paths), "polygons": len(polygons),
            "issues": issues}


# ======================== 主循环 ========================
for idx, topic in enumerate(TEST_TOPICS):
    print("=" * 60)
    print(f"测试 {idx+1}/{len(TEST_TOPICS)}: [{topic}]")
    print("=" * 60)
    print(f"Endpoint: {CONFIG['endpoint']}")
    print(f"Model: {CONFIG['model']}")
    print()
    
    # 测试1
    print("[1] AI API 调用...")
    ok, raw, name, svg_url = call_ai(topic)
    if not ok:
        print(f"    ❌ 失败: {raw[:200]}")
        print()
        continue
    print(f"    ✅ name={name}, url={svg_url[:80]}...")
    
    # 测试2
    print(f"[2] 下载 SVG: {svg_url[:80]}...")
    is_svg, msg, svg_content = download_svg(svg_url)
    if not is_svg:
        print(f"    ❌ {msg}")
        print()
        continue
    print(f"    ✅ {msg}, 包含<svg>=True")
    
    # 测试3
    print(f"[3] 解析 SVG 坐标...")
    result = parse_svg(svg_content)
    print(f"    viewBox={result['viewbox']}, path={result['paths']}个, polygon={result['polygons']}个")
    print(f"    笔画={result['strokes']}条, 坐标点={result['points']}个")
    print(f"    范围: x=[{result['min_x']:.3f}, {result['max_x']:.3f}], y=[{result['min_y']:.3f}, {result['max_y']:.3f}]")
    if result["issues"]:
        for issue in result["issues"]:
            print(f"    ⚠️  {issue}")
    else:
        print(f"    ✅ 坐标正常")
    print()

print("=" * 60)
print("测试完成")
print("=" * 60)
