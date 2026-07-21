package com.piggie.tv.util

import com.piggie.tv.data.models.NativeSession

object JellyfinUrlUtils {
    fun userAvatar(session: NativeSession): String =
        "${session.serverUrl}/Users/${session.userId}/Images/Primary"
}
