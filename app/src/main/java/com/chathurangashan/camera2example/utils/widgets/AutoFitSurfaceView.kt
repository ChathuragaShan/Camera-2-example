package com.chathurangashan.camera2example.utils.widgets

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceView
import kotlin.math.roundToInt

/**
 * A [SurfaceView] that can be adjusted to a specified aspect ratio and
 * performs center-crop transformation of input frames.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle) {

    private var aspectRatio = 0f
    var adjustedWidth = 0
    var adjustedHeight = 0

    /**
     * Sets the aspect ratio for this view. The size of the view will be
     * measured based on the ratio calculated from the parameters.
     *
     * @param width  Camera resolution horizontal size
     * @param height Camera resolution vertical size
     */
    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Size cannot be negative" }
        aspectRatio = width.toFloat() / height.toFloat()
        Log.d(TAG, "Width and Height dimensions are: $width x $height")
        Log.d(TAG, "Ratio is: $width / $height")
        Log.d(TAG, "Ratio is in decimal: $aspectRatio")
        holder.setFixedSize(width, height)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        Log.d(TAG, "before change height and width: $width x $height")

        if (aspectRatio == 0f) {
            setMeasuredDimension(width, height)
        } else {
            val newDimension = letterBoxEffect(width, height)
            adjustedWidth = newDimension.first
            adjustedHeight = newDimension.second
            setMeasuredDimension(adjustedWidth, adjustedHeight)
        }
    }

    /**
     * This function is responsible for applying center-crop effect to the camera view
     */
    private fun centerCropEffect(width: Int, height: Int): Pair<Int, Int> {

        // Performs center-crop transformation of the camera frames
        val newWidth: Int
        val newHeight: Int
        val actualRatio = if (width > height) aspectRatio else 1f / aspectRatio
        if (width < height * actualRatio) {
            newHeight = height
            newWidth = (height * actualRatio).roundToInt()
        } else {
            newWidth = width
            newHeight = (width / actualRatio).roundToInt()
        }

        Log.d(TAG, "Measured dimensions set: $newWidth x $newHeight")
        return Pair(newWidth, newHeight)

    }

    /**
     * This function is responsible for applying letter-box effect to the camera view
     */
    private fun letterBoxEffect(width: Int, height: Int): Pair<Int, Int> {

        val newWidth: Int
        val newHeight: Int
        val actualRatio = if (width > height) aspectRatio else 1f / aspectRatio

        if (width < height) {
            newHeight = (width / actualRatio).roundToInt()
            newWidth = width
        } else {
            newWidth = (height * actualRatio).roundToInt()
            newHeight = height
        }

        Log.d(TAG, "Measured dimensions set: $newWidth x $newHeight")
        return Pair(newWidth, newHeight)
    }

    companion object {
        private val TAG = AutoFitSurfaceView::class.java.simpleName
    }
}
