package com.piggie.tv.util

import android.content.Context
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.DimenRes

fun Context.dim(@DimenRes id: Int): Int = resources.getDimensionPixelSize(id)

fun Context.dimFloat(@DimenRes id: Int): Float = resources.getDimension(id)

fun Context.sp(@DimenRes id: Int): Float = resources.getDimension(id) / resources.displayMetrics.scaledDensity

fun TextView.setTextSizeRes(@DimenRes id: Int) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(id))
}
