package com.piggie.tv.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.reader.ReaderCache
import com.piggie.tv.data.reader.ReaderDocument
import com.piggie.tv.data.reader.ReaderFormat
import com.piggie.tv.data.reader.ReaderRepository
import com.piggie.tv.data.reader.ReaderSource
import com.piggie.tv.data.session.ReadingSettings
import com.piggie.tv.util.PTVLog
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvReaderTrace
import com.piggie.tv.memory.MemoryPressurePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

sealed class ReaderState {
    data object Idle : ReaderState()
    data object Loading : ReaderState()
    data class Success(
        val document: ReaderDocument,
        val initialPage: Int,
        val isRtl: Boolean,
        val fitMode: ReaderFitMode
    ) : ReaderState()
    data class Unsupported(val format: ReaderFormat) : ReaderState()
    data class Error(val message: String) : ReaderState()
}

data class ReaderPageResult(
    val requestId: Int,
    val pageIndex: Int,
    val bitmap: Bitmap?,
    val cacheHit: Boolean,
    val renderDurationMs: Long,
    val error: String? = null
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val api = JellyfinNativeApi(application)
    private val cache = ReaderCache(application)
    private val repository = ReaderRepository(application, api, cache)
    private val settings = ReadingSettings(application)
    private val sourceMutex = Mutex()
    private val requestCounter = AtomicInteger()
    private val bitmapCacheTimes = mutableMapOf<String, Long>()
    private val bitmapCache = object : LruCache<String, Bitmap>(READER_BITMAP_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (newValue == null) bitmapCacheTimes.remove(key)
        }
    }

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Idle)
    val state: StateFlow<ReaderState> = _state

    private var currentSource: ReaderSource? = null
    private var currentPageJob: Job? = null
    private var prefetchJob: Job? = null
    private var currentItemId: String = "unknown"

    fun loadDocument(session: NativeSession, item: MediaItem) {
        currentItemId = item.id
        currentPageJob?.cancel()
        prefetchJob?.cancel()
        synchronized(bitmapCache) { bitmapCache.evictAll() }
        PtvDiagnosticsManager.setPageCacheEntries(0)
        viewModelScope.launch {
            _state.value = ReaderState.Loading
            try {
                val result = withContext(Dispatchers.IO) {
                    currentSource?.release()
                    val document = repository.getDocument(session, item)
                    if (document.format !in setOf(ReaderFormat.CBZ, ReaderFormat.PDF, ReaderFormat.JELLYFIN_PAGES)) {
                        return@withContext ReaderState.Unsupported(document.format)
                    }
                    val source = repository.getSource(session, item, document.format)
                    if (!source.open(getApplication())) {
                        return@withContext ReaderState.Error("The document source could not be opened.")
                    }
                    currentSource = source
                    val pageCount = source.getPageCount()
                    if (pageCount <= 0) return@withContext ReaderState.Error("This document contains no readable pages.")
                    val actualDocument = document.copy(pageCount = pageCount)
                    val initialPage = settings.getLastPage(session, item.id).coerceIn(0, pageCount - 1)
                    val fitMode = runCatching { ReaderFitMode.valueOf(settings.getFitMode(session, item.id)) }
                        .getOrDefault(ReaderFitMode.FIT_PAGE)
                    ReaderState.Success(actualDocument, initialPage, settings.isRtl(session, item.id), fitMode)
                }
                _state.value = result
            } catch (_: CancellationException) {
                // A replacement document request owns the state now.
            } catch (error: Exception) {
                PTVLog.e("Reader document load failed", error)
                _state.value = ReaderState.Error(error.message ?: "Unknown reader error")
            }
        }
    }

    fun requestPage(
        index: Int,
        pageCount: Int,
        targetWidth: Int,
        targetHeight: Int,
        callback: (ReaderPageResult) -> Unit
    ): Int {
        val requestId = requestCounter.incrementAndGet()
        currentPageJob?.cancel()
        prefetchJob?.cancel()
        currentPageJob = viewModelScope.launch {
            val key = cacheKey(index, targetWidth, targetHeight)
            val cached = getCachedBitmap(key)
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val bitmap = try {
                cached ?: withContext(Dispatchers.IO) {
                    sourceMutex.withLock {
                        currentSource?.getPage(index, targetWidth, targetHeight)
                    }
                }?.also {
                    putCachedBitmap(key, it)
                    PtvDiagnosticsManager.setPageCacheEntries(synchronized(bitmapCache) { bitmapCache.snapshot().size })
                }
            } catch (_: CancellationException) {
                PTVLog.d("Reader stale request canceled page=$index request=$requestId")
                PtvDiagnosticsManager.recordReader(
                    PtvReaderTrace(itemId = currentItemId, event = "stale_request_canceled", page = index)
                )
                return@launch
            } catch (error: Throwable) {
                callback(
                    ReaderPageResult(
                        requestId,
                        index,
                        null,
                        false,
                        android.os.SystemClock.elapsedRealtime() - startedAt,
                        error.message ?: error.javaClass.simpleName
                    )
                )
                return@launch
            }
            ensureActive()
            callback(
                ReaderPageResult(
                    requestId = requestId,
                    pageIndex = index,
                    bitmap = bitmap,
                    cacheHit = cached != null,
                    renderDurationMs = android.os.SystemClock.elapsedRealtime() - startedAt,
                    error = if (bitmap == null) "Page rendering returned no image." else null
                )
            )
            if (bitmap != null) prefetchAdjacent(index, pageCount, targetWidth, targetHeight)
        }
        return requestId
    }

    private fun prefetchAdjacent(current: Int, pageCount: Int, width: Int, height: Int) {
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            listOf(current - 1, current + 1)
                .filter { it in 0 until pageCount }
                .forEach { index ->
                    ensureActive()
                    val key = cacheKey(index, width, height)
                    if (getCachedBitmap(key) == null) {
                        val bitmap = sourceMutex.withLock { currentSource?.getPage(index, width, height) }
                        if (bitmap != null) {
                            putCachedBitmap(key, bitmap)
                            PtvDiagnosticsManager.setPageCacheEntries(synchronized(bitmapCache) { bitmapCache.snapshot().size })
                        }
                    }
                }
        }
    }

    fun saveProgress(session: NativeSession, itemId: String, pageIndex: Int) {
        settings.setLastPage(session, itemId, pageIndex)
        api.reportReadingProgress(session, itemId, pageIndex)
    }

    fun toggleRtl(session: NativeSession, itemId: String, isRtl: Boolean) {
        settings.setRtl(session, itemId, isRtl)
    }

    fun setFitMode(session: NativeSession, itemId: String, fitMode: ReaderFitMode) {
        settings.setFitMode(session, itemId, fitMode.name)
    }

    fun onTrimMemory(level: Int) {
        val actions = MemoryPressurePolicy.actions(level)
        if (!actions.clearImageMemoryCache) return
        prefetchJob?.cancel()
        synchronized(bitmapCache) {
            bitmapCache.trimToSize(
                if (actions.discoveryCachePercent > 0) READER_BITMAP_LOW_BYTES else 0
            )
            PtvDiagnosticsManager.setPageCacheEntries(bitmapCache.snapshot().size)
        }
    }

    private fun getCachedBitmap(key: String, nowMs: Long = android.os.SystemClock.elapsedRealtime()): Bitmap? =
        synchronized(bitmapCache) {
            val storedAt = bitmapCacheTimes[key]
            if (storedAt == null || nowMs - storedAt > READER_BITMAP_TTL_MS) {
                bitmapCache.remove(key)
                null
            } else {
                bitmapCache.get(key)
            }
        }

    private fun putCachedBitmap(
        key: String,
        bitmap: Bitmap,
        nowMs: Long = android.os.SystemClock.elapsedRealtime()
    ) = synchronized(bitmapCache) {
        bitmapCacheTimes[key] = nowMs
        bitmapCache.put(key, bitmap)
    }

    private fun cacheKey(index: Int, width: Int, height: Int): String = "$currentItemId:$index@${width}x$height"

    override fun onCleared() {
        currentPageJob?.cancel()
        prefetchJob?.cancel()
        currentSource?.release()
        synchronized(bitmapCache) {
            bitmapCache.evictAll()
            bitmapCacheTimes.clear()
        }
        super.onCleared()
    }

    private companion object {
        const val READER_BITMAP_CACHE_BYTES = 24 * 1024 * 1024
        const val READER_BITMAP_LOW_BYTES = 8 * 1024 * 1024
        const val READER_BITMAP_TTL_MS = 5L * 60L * 1000L
    }
}
