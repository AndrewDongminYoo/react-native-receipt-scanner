package com.receiptscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

// Corner order: topLeft[0], topRight[1], bottomRight[2], bottomLeft[3]
internal class QuadCropView
  @JvmOverloads
  constructor(
    context: Context,
    attrs: AttributeSet? = null,
  ) : View(context, attrs) {
    private val dp = resources.displayMetrics.density
    private val corners = Array(4) { PointF() }
    private var activeIndex = -1
    private val imageBounds = RectF()

    // Matches iOS CAShapeLayer: fillColor = rgba(0, 0.5, 1, 0.2), strokeColor = rgba(0, 0.5, 1, 0.9)
    private val quadFillPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x330080FF.toInt()
        style = Paint.Style.FILL
      }
    private val quadStrokePaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE60080FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
      }
    private val handleFillPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
      }

    // Matches iOS handle.layer.borderColor = UIColor.systemBlueColor, borderWidth = 2
    private val handleBorderPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF007AFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
      }

    private val handleRadius get() = 16f * dp // matches iOS kHandleRadius = 16
    private val touchRadius get() = 40f * dp

    fun setImageRect(
      left: Float,
      top: Float,
      right: Float,
      bottom: Float,
    ) {
      imageBounds.set(left, top, right, bottom)
      corners[0].set(left, top)
      corners[1].set(right, top)
      corners[2].set(right, bottom)
      corners[3].set(left, bottom)
      invalidate()
    }

    /**
     * Returns the 8 corner coordinates [tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y]
     * mapped back to full-resolution original image space.
     */
    fun getCornersInImageSpace(
      imageLeft: Float,
      imageTop: Float,
      displayWidth: Float,
      displayHeight: Float,
      originalWidth: Int,
      originalHeight: Int,
    ): FloatArray {
      val sx = originalWidth / displayWidth
      val sy = originalHeight / displayHeight
      return FloatArray(8) { i ->
        val pt = corners[i / 2]
        if (i % 2 == 0) (pt.x - imageLeft) * sx else (pt.y - imageTop) * sy
      }
    }

    override fun onDraw(canvas: Canvas) {
      super.onDraw(canvas)
      val quad =
        Path().apply {
          moveTo(corners[0].x, corners[0].y)
          lineTo(corners[1].x, corners[1].y)
          lineTo(corners[2].x, corners[2].y)
          lineTo(corners[3].x, corners[3].y)
          close()
        }
      canvas.drawPath(quad, quadFillPaint)
      canvas.drawPath(quad, quadStrokePaint)
      corners.forEach { pt ->
        canvas.drawCircle(pt.x, pt.y, handleRadius, handleFillPaint)
        canvas.drawCircle(pt.x, pt.y, handleRadius, handleBorderPaint)
      }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          activeIndex = nearestHandle(event.x, event.y)
          if (activeIndex != -1) parent?.requestDisallowInterceptTouchEvent(true)
          return activeIndex != -1
        }

        MotionEvent.ACTION_MOVE -> {
          if (activeIndex != -1) {
            // Clamp to image bounds, matching iOS behavior
            val x = event.x.coerceIn(imageBounds.left, imageBounds.right)
            val y = event.y.coerceIn(imageBounds.top, imageBounds.bottom)
            corners[activeIndex].set(x, y)
            invalidate()
          }
        }

        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          parent?.requestDisallowInterceptTouchEvent(false)
          activeIndex = -1
        }
      }
      return true
    }

    private fun nearestHandle(
      x: Float,
      y: Float,
    ): Int {
      val maxDist = touchRadius * touchRadius
      var best = -1
      var bestDist = maxDist
      corners.forEachIndexed { i, pt ->
        val dx = pt.x - x
        val dy = pt.y - y
        val d = dx * dx + dy * dy
        if (d < bestDist) {
          bestDist = d
          best = i
        }
      }
      return best
    }
  }
