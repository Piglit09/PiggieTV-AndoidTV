package com.piggie.tv.data.discovery

import com.piggie.tv.data.api.HttpRequestFailure
import java.net.SocketTimeoutException
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryFailurePolicyTest {
    @Test fun faultsMapToDistinctTerminalStates() {
        assertEquals(ShelfStatus.INVALID_QUERY, DiscoveryManager.shelfFailure(HttpRequestFailure(400, "")).first)
        assertEquals(ShelfStatus.HTTP_ERROR, DiscoveryManager.shelfFailure(HttpRequestFailure(502, "")).first)
        assertEquals(ShelfStatus.TIMEOUT, DiscoveryManager.shelfFailure(SocketTimeoutException()).first)
        assertEquals(ShelfStatus.RENDER_ERROR, DiscoveryManager.shelfFailure(JSONException("bad" )).first)
    }
}
