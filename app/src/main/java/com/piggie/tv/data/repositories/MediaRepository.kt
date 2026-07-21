package com.piggie.tv.data.repositories

import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaShelf
import com.piggie.tv.data.models.NativeSession

class MediaRepository(private val api: JellyfinNativeApi) {
    
    fun getHomeShelves(session: NativeSession, callback: (MediaShelf) -> Unit) {
        // Wrap API calls with native recommendation logic
        api.loadHomeIncrementally(session, callback)
    }

    fun getReadingHomeShelves(session: NativeSession, callback: (MediaShelf) -> Unit) {
        api.loadReadingHomeIncrementally(session, callback)
    }

    fun getRecommended(session: NativeSession): List<MediaShelf> {
        // Future: Fetch tailored recommendations, genres, etc.
        return emptyList()
    }
}
