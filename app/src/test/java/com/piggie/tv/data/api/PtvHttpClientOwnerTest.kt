package com.piggie.tv.data.api

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class PtvHttpClientOwnerTest {
    @Test
    fun transportsShareConnectionPoolAndDispatcherButNotClientIdentity() {
        val first = NativeHttpTransport({ "first" })
        val second = NativeHttpTransport({ "second" })

        assertNotSame(first.client, second.client)
        assertSame(PtvHttpClientOwner.metadataBaseClient.connectionPool, first.client.connectionPool)
        assertSame(first.client.connectionPool, second.client.connectionPool)
        assertSame(PtvHttpClientOwner.metadataBaseClient.dispatcher, first.client.dispatcher)
        assertSame(first.client.dispatcher, second.client.dispatcher)
    }
}
