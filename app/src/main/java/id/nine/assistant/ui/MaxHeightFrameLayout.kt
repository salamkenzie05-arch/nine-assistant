package id.nine.assistant.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.min

/**
 * FrameLayout yang tingginya mengikuti isi, tetapi tidak pernah melebihi [maxHeightPx] (jika > 0).
 * Dipakai di layar asisten agar riwayat tidak terpotong di tengah kalimat: panel cukup tinggi untuk isinya.
 */
class MaxHeightFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var maxHeightPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var hs = heightMeasureSpec
        if (maxHeightPx > 0) {
            val size = MeasureSpec.getSize(heightMeasureSpec)
            hs = when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(min(size, maxHeightPx), MeasureSpec.AT_MOST)
                MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
                else -> heightMeasureSpec
            }
        }
        super.onMeasure(widthMeasureSpec, hs)
    }
}
