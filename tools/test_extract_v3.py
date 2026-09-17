import os
"""
测试方案3：用 OpenCV 提取轮廓 → 验证效果 → 生成坐标JSON
在电脑上验证算法正确性后，再移植到 Android
"""
import cv2
import numpy as np
import json

# 读取 Z-Image-Turbo 生成的图片
img = cv2.imread('test_cat_debug.png')
if img is None:
    print("图片不存在，先生成图片")
    import urllib.request
    API_KEY = os.environ.get('SILICONFLOW_API_KEY', '')
    payload = {
        'model': 'Tongyi-MAI/Z-Image-Turbo',
        'prompt': '极简简笔画，纯黑色单线条，白色背景，无填充无阴影，一只小猫，只有最基础的外形轮廓线',
        'image_size': '1024x1024', 'batch_size': 1, 'num_inference_steps': 4,
    }
    req = urllib.request.Request('https://api.siliconflow.cn/v1/images/generations',
        data=json.dumps(payload).encode('utf-8'),
        headers={'Content-Type': 'application/json', 'Authorization': f'Bearer {API_KEY}'})
    with urllib.request.urlopen(req, timeout=60) as resp:
        result = json.loads(resp.read().decode('utf-8'))
    url = result['images'][0]['url']
    req2 = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req2, timeout=30) as resp2:
        data = resp2.read()
    with open('test_cat_debug.png', 'wb') as f:
        f.write(data)
    img = cv2.imread('test_cat_debug.png')

h, w = img.shape[:2]
print(f"原图: {w}x{h}")

# 1. 灰度化
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

# 2. 自适应阈值二值化（对简笔画效果比固定阈值好）
binary = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                cv2.THRESH_BINARY_INV, 15, 5)

# 3. 形态学闭运算：先膨胀再腐蚀，连接断裂线条
kernel = np.ones((2, 2), np.uint8)
closed = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel, iterations=2)

# 4. 骨骼化/细化（让粗线条变细，更适合提取中心线）
# 用距离变换 + 阈值实现骨架化
from scipy import ndimage
dist = ndimage.distance_transform_edt(closed)
skel = (dist > 1.5).astype(np.uint8) * 255

# 保存中间结果
cv2.imwrite('debug_binary.png', binary)
cv2.imwrite('debug_closed.png', closed)
cv2.imwrite('debug_skeleton.png', skel)

# 5. 提取骨骼线上的点
pts = np.argwhere(skel > 0)
if len(pts) == 0:
    # 兜底：直接用二值图
    pts = np.argwhere(binary > 0)

print(f"骨骼点: {len(pts)} 个")

# 6. 用 DBSCAN 聚类，把点分成不同笔画
from sklearn.cluster import DBSCAN
# 每 4 个点取 1 个，加速聚类
sample_idx = np.random.choice(len(pts), min(len(pts), 8000), replace=False)
sample_pts = pts[sample_idx]

clustering = DBSCAN(eps=8, min_samples=5).fit(sample_pts.astype(float))
labels = clustering.labels_

print(f"聚类: {len(set(labels)) - (1 if -1 in labels else 0)} 个簇")

# 7. 对每个簇内的点排序，生成笔画
strokes = []
for label in set(labels):
    if label == -1:
        continue
    mask = labels == label
    cluster_pts = sample_pts[mask]
    if len(cluster_pts) < 10:
        continue
    
    # 按 y 为主、x 为辅排序
    sorted_idx = np.lexsort((cluster_pts[:, 1], cluster_pts[:, 0]))
    ordered = cluster_pts[sorted_idx]
    
    # 每 3 个点取 1 个（降采样）
    ordered = ordered[::3]
    
    # 归一化 + 转为 x,y 格式
    points = [{"x": float(p[1]) / w, "y": float(p[0]) / h} for p in ordered]
    strokes.append(points)

# 按点数排序
strokes.sort(key=len, reverse=True)

# 8. 保存 JSON
result = {
    "name": "cat",
    "strokes": [{"points": s, "closePath": False} for s in strokes[:30]]
}

with open('test_cat_coords.json', 'w', encoding='utf-8') as f:
    json.dump(result, f, ensure_ascii=False)

total_pts = sum(len(s['points']) for s in result['strokes'])
print(f"输出: {len(result['strokes'])} 条笔画, {total_pts} 个坐标点")
print(f"已保存: test_cat_coords.json")
print(f"中间结果: debug_binary.png, debug_closed.png, debug_skeleton.png")
