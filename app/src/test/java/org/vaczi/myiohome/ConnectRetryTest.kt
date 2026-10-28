package org.vaczi.myiohome

import org.vaczi.myiohome.data.net.ConnectRetry
import org.vaczi.myiohome.data.net.HttpStatusException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException

class ConnectRetryTest {

    @Test
    fun getRetriesConnectionLevelFailures() {
        assertTrue(ConnectRetry.shouldRetry(ConnectException("refused"), post = false, extraAttemptsDone = 0))
        assertTrue(ConnectRetry.shouldRetry(SocketException("Connection reset"), post = false, extraAttemptsDone = 1))
        assertTrue(ConnectRetry.shouldRetry(IOException("unexpected end of stream on http://x"), post = false, extraAttemptsDone = 0))
    }

    @Test
    fun stopsAfterTwoExtraAttempts() {
        assertFalse(ConnectRetry.shouldRetry(ConnectException("refused"), post = false, extraAttemptsDone = 2))
    }

    @Test
    fun postRetriesOnlyFailedConnects() {
        assertTrue(ConnectRetry.shouldRetry(ConnectException("refused"), post = true, extraAttemptsDone = 0))
        assertFalse(ConnectRetry.shouldRetry(SocketException("Connection reset"), post = true, extraAttemptsDone = 0))
        assertFalse(ConnectRetry.shouldRetry(IOException("unexpected end of stream"), post = true, extraAttemptsDone = 0))
    }

    @Test
    fun neverRetriesTimeoutsOrHttpStatus() {
        assertFalse(ConnectRetry.shouldRetry(SocketTimeoutException("timeout"), post = false, extraAttemptsDone = 0))
        assertFalse(ConnectRetry.shouldRetry(HttpStatusException(401, "HTTP 401", null), post = false, extraAttemptsDone = 0))
        assertFalse(ConnectRetry.shouldRetry(IllegalStateException("x"), post = false, extraAttemptsDone = 0))
    }
}
