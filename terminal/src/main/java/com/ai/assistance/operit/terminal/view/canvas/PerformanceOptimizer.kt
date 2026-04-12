package com.ai.assistance.operit.terminal.view.canvas

import android.graphics.Rect
import android.util.Log
import kotlin.math.max
import kotlin.math.min

class PerformanceOptimizer {
    
    companion object {
        private const val TAG = "PerformanceOptimizer"
        private const val TARGET_FPS = 60f
        private const val MIN_FPS = 15f
        private const val MAX_FRAME_TIME_MS = 16L // ~60fps
    }
    
        class DirtyRegionTracker {
        private val dirtyRegions = mutableListOf<Rect>()
        private var fullRedrawNeeded = true
        
        fun markDirty(rect: Rect) {
            dirtyRegions.add(Rect(rect))
            fullRedrawNeeded = false
        }
        
        fun markDirtyCell(row: Int, col: Int, charWidth: Float, charHeight: Float) {
            val rect = Rect(
                (col * charWidth).toInt(),
                (row * charHeight).toInt(),
                ((col + 1) * charWidth).toInt(),
                ((row + 1) * charHeight).toInt()
            )
            markDirty(rect)
        }
        
        fun markFullRedraw() {
            fullRedrawNeeded = true
            dirtyRegions.clear()
        }
        
        fun needsFullRedraw(): Boolean = fullRedrawNeeded
        
        fun getDirtyRegions(): List<Rect> = dirtyRegions.toList()
        
        fun clearDirty() {
            dirtyRegions.clear()
            fullRedrawNeeded = false
        }
        
                fun optimizeDirtyRegions(): List<Rect> {
            if (dirtyRegions.isEmpty()) return emptyList()
            
            val merged = mutableListOf<Rect>()
            val sorted = dirtyRegions.sortedBy { it.top * 10000 + it.left }
            
            var current = Rect(sorted[0])
            for (i in 1 until sorted.size) {
                val rect = sorted[i]
                if (shouldMerge(current, rect)) {
                    current.union(rect)
                } else {
                    merged.add(current)
                    current = Rect(rect)
                }
            }
            merged.add(current)
            
            return merged
        }
        
        private fun shouldMerge(r1: Rect, r2: Rect): Boolean {
            val threshold = 50
            return Rect.intersects(r1, r2) ||
                   (r1.bottom >= r2.top - threshold && r1.right >= r2.left - threshold)
        }
    }
    
        class AdaptiveFrameRateController {
        private val frameTimes = ArrayDeque<Long>(60)
        private var lastActivityTime = System.currentTimeMillis()
        private var currentFps = TARGET_FPS
        
        fun recordFrameTime(frameTimeMs: Long) {
            frameTimes.addLast(frameTimeMs)
            if (frameTimes.size > 60) {
                frameTimes.removeFirst()
            }
        }
        
        fun notifyActivity() {
            lastActivityTime = System.currentTimeMillis()
        }
        
        fun getAdaptiveSleepTime(): Long {
            val idleTime = System.currentTimeMillis() - lastActivityTime
            
            // 1
            return when {
                idleTime > 5000 -> 100 // 10fps when very idle
                idleTime > 1000 -> 33  // 30fps when idle
                else -> MAX_FRAME_TIME_MS // 60fps when active
            }
        }
        
        fun getCurrentFps(): Float {
            if (frameTimes.isEmpty()) return currentFps
            
            val avgFrameTime = frameTimes.average()
            currentFps = if (avgFrameTime > 0) 1000f / avgFrameTime.toFloat() else TARGET_FPS
            return currentFps
        }
        
        fun shouldRender(): Boolean {
            val idleTime = System.currentTimeMillis() - lastActivityTime
            // 5
            return idleTime < 5000 || (System.currentTimeMillis() % 1000) < 50
        }
    }
    
    /**
     *  - GC
     */
    class ObjectPool<T>(
        private val factory: () -> T,
        private val reset: (T) -> Unit,
        private val maxSize: Int = 50
    ) {
        private val pool = ArrayDeque<T>(maxSize)
        
        fun acquire(): T {
            return pool.removeFirstOrNull() ?: factory()
        }
        
        fun release(obj: T) {
            if (pool.size < maxSize) {
                reset(obj)
                pool.addLast(obj)
            }
        }
        
        fun clear() {
            pool.clear()
        }
    }
    
        class PerformanceMetrics {
        private var frameCount = 0L
        private var totalFrameTime = 0L
        private var lastReportTime = System.currentTimeMillis()
        
        fun recordFrame(frameTimeMs: Long) {
            frameCount++
            totalFrameTime += frameTimeMs
            
            val now = System.currentTimeMillis()
            if (now - lastReportTime >= 1000) {
                val avgFrameTime = if (frameCount > 0) totalFrameTime / frameCount else 0
                val fps = if (avgFrameTime > 0) 1000f / avgFrameTime else 0f
                
                Log.d(TAG, "Performance: FPS=${"%.1f".format(fps)}, AvgFrameTime=${avgFrameTime}ms, Frames=$frameCount")
                
                frameCount = 0
                totalFrameTime = 0
                lastReportTime = now
            }
        }
        
        fun getCurrentStats(): Stats {
            val avgFrameTime = if (frameCount > 0) totalFrameTime / frameCount else 0
            val fps = if (avgFrameTime > 0) 1000f / avgFrameTime else 0f
            return Stats(fps, avgFrameTime)
        }
        
        data class Stats(val fps: Float, val avgFrameTimeMs: Long)
    }
}

