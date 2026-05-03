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
    private var userHasAdjusted = false

    // Matches iOS CAShapeLayer: fillColor = rgba(0, 0.5, 1, 0.2), strokeColor = rgba(0, 0.5, 1, 0.9)
    private val quadFillPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 0x330080FF fits in positive Int (alpha 0x33 < 0x80) — no .toInt() needed.
        color = 0x330080FF
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

    // Reused across onDraw frames. rewind() preserves the internal data buffer so
    // the per-frame allocation cost stays out of the draw hot path.
    private val quadPath = Path()

    fun setImageBounds(
      left: Float,
      top: Float,
      right: Float,
      bottom: Float,
    ) {
      imageBounds.set(left, top, right, bottom)
    }

    // Sets corner positions, clamping each to imageBounds.
    // No-op if the user has already started adjusting handles manually,
    // so a late-arriving auto-detection result doesn't override their work.
    fun setCorners(
      tl: PointF,
      tr: PointF,
      br: PointF,
      bl: PointF,
    ) {
      if (userHasAdjusted) return

      fun cx(x: Float) = x.coerceIn(imageBounds.left, imageBounds.right)

      fun cy(y: Float) = y.coerceIn(imageBounds.top, imageBounds.bottom)
      corners[0].set(cx(tl.x), cy(tl.y))
      corners[1].set(cx(tr.x), cy(tr.y))
      corners[2].set(cx(br.x), cy(br.y))
      corners[3].set(cx(bl.x), cy(bl.y))
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
      quadPath.rewind()
      quadPath.moveTo(corners[0].x, corners[0].y)
      quadPath.lineTo(corners[1].x, corners[1].y)
      quadPath.lineTo(corners[2].x, corners[2].y)
      quadPath.lineTo(corners[3].x, corners[3].y)
      quadPath.close()
      canvas.drawPath(quadPath, quadFillPaint)
      canvas.drawPath(quadPath, quadStrokePaint)
      corners.forEach { pt ->
        canvas.drawCircle(pt.x, pt.y, handleRadius, handleFillPaint)
        canvas.drawCircle(pt.x, pt.y, handleRadius, handleBorderPaint)
      }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          activeIndex = nearestHandle(event.x, event.y)
          if (activeIndex != -1) {
            userHasAdjusted = true
            parent?.requestDisallowInterceptTouchEvent(true)
          }
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

        MotionEvent.ACTION_UP -> {
          parent?.requestDisallowInterceptTouchEvent(false)
          activeIndex = -1
          performClick()
        }

        MotionEvent.ACTION_CANCEL -> {
          parent?.requestDisallowInterceptTouchEvent(false)
          activeIndex = -1
        }
      }
      return true
    }

    // Required by ClickableViewAccessibility lint when overriding onTouchEvent.
    // The view has no click semantics of its own (handles are dragged, not tapped),
    // but the override lets accessibility services dispatch click events.
    override fun performClick(): Boolean {
      super.performClick()
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
