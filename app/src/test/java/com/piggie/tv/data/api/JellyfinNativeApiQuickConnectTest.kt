package com.piggie.tv.data.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JellyfinNativeApiQuickConnectTest {
    private lateinit var server: MockWebServer
    private lateinit var api: JellyfinNativeApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun ticketRepresentationNeverContainsCodeOrSecret() {
        val ticket = QuickConnectTicket(secret = "secret-value", code = "ABC123")

        assertFalse(ticket.toString().contains("secret-value"))
        assertFalse(ticket.toString().contains("ABC123"))
        assertTrue(ticket.toString().contains("[REDACTED]"))
    }

    @Test
    fun initiateParsesTicketWithoutPuttingSecretInRepresentation() {
        server.enqueue(MockResponse().setBody("""{"Secret":"secret-value","Code":"ABC123"}"""))

        val ticket = api.initiateQuickConnect(server.url("/").toString())
        val request = server.takeRequest()

        assertEquals("secret-value", ticket.secret)
        assertEquals("ABC123", ticket.code)
        assertEquals("POST", request.method)
        assertEquals("/QuickConnect/Initiate", request.requestUrl?.encodedPath)
        assertFalse(ticket.toString().contains(ticket.secret))
        assertFalse(ticket.toString().contains(ticket.code))
    }

    @Test
    fun initiateRejectsIncompleteTicketWithoutExposingResponseFields() {
        server.enqueue(MockResponse().setBody("""{"Secret":"","Code":"ABC123"}"""))

        val error = runCatching {
            api.initiateQuickConnect(server.url("/").toString())
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(error?.message.orEmpty().contains("ABC123"))
    }

    @Test
    fun statePollParsesApprovalAndScopesSecretToQuery() {
        server.enqueue(
            MockResponse().setBody(
                """{"Authenticated":true,"Secret":"secret-value","Code":"ABC123"}"""
            )
        )

        val status = api.getQuickConnectStatus(server.url("/").toString(), "secret-value")
        val request = server.takeRequest()

        assertTrue(status.authenticated)
        assertEquals("/QuickConnect/Connect", request.requestUrl?.encodedPath)
        assertEquals("secret-value", request.requestUrl?.queryParameter("Secret"))
    }

    @Test
    fun statePollRejectsAnotherAttemptsSecret() {
        server.enqueue(
            MockResponse().setBody(
                """{"Authenticated":true,"Secret":"different-secret","Code":"ABC123"}"""
            )
        )

        val error = runCatching {
            api.getQuickConnectStatus(server.url("/").toString(), "expected-secret")
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(error?.message.orEmpty().contains("expected-secret"))
        assertFalse(error?.message.orEmpty().contains("different-secret"))
    }

    @Test
    fun pollHttpStatusRemainsTypedForPolicyMapping() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("expired"))

        val error = runCatching {
            api.getQuickConnectStatus(server.url("/").toString(), "secret-value")
        }.exceptionOrNull()

        assertTrue(error is HttpRequestFailure)
        assertEquals(404, (error as HttpRequestFailure).statusCode)
        assertFalse(error.message.orEmpty().contains("secret-value"))
    }

    @Test
    fun approvedSecretAuthenticatesOnceInRequestBody() {
        server.enqueue(
            MockResponse().setBody(
                """{"AccessToken":"access-token","ServerId":"server-id","User":{"Id":"user-id","Name":"Piggie"}}"""
            )
        )

        val session = api.authenticateWithQuickConnect(
            server.url("/").toString(),
            "approved-secret",
        )
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertEquals("POST", request.method)
        assertEquals("/Users/AuthenticateWithQuickConnect", request.requestUrl?.encodedPath)
        assertEquals("approved-secret", body.getString("Secret"))
        assertEquals("access-token", session.token)
        request.headers.values("Authorization").forEach { value ->
            assertFalse(value.contains("approved-secret"))
        }
    }
}
