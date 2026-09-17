import os
import json, urllib.request

API_KEY = os.environ.get('ARK_API_KEY', '')

# 火山引擎 Seedream 可能有独立的端点（不是 /api/v3）
endpoints = [
    'https://ark.cn-beijing.volces.com/api/v3/images/generations',
    'https://visual.volcengineapi.com/',
    'https://open.volcengineapi.com/visual/',
]

for ep in endpoints:
    print(f"端点: {ep}")
    try:
        req = urllib.request.Request(
            ep,
            headers={'Authorization': f'Bearer {API_KEY}'},
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            print(f"  可访问: {resp.status}")
    except urllib.error.HTTPError as e:
        body = e.read().decode('utf-8', errors='replace')
        print(f"  HTTP {e.code}: {body[:100]}")
    except Exception as e:
        print(f"  失败: {e}")
    print()

# 你的 API Key 可能只绑定了文本模型端点，Seedream 需要单独创建推理接入点（Endpoint ID）
# 在火山引擎控制台：模型推理 → 创建推理接入点 → 选 Seedream → 生成端点ID（如 ep-2025xxxx）
# 然后调用: POST https://ark.cn-beijing.volces.com/api/v3/images/generations
# 使用端点ID作为 model 参数

print("=" * 50)
print("你需要做的：")
print("1. 打开 https://console.volcengine.com/ark")
print("2. 模型推理 → 创建推理接入点")
print("3. 选择 Seedream 5.0 模型")
print("4. 创建后会得到一个端点ID（如 ep-20250101xxxxxx）")
print("5. 把端点ID发给我，我帮你测试")
