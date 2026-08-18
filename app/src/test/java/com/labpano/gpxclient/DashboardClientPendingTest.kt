package com.labpano.gpxclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DashboardClientPendingTest {
    @Test
    fun requestsMediaOnlyItemsSoErrorVideosCanReceivePhoneBackup() {
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val handled = executor.submit {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readHttpLine(input)
                val target = requestLine.split(' ')[1]
                assertTrue(target.startsWith("/api/v1/pending-gpx?"))
                assertTrue(target.contains("includeMediaOnly=1"))
                while (true) if (readHttpLine(input).isEmpty()) break
                val body = """{"apiVersion":3,"offset":0,"limit":200,"total":1,"nextOffset":null,"items":[{"id":"err","status":"ERROR","completedAt":"2026-08-17T14:20:00.000Z","videoName":"260817_161856570.mp4","videoPath":"/out.mp4","gpxName":"","gpxPath":"","gpxSizeBytes":0,"videoStartMillis":1000,"videoEndMillis":5000,"downloadUrl":""}]}""".toByteArray()
                val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().use { output ->
                    output.write(header.toByteArray(Charsets.ISO_8859_1))
                    output.write(body)
                    output.flush()
                }
            }
        }
        try {
            val items = DashboardClient().fetchPendingGpx("http://127.0.0.1:${server.localPort}")
            handled.get(5, TimeUnit.SECONDS)
            assertEquals(1, items.size)
            assertEquals("ERROR", items.single().status)
            assertEquals(1000L, items.single().videoStartMillis)
            assertEquals(5000L, items.single().videoEndMillis)
        } finally {
            runCatching { server.close() }
            executor.shutdownNow()
        }
    }

    private fun readHttpLine(input: BufferedInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            check(value >= 0) { "Unexpected end of HTTP request" }
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        return bytes.toString(Charsets.ISO_8859_1.name())
    }
}
