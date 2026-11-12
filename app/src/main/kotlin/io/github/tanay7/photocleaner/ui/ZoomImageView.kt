package io.github.tanay7.photocleaner.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * A picture that fits the screen and can be pinched or double-tapped to zoom and dragged to pan.
 * When it isn't zoomed, a sideways swipe asks for the next or previous picture.
 */
class ZoomImageView(ctx: Context) : View(ctx) {
    var onSwipe: ((Int) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    /** Scale that fits the picture in the view, then the user's zoom on top of it. */
    private var fit = 1f
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.05f) reset() else zoomBy(2.5f, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom <= 1.01f) return false
            tx -= dx
            ty -= dy
            clamp()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (zoom > 1.01f || abs(vx) < 800 || abs(vx) < abs(vy) * 1.5f) return false
            onSwipe?.invoke(if (vx < 0) 1 else -1)
            return true
        }
    })

    /** Shows [bmp]; keeps the zoom when [keepZoom] (a sharper copy of the same picture arrived). */
    fun setBitmap(bmp: Bitmap?, keepZoom: Boolean = false) {
        bitmap = bmp
        if (keepZoom && bmp != null && zoom > 1.01f) {
            // Same picture, more pixels: the fit scale changes and the picture stays where it was.
            computeFit()
            clamp()
        } else {
            reset()
        }
        invalidate()
    }

    private fun fitFor(b: Bitmap) = if (width == 0 || height == 0) 1f else min(width.toFloat() / b.width, height.toFloat() / b.height)

    private fun computeFit() {
        val b = bitmap ?: return
        fit = fitFor(b)
    }

    fun reset() {
        zoom = 1f
        computeFit()
        center()
        invalidate()
    }

    private fun center() {
        val b = bitmap ?: return
        val s = fit * zoom
        tx = (width - b.width * s) / 2
        ty = (height - b.height * s) / 2
    }

    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        if (bitmap == null) return
        val old = zoom
        zoom = (zoom * f).coerceIn(1f, 8f)
        val k = zoom / old
        tx = fx - (fx - tx) * k
        ty = fy - (fy - ty) * k
        if (zoom <= 1.001f) center() else clamp()
        invalidate()
    }

    /** Keeps the picture covering the view when it's bigger than it, and centred when smaller. */
    private fun clamp() {
        val b = bitmap ?: return
        val s = fit * zoom
        val w = b.width * s
        val h = b.height * s
        tx = if (w <= width) (width - w) / 2 else tx.coerceIn(width - w, 0f)
        ty = if (h <= height) (height - h) / 2 else ty.coerceIn(height - h, 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    /** A tap shows or hides the controls; TalkBack's double-tap does the same. */
    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    @SuppressLint("ClickableViewAccessibility") // the gesture detector calls performClick() on a tap
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (b.isRecycled) return
        val s = fit * zoom
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(s, s)
        canvas.drawBitmap(b, 0f, 0f, paint)
        canvas.restore()
    }
}
