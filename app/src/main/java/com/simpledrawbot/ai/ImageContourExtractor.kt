package com.simpledrawbot.ai

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.simpledrawbot.model.Stroke
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.*
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 基于 OpenCV 的图像轮廓提取器 v1.5
 * 流程：Bitmap→Mat→灰度→高斯模糊→自适应二值化→闭运算→骨架细化→findContours→
 *       DouglasPeucker→碎线过滤→断点合并→归一化
 *
 * 新增特性：
 * - Zhang-Suen 骨架细化（解决粗线双轮廓问题）
 * - 高斯模糊预处理（减少锯齿噪点）
 * - 碎线长度过滤（去除短碎杂线）
 * - 断点合并（连接相邻笔画）
 * - 单线模式开关
 */
object ImageContourExtractor {

    private const val TAG = "ContourExtractor"
    private const val MAX_SIZE = 512

    private data class ExtractParams(
        val minArea: Int,
        val minLength: Double,          // 新增：最小轮廓周长
        val dpEpsilon: Double,
        val maxStrokes: Int,
        val adaptiveBlockSize: Int,
        val adaptiveC: Double,
        val closeKernelSize: Int,
        val closeIterations: Int,
        val useSkeleton: Boolean        // 新增：是否使用骨架细化
    )

    private val PARAMS_SIMPLE = ExtractParams(
        minArea = 20, minLength = 15.0, dpEpsilon = 3.0, maxStrokes = 50,
        adaptiveBlockSize = 15, adaptiveC = 8.0,
        closeKernelSize = 3, closeIterations = 1, useSkeleton = true
    )
    private val PARAMS_COMPLEX = ExtractParams(
        minArea = 10, minLength = 10.0, dpEpsilon = 1.5, maxStrokes = 200,
        adaptiveBlockSize = 13, adaptiveC = 5.0,
        closeKernelSize = 3, closeIterations = 1, useSkeleton = true
    )
    private val PARAMS_MAX = ExtractParams(
        minArea = 5, minLength = 5.0, dpEpsilon = 0.8, maxStrokes = 800,
        adaptiveBlockSize = 9, adaptiveC = 3.0,
        closeKernelSize = 3, closeIterations = 2, useSkeleton = false
    )

    /** 全局单线模式开关（可由外部切换） */
    @Volatile var singleLineMode: Boolean = true
    /** 细节程度 0~100，默认60。0=极简只留主体，100=全部保留 */
    @Volatile var detailLevel: Int = 80

    fun initOpenCV() {
        try {
            System.loadLibrary("opencv_java4")
            Log.d(TAG, "OpenCV 加载成功")
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV 加载失败", e)
        }
    }

    /**
     * 自动裁边：去除四周纯色边距，只保留有内容的图片区域
     */
    fun trimBorder(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 10 || h <= 10) return bitmap

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 找左边界
        var left = 0
        outer@ for (x in 0 until w) {
            for (y in 0 until h) {
                val c = pixels[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // 非纯白色/浅灰色像素
                if (r < 240 || g < 240 || b < 240) {
                    left = x; break@outer
                }
            }
        }

        // 找右边界
        var right = w - 1
        outer@ for (x in w - 1 downTo 0) {
            for (y in 0 until h) {
                val c = pixels[y * w + x]
                if (((c shr 16) and 0xFF) < 240 || ((c shr 8) and 0xFF) < 240 || (c and 0xFF) < 240) {
                    right = x; break@outer
                }
            }
        }

        // 找上边界
        var top = 0
        outer@ for (y in 0 until h) {
            for (x in left..right) {
                val c = pixels[y * w + x]
                if (((c shr 16) and 0xFF) < 240 || ((c shr 8) and 0xFF) < 240 || (c and 0xFF) < 240) {
                    top = y; break@outer
                }
            }
        }

        // 找下边界
        var bottom = h - 1
        outer@ for (y in h - 1 downTo 0) {
            for (x in left..right) {
                val c = pixels[y * w + x]
                if (((c shr 16) and 0xFF) < 240 || ((c shr 8) and 0xFF) < 240 || (c and 0xFF) < 240) {
                    bottom = y; break@outer
                }
            }
        }

        // 保护：裁掉边距不超过20%，裁后不小于原图60%
        val cropW = (right - left + 1).coerceAtMost(w).coerceAtLeast((w * 0.6).toInt()).coerceAtLeast(10)
        val cropH = (bottom - top + 1).coerceAtMost(h).coerceAtLeast((h * 0.6).toInt()).coerceAtLeast(10)
        val safeLeft = (left % w).coerceAtMost((w * 0.2).toInt())
        val safeTop = (top % h).coerceAtMost((h * 0.2).toInt())
        if (cropW >= w * 0.95 && cropH >= h * 0.95) return bitmap


        Log.d(TAG, "自动裁边: ${w}x${h} → ${cropW}x${cropH} (left=$left top=$top)")
        return Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
    }

    fun extract(
        bitmap: Bitmap,
        complexity: AiSketchGenerator.Complexity = AiSketchGenerator.Complexity.SIMPLE
    ): List<Stroke> {
        val params = when (complexity) {
            AiSketchGenerator.Complexity.SIMPLE -> PARAMS_SIMPLE
            AiSketchGenerator.Complexity.COMPLEX -> PARAMS_COMPLEX
            AiSketchGenerator.Complexity.MAX -> PARAMS_MAX
        }

        // 第一次：正常参数提取
        val result = extractWithParams(bitmap, params, aggressive = true)
        if (result.isNotEmpty() && !isExtractionBad(result, bitmap)) {
            return result
        }

        // 兜底重试：关闭开运算 + 更宽松参数
        Log.d(TAG, "第一次提取失败(${result.size}笔)，自动重试温和参数")
        return extractWithParams(bitmap, params, aggressive = false)
    }

    /**
     * 判断提取结果是否太差（笔画数太少或主体缺失）
     */
    private fun isExtractionBad(strokes: List<Stroke>, bitmap: Bitmap): Boolean {
        if (strokes.size < 10) return true
        val maxBbox = strokes.maxOfOrNull { s ->
            val xs = s.points.map { it.x }; val ys = s.points.map { it.y }
            ((xs.maxOrNull() ?: 0f) - (xs.minOrNull() ?: 0f)) *
            ((ys.maxOrNull() ?: 0f) - (ys.minOrNull() ?: 0f))
        } ?: 0f
        return maxBbox < 0.05f // 最大轮廓不到图片5%面积
    }

    /**
     * 核心提取逻辑
     * @param aggressive true=正常参数(开运算+高adaptiveC), false=温和参数(关闭开运算+低adaptiveC)
     */
    private fun extractWithParams(
        bitmap: Bitmap, params: ExtractParams, aggressive: Boolean
    ): List<Stroke> {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        val scale = if (maxOf(src.width(), src.height()) > MAX_SIZE) {
            MAX_SIZE.toDouble() / maxOf(src.width(), src.height())
        } else 1.0
        val resized = Mat()
        if (scale < 1.0) {
            Imgproc.resize(src, resized, Size(0.0, 0.0), scale, scale, Imgproc.INTER_AREA)
        } else {
            src.copyTo(resized)
        }
        src.release()

        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGB2GRAY)

        // 参数自适应：原图窄边 < 300px → 降低blockSize和C
        val isSmallImage = minOf(resized.width(), resized.height()) < 300
        val blockSize = if (isSmallImage) (params.adaptiveBlockSize / 2).coerceAtLeast(7) else params.adaptiveBlockSize
        val adaptiveC = if (isSmallImage) (params.adaptiveC / 2).coerceAtLeast(1.0) else params.adaptiveC

        Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.5)

        // 自适应二值化
        val binary = Mat()
        Imgproc.adaptiveThreshold(gray, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV,
            blockSize, adaptiveC)
        gray.release()

        // 三路独立参数映射（共用detailLevel滑块）
        val ratio = detailLevel / 100f

        // 1. 开运算（噪点强度）：detail<20→4, <40→3, <60→2, <80→1, ≥80→关闭
        val openSize = if (aggressive && detailLevel < 80) {
            when { detailLevel < 20 -> 4; detailLevel < 40 -> 3; detailLevel < 60 -> 2; else -> 1 }
        } else 0
        if (openSize >= 2) {
            val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,
                Size(openSize.toDouble(), openSize.toDouble()))
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, openKernel)
        }

        // 2. 闭运算（连线强度）：detail<20→3, <40→2, <60→1, ≥60→关闭
        val closeSize = if (detailLevel < 60) {
            when { detailLevel < 20 -> 3; detailLevel < 40 -> 2; else -> 1 }
        } else 0
        if (closeSize >= 2) {
            val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,
                Size(closeSize.toDouble(), closeSize.toDouble()))
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, closeKernel,
                Point(-1.0, -1.0), 1)
        }

        // 骨架细化：仅detailLevel<100时微膨胀保护小轮廓
        val useSkeleton = singleLineMode && params.useSkeleton
        var processed = if (useSkeleton) {
            if (detailLevel < 100) {
                val dilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(2.0, 2.0))
                Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_DILATE, dilateKernel)
            }
            val skeleton = zhangSuenThinning(binary)
            binary.release(); skeleton
        } else binary

        var contours = ArrayList<MatOfPoint>()
        var hierarchy = Mat()
        Imgproc.findContours(processed, contours, hierarchy,
            Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)

        // 回退：骨架细化后0条 → 非骨架重试
        if (contours.isEmpty() && useSkeleton) {
            Log.d(TAG, "骨架细化0条，回退非骨架")
            processed.release()
            val gray2 = Mat()
            Imgproc.cvtColor(resized, gray2, Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray2, gray2, Size(3.0, 3.0), 0.5)
            val binary2 = Mat()
            Imgproc.adaptiveThreshold(gray2, binary2, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV,
                blockSize, (adaptiveC / 2).coerceAtLeast(1.0))
            gray2.release()
            val fbKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(2.0, 2.0))
            Imgproc.morphologyEx(binary2, binary2, Imgproc.MORPH_CLOSE, fbKernel,
                Point(-1.0, -1.0), 1)
            processed = binary2
            contours = ArrayList()
            hierarchy.release(); hierarchy = Mat()
            Imgproc.findContours(processed, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
        }

        // OTSU 兜底
        if (contours.isEmpty()) {
            Log.d(TAG, "回退OTSU")
            processed.release()
            val gray3 = Mat()
            Imgproc.cvtColor(resized, gray3, Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray3, gray3, Size(5.0, 5.0), 1.0)
            val binary3 = Mat()
            Imgproc.threshold(gray3, binary3, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
            gray3.release()
            val fbKernel2 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(2.0, 2.0))
            Imgproc.morphologyEx(binary3, binary3, Imgproc.MORPH_CLOSE, fbKernel2,
                Point(-1.0, -1.0), 1)
            processed = binary3
            contours = ArrayList()
            hierarchy.release(); hierarchy = Mat()
            Imgproc.findContours(processed, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
        }

        Log.d(TAG, "aggressive=$aggressive skeleton=$useSkeleton block=$blockSize C=$adaptiveC → ${contours.size}条轮廓")

        val w = resized.width().toFloat(); val h = resized.height().toFloat()
        resized.release(); processed.release()

        // minArea：detailLevel线性映射，100→1像素（全量保留）
        val effectiveMinArea = ((1f - ratio) * 49 + 1).toInt().coerceAtLeast(1).toDouble()
        // 伪闭合过滤：内轮廓(level≥1)面积<20像素的直接丢弃
        val MIN_HOLE_AREA = 20.0
        val validContours = contours
            .mapIndexed { idx, c -> Triple(idx, c, getHierarchyLevel(hierarchy, idx)) }
            .filter { (_, c, level) ->
                val area = Imgproc.contourArea(c)
                c.rows() >= 3 && area >= effectiveMinArea &&
                Imgproc.arcLength(MatOfPoint2f(*c.toArray()), false) >= params.minLength &&
                !(level >= 1 && area < MIN_HOLE_AREA) // 伪闭合丢弃
            }
            .sortedByDescending { (_, c, _) -> Imgproc.contourArea(c) }
            .take(params.maxStrokes)

        val strokesWithMeta = mutableListOf<Triple<Stroke, Float, Int>>()
        // 超精细模式：detailLevel≥90时dpEpsilon降为0.3倍
        val dpEps = when {
            !aggressive -> params.dpEpsilon * 0.6
            detailLevel >= 90 -> params.dpEpsilon * 0.3
            detailLevel >= 70 -> params.dpEpsilon * 0.6
            else -> params.dpEpsilon
        }
        for ((_, contour, level) in validContours) {
            val approx = MatOfPoint2f()
            contour.convertTo(approx, CvType.CV_32FC2)
            val simplified = MatOfPoint2f()
            Imgproc.approxPolyDP(approx, simplified, dpEps, false)
            approx.release()
            val pts = simplified.toList(); simplified.release()
            if (pts.size < 2) continue
            val normalized = pts.map { PointF(it.x.toFloat() / w, it.y.toFloat() / h) }
            val isClosed = normalized.size >= 3 &&
                hypot((normalized.first().x - normalized.last().x).toDouble(),
                      (normalized.first().y - normalized.last().y).toDouble()) < 0.03
            strokesWithMeta.add(Triple(Stroke(normalized, isClosed), Imgproc.contourArea(contour).toFloat(), level))
        }
        for (c in contours) c.release()
        hierarchy.release()

        Log.d(TAG, "简化后 ${strokesWithMeta.size} 条笔画")

        val filtered = filterByDetailLevelWithHierarchy(strokesWithMeta)
        val merged = mergeNearbyEndpoints(filtered)
        Log.d(TAG, "细节筛选+合并后 ${merged.size} 条笔画")
        return merged
    }

    /** 计算轮廓在层级树中的深度（通过 hierarchy Mat 逐轮廓访问） */
    private fun getHierarchyLevel(hierarchy: Mat, index: Int): Int {
        if (hierarchy.rows() <= 0 || index < 0) return 0
        var level = 0; var currentIdx = index
        while (level < 10) {
            // hierarchy 格式: [1, contoursCount, 4]，每行4个值 [next, prev, child, parent]
            val row = hierarchy.get(0, currentIdx)
            if (row == null || row.size < 4) break
            val parentIdx = row[3].toInt()
            if (parentIdx < 0) break
            currentIdx = parentIdx; level++
        }
        return level
    }

    // ================================================================
    //  Zhang-Suen 骨架细化算法
    // ================================================================

    private fun zhangSuenThinning(src: Mat): Mat {
        val img = Mat()
        src.copyTo(img)
        var rows = img.rows(); var cols = img.cols()

        // 将图像转为 0/1 矩阵
        val data = ByteArray(rows * cols)
        img.get(0, 0, data)

        var changed: Boolean
        do {
            changed = false
            // 子迭代 1
            val toRemove1 = mutableListOf<Int>()
            for (y in 1 until rows - 1) {
                for (x in 1 until cols - 1) {
                    val idx = y * cols + x
                    if (data[idx].toInt() != 255) continue
                    val p2 = data[(y-1)*cols + x].toInt() == 255
                    val p3 = data[(y-1)*cols + x+1].toInt() == 255
                    val p4 = data[y*cols + x+1].toInt() == 255
                    val p5 = data[(y+1)*cols + x+1].toInt() == 255
                    val p6 = data[(y+1)*cols + x].toInt() == 255
                    val p7 = data[(y+1)*cols + x-1].toInt() == 255
                    val p8 = data[y*cols + x-1].toInt() == 255
                    val p9 = data[(y-1)*cols + x-1].toInt() == 255

                    val neighbors = listOf(p2, p3, p4, p5, p6, p7, p8, p9)
                    val b = neighbors.count { it }
                    val a = (0 until 7).count { !neighbors[it] && neighbors[(it+1)%8] }

                    if (b in 2..6 && a == 1 && !(p2 && p4 && p6) && !(p4 && p6 && p8)) {
                        toRemove1.add(idx)
                        changed = true
                    }
                }
            }
            for (idx in toRemove1) data[idx] = 0

            // 子迭代 2
            val toRemove2 = mutableListOf<Int>()
            for (y in 1 until rows - 1) {
                for (x in 1 until cols - 1) {
                    val idx = y * cols + x
                    if (data[idx].toInt() != 255) continue
                    val p2 = data[(y-1)*cols + x].toInt() == 255
                    val p3 = data[(y-1)*cols + x+1].toInt() == 255
                    val p4 = data[y*cols + x+1].toInt() == 255
                    val p5 = data[(y+1)*cols + x+1].toInt() == 255
                    val p6 = data[(y+1)*cols + x].toInt() == 255
                    val p7 = data[(y+1)*cols + x-1].toInt() == 255
                    val p8 = data[y*cols + x-1].toInt() == 255
                    val p9 = data[(y-1)*cols + x-1].toInt() == 255

                    val neighbors = listOf(p2, p3, p4, p5, p6, p7, p8, p9)
                    val b = neighbors.count { it }
                    val a = (0 until 7).count { !neighbors[it] && neighbors[(it+1)%8] }

                    if (b in 2..6 && a == 1 && !(p2 && p4 && p8) && !(p2 && p6 && p8)) {
                        toRemove2.add(idx)
                        changed = true
                    }
                }
            }
            for (idx in toRemove2) data[idx] = 0
        } while (changed)

        img.put(0, 0, data)
        return img
    }

    // ================================================================
    //  断点合并：连接端点距离 < 阈值的笔画
    // ================================================================

    private fun mergeNearbyEndpoints(strokes: List<Stroke>): List<Stroke> {
        if (strokes.size < 2) return strokes
        val threshold = 0.015f // 归一化空间中的距离阈值
        val used = BooleanArray(strokes.size)
        val result = mutableListOf<Stroke>()

        for (i in strokes.indices) {
            if (used[i]) continue
            var current = strokes[i]
            used[i] = true
            var merged = true

            while (merged) {
                merged = false
                for (j in strokes.indices) {
                    if (used[j]) continue
                    val distStart = dist(current.points.last(), strokes[j].points.first())
                    val distEnd = dist(current.points.last(), strokes[j].points.last())
                    val distReverse = dist(current.points.first(), strokes[j].points.first())

                    if (distStart < threshold) {
                        // current 尾部接 strokes[j] 头部
                        current = Stroke(
                            current.points + strokes[j].points,
                            current.closePath || strokes[j].closePath
                        )
                        used[j] = true; merged = true; break
                    } else if (distEnd < threshold) {
                        // current 尾部接 strokes[j] 尾部（反转 strokes[j]）
                        current = Stroke(
                            current.points + strokes[j].points.reversed(),
                            current.closePath || strokes[j].closePath
                        )
                        used[j] = true; merged = true; break
                    } else if (distReverse < threshold) {
                        // current 头部接 strokes[j] 头部（反转 current）
                        current = Stroke(
                            strokes[j].points.reversed() + current.points,
                            current.closePath || strokes[j].closePath
                        )
                        used[j] = true; merged = true; break
                    }
                }
            }
            result.add(current)
        }
        return result
    }

    private fun dist(a: PointF, b: PointF): Float =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    /**
     * v1.7: 综合权重（轮廓面积×0.7 + 包围盒面积×0.3）+ 层级过滤
     */
    private fun filterByDetailLevelWithHierarchy(strokes: List<Triple<Stroke, Float, Int>>): List<Stroke> {
        // detailLevel=100: 真全量，不截断
        if (detailLevel >= 100 || strokes.isEmpty()) return strokes.map { it.first }

        val targetRatio = detailLevel / 100.0  // 10→0.1, 80→0.8

        // 层级过滤：线性映射，100时全部保留
        val maxLevel = when {
            targetRatio < 0.2f -> 0     // detail<20: 只留最外层
            targetRatio < 0.5f -> 1     // detail<50: 前2层
            targetRatio < 0.8f -> 2     // detail<80: 前3层
            else -> 99                   // detail≥80: 全部层级
        }
        val hierarchyFiltered = strokes.filter { (_, _, level) ->
            level <= maxLevel
        }
        if (hierarchyFiltered.isEmpty()) return strokes.map { it.first }

        // 综合权重：轮廓面积 + 闭合加成(眼睛等小圆圈×2.5)
        data class WeightedStroke(val stroke: Stroke, val weight: Double)
        val weighted = hierarchyFiltered.map { (stroke, contourArea, _) ->
            val xs = stroke.points.map { it.x }; val ys = stroke.points.map { it.y }
            val minX = xs.minOrNull() ?: 0f; val maxX = xs.maxOrNull() ?: 0f
            val minY = ys.minOrNull() ?: 0f; val maxY = ys.maxOrNull() ?: 0f
            val bboxArea = ((maxX - minX) * (maxY - minY)).toDouble()
            // 闭合轮廓加权：闭合且接近圆形 → ×2.5
            val closeBonus = if (stroke.closePath && bboxArea < 0.01) 2.5 else 1.0
            val weight = contourArea.toDouble() * closeBonus + bboxArea * 0.3
            WeightedStroke(stroke, weight)
        }
        val totalWeight = weighted.sumOf { it.weight }
        if (totalWeight <= 0.0) return hierarchyFiltered.map { it.first }

        // 按权重降序，累计达目标比例截断
        val sorted = weighted.sortedByDescending { it.weight }
        val result = mutableListOf<Stroke>()
        var accumulated = 0.0
        for (ws in sorted) {
            result.add(ws.stroke)
            accumulated += ws.weight
            if (accumulated / totalWeight >= targetRatio) break
        }

        // 小闭合轮廓强制保留（眼睛、纽扣等）：不在截断结果中的小圆也追加
        val resultPoints = result.flatMap { it.points }.toSet()
        val forceKeep = hierarchyFiltered.filter { (stroke, _, _) ->
            if (stroke.points.any { it in resultPoints }) return@filter false // 已在结果中
            val xs = stroke.points.map { it.x }; val ys = stroke.points.map { it.y }
            val w = (xs.maxOrNull() ?: 0f) - (xs.minOrNull() ?: 0f)
            val h = (ys.maxOrNull() ?: 0f) - (ys.minOrNull() ?: 0f)
            val bbox = w * h
            stroke.closePath && bbox > 0f && bbox < 0.008f // 小闭合轮廓（归一化空间）
        }.map { it.first }
        if (forceKeep.isNotEmpty()) {
            Log.d(TAG, "强制保留 ${forceKeep.size} 个小闭合轮廓（眼睛/纽扣等）")
            result.addAll(forceKeep)
        }

        return result
    }
}
