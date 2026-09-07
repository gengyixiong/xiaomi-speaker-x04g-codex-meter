package com.gengyixiong.codexmeter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.random.Random

internal enum class CatType { YELLOW, TORTOISE, BLACK }

internal data class BlinkWindow(val startMs: Long, val durationMs: Long)
internal data class BlinkPlan(val windows: List<BlinkWindow>, val visibleMs: Long)

internal enum class CatEdge(val rotation: Float) {
    BOTTOM(0f), TOP(180f), LEFT(90f), RIGHT(-90f),
}

internal data class CatBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun overlaps(other: CatBounds, spacing: Float = 0f) =
        left - spacing < other.right && right + spacing > other.left &&
            top - spacing < other.bottom && bottom + spacing > other.top
}

internal object CatOverlayMath {
    const val SIZE = 68f
    private const val VISIBLE = 60f
    private const val CORNER_MARGIN = 40f

    fun spawnCount(percent: Int) = when {
        percent < 70 -> 1
        percent < 95 -> 2
        else -> 3
    }

    fun selectTypes(count: Int, last: CatType?, random: Random): List<CatType> {
        require(count in 1..CatType.values().size)
        val types = CatType.values().toMutableList()
        if (last == null) return types.shuffled(random).take(count)
        val otherTypes = types.filter { it != last }.shuffled(random)
        return when (count) {
            1 -> otherTypes.take(1)
            2 -> otherTypes
            else -> otherTypes + last
        }
    }

    fun blinkPlan(random: Random): BlinkPlan {
        val count = random.nextInt(1, 4)
        var at = random.nextLong(650, 1_401)
        val windows = MutableList(count) {
            BlinkWindow(at, random.nextLong(100, 161)).also { at += it.durationMs + random.nextLong(500, 1_301) }
        }
        val visible = (at + random.nextLong(700, 1_501)).coerceIn(3_000, 7_000)
        return BlinkPlan(windows, visible)
    }

    fun laneRange(length: Int): IntRange {
        val low = (CORNER_MARGIN + SIZE / 2).toInt()
        val high = (length - low).coerceAtLeast(low)
        return low..high
    }

    fun maximumBounds(edge: CatEdge, lane: Float, width: Int, height: Int): CatBounds = when (edge) {
        CatEdge.BOTTOM -> CatBounds(lane - SIZE / 2, height - VISIBLE, lane + SIZE / 2, height + SIZE - VISIBLE)
        CatEdge.TOP -> CatBounds(lane - SIZE / 2, VISIBLE - SIZE, lane + SIZE / 2, VISIBLE)
        CatEdge.LEFT -> CatBounds(VISIBLE - SIZE, lane - SIZE / 2, VISIBLE, lane + SIZE / 2)
        CatEdge.RIGHT -> CatBounds(width - VISIBLE, lane - SIZE / 2, width + SIZE - VISIBLE, lane + SIZE / 2)
    }
}

internal class CatOverlayView(context: Context) : View(context) {
    private data class SpritePair(val open: Bitmap, val blink: Bitmap)
    private data class Cat(
        val type: CatType,
        val sprites: SpritePair,
        val edge: CatEdge,
        val lane: Float,
        val startedAt: Long,
        val enteringMs: Long,
        val visibleMs: Long,
        val exitingMs: Long,
        val blinkPlan: BlinkPlan,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint().apply { isAntiAlias = false; isFilterBitmap = false; isDither = false }
    private val cats = mutableListOf<Cat>()
    private val random = Random.Default
    private var sprites: List<SpritePair>? = null
    private var attached = false
    private var lastSpawnedType: CatType? = null

    private val spawnEvent = Runnable {
        if (attached) {
            val types = CatOverlayMath.selectTypes(CatOverlayMath.spawnCount(random.nextInt(100)), lastSpawnedType, random)
            val delays = listOf(0L) + List((types.size - 1).coerceAtLeast(0)) { random.nextLong(1, 1_501) }.sorted()
            types.forEachIndexed { index, type ->
                val delay = delays[index]
                handler.postDelayed({ if (attached) spawnOne(type) }, delay)
            }
            scheduleEvent()
        }
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        if (sprites == null) sprites = loadSprites()
        if (sprites != null) scheduleEvent()
    }

    override fun onDetachedFromWindow() {
        attached = false
        handler.removeCallbacksAndMessages(null)
        cats.clear()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent) = false

    private fun loadSprites(): List<SpritePair>? = runCatching {
        listOf(
            SpritePair(bitmap(R.drawable.cat_yellow_open), bitmap(R.drawable.cat_yellow_blink)),
            SpritePair(bitmap(R.drawable.cat_tortoise_open), bitmap(R.drawable.cat_tortoise_blink)),
            SpritePair(bitmap(R.drawable.cat_black_open), bitmap(R.drawable.cat_black_blink)),
        )
    }.getOrNull()

    private fun bitmap(resource: Int): Bitmap =
        requireNotNull(BitmapFactory.decodeResource(resources, resource)) { "Missing cat sprite" }

    private fun scheduleEvent() {
        handler.postDelayed(spawnEvent, random.nextLong(15_000, 45_001))
    }

    private fun spawnOne(type: CatType) {
        val choices = sprites ?: return
        if (type == lastSpawnedType || width <= 0 || height <= 0 || cats.size >= 3) return
        repeat(10) {
            val edge = CatEdge.values().random(random)
            val lane = if (edge == CatEdge.TOP || edge == CatEdge.BOTTOM) {
                CatOverlayMath.laneRange(width).random(random).toFloat()
            } else {
                CatOverlayMath.laneRange(height).random(random).toFloat()
            }
            val candidate = CatOverlayMath.maximumBounds(edge, lane, width, height)
            if (cats.none { candidate.overlaps(CatOverlayMath.maximumBounds(it.edge, it.lane, width, height), 10f) }) {
                val entering = random.nextLong(300, 451)
                val blinkPlan = CatOverlayMath.blinkPlan(random)
                cats += Cat(
                    type, choices[type.ordinal], edge, lane, SystemClock.uptimeMillis(), entering, blinkPlan.visibleMs,
                    random.nextLong(300, 451), blinkPlan,
                )
                lastSpawnedType = type
                invalidate()
                return
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val iterator = cats.iterator()
        while (iterator.hasNext()) {
            val cat = iterator.next()
            val elapsed = now - cat.startedAt
            val total = cat.enteringMs + cat.visibleMs + cat.exitingMs
            if (elapsed >= total) {
                iterator.remove()
                continue
            }
            val amount = when {
                elapsed < cat.enteringMs -> ease(elapsed.toFloat() / cat.enteringMs)
                elapsed < cat.enteringMs + cat.visibleMs -> 1f
                else -> 1f - ease((elapsed - cat.enteringMs - cat.visibleMs).toFloat() / cat.exitingMs)
            }
            val centerX = centerX(cat, amount)
            val centerY = centerY(cat, amount)
            val blinkElapsed = elapsed - cat.enteringMs
            val blinking = cat.blinkPlan.windows.any { blinkElapsed in it.startMs until it.startMs + it.durationMs }
            canvas.save()
            canvas.rotate(cat.edge.rotation, centerX, centerY)
            canvas.drawBitmap(if (blinking) cat.sprites.blink else cat.sprites.open, centerX - CatOverlayMath.SIZE / 2, centerY - CatOverlayMath.SIZE / 2, paint)
            canvas.restore()
        }
        if (cats.isNotEmpty()) postInvalidateDelayed(16)
    }

    private fun centerX(cat: Cat, amount: Float): Float {
        val outside = CatOverlayMath.SIZE / 2
        val travel = 60f
        return when (cat.edge) {
            CatEdge.BOTTOM, CatEdge.TOP -> cat.lane
            CatEdge.LEFT -> -outside + travel * amount
            CatEdge.RIGHT -> width + outside - travel * amount
        }
    }

    private fun centerY(cat: Cat, amount: Float): Float {
        val outside = CatOverlayMath.SIZE / 2
        val travel = 60f
        return when (cat.edge) {
            CatEdge.LEFT, CatEdge.RIGHT -> cat.lane
            CatEdge.BOTTOM -> height + outside - travel * amount
            CatEdge.TOP -> -outside + travel * amount
        }
    }

    private fun ease(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

}
