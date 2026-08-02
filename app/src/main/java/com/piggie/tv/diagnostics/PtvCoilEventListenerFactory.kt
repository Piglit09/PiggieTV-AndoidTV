package com.piggie.tv.diagnostics

import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import coil.EventListener
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Dimension
import coil.size.Size

/**
 * Process-wide Coil accounting. Disabled requests reuse Coil's no-op singleton; enabled requests
 * retain only primitive timing/size state until their terminal callback.
 */
object PtvCoilEventListenerFactory : EventListener.Factory {
    override fun create(request: ImageRequest): EventListener =
        if (PtvDiagnosticsManager.isEnabled()) {
            PtvCoilEventListener(
                request.parameters.value<String>(CATEGORY_PARAMETER) ?: CATEGORY_UNCLASSIFIED
            )
        } else {
            EventListener.NONE
        }

    const val CATEGORY_PARAMETER = "ptv_image_category"
    const val CATEGORY_HERO = "hero"
    const val CATEGORY_CARD = "card"
    const val CATEGORY_PROFILE = "profile"
    const val CATEGORY_DETAILS_BACKDROP = "details_backdrop"
    const val CATEGORY_DETAILS_LOGO = "details_logo"
    const val CATEGORY_DETAILS_PERSON = "details_person"
    const val CATEGORY_UNCLASSIFIED = "unclassified"
}

private class PtvCoilEventListener(
    private val category: String
) : EventListener {
    private var startedAtMs = 0L
    private var concurrentRequests = 0
    private var requestedWidth = -1
    private var requestedHeight = -1
    private var completed = false

    override fun onStart(request: ImageRequest) {
        concurrentRequests = PtvDiagnosticsManager.imageStarted()
        if (concurrentRequests > 0) startedAtMs = SystemClock.elapsedRealtime()
    }

    override fun resolveSizeEnd(request: ImageRequest, size: Size) {
        requestedWidth = size.width.pixelValue()
        requestedHeight = size.height.pixelValue()
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
        finish(
            bitmapWidth = bitmap?.width,
            bitmapHeight = bitmap?.height,
            bitmapAllocationBytes = bitmap?.let { value ->
                runCatching { value.allocationByteCount.coerceAtLeast(0) }.getOrNull()
            },
            dataSource = result.dataSource.name,
            failure = null,
            canceled = false
        )
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        finish(
            bitmapWidth = null,
            bitmapHeight = null,
            bitmapAllocationBytes = null,
            dataSource = null,
            failure = result.throwable.javaClass.simpleName.ifBlank { "image_error" },
            canceled = false
        )
    }

    override fun onCancel(request: ImageRequest) {
        finish(
            bitmapWidth = null,
            bitmapHeight = null,
            bitmapAllocationBytes = null,
            dataSource = null,
            failure = null,
            canceled = true
        )
    }

    private fun finish(
        bitmapWidth: Int?,
        bitmapHeight: Int?,
        bitmapAllocationBytes: Int?,
        dataSource: String?,
        failure: String?,
        canceled: Boolean
    ) {
        if (completed || concurrentRequests <= 0) return
        completed = true
        PtvDiagnosticsManager.recordCoilImageResult(
            category = category,
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            bitmapAllocationBytes = bitmapAllocationBytes,
            dataSource = dataSource,
            loadMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L),
            failure = failure,
            canceled = canceled,
            concurrentRequests = concurrentRequests
        )
    }

    private fun Dimension.pixelValue(): Int = (this as? Dimension.Pixels)?.px ?: -1
}
