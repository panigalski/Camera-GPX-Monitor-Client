package com.labpano.gpxclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DashboardClientUploadTest {
    @Test
    fun uploadsBackupGpxAndRequiresCameraChecksumAcknowledgement() {
        val body = "<?xml version=\"1.0\"?><gpx version=\"1.1\"></gpx>".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val handled = executor.submit {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readHttpLine(input)
                assertEquals("POST", requestLine.substringBefore(' '))
                val target = requestLine.split(' ')[1]
                assertEquals("/api/v1/backup-gpx-upload", target.substringBefore('?'))
                val query = target.substringAfter('?', "").split('&').associate { pair ->
                    val pieces = pair.split('=', limit = 2)
                    URLDecoder.decode(pieces[0], "UTF-8") to URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
                }
                assertEquals("GOOD", query["status"])
                assertEquals("16-08-2026", query["subfolder"])
                assertEquals("260816_102735266_backup.gpx", query["filename"])
                assertEquals(sha, query["sha256"])

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readHttpLine(input)
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) headers[line.substring(0, separator).lowercase(Locale.US)] = line.substring(separator + 1).trim()
                }
                val length = headers["content-length"]!!.toInt()
                val received = ByteArray(length)
                var offset = 0
                while (offset < received.size) {
                    val read = input.read(received, offset, received.size - offset)
                    check(read >= 0)
                    offset += read
                }
                assertEquals(body.toList(), received.toList())

                val responseBody = """{"ok":true,"destination":"/output/GOOD/16-08-2026/260816_102735266_backup.gpx","sizeBytes":${body.size},"sha256":"$sha","alreadyPresent":false}""".toByteArray()
                val responseHeader = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${responseBody.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().use { output ->
                    output.write(responseHeader.toByteArray(Charsets.ISO_8859_1))
                    output.write(responseBody)
                    output.flush()
                }
            }
        }
        try {
            val result = DashboardClient().uploadBackupGpx(
                baseAddress = "http://127.0.0.1:${server.localPort}",
                status = "GOOD",
                dateFolder = "16-08-2026",
                fileName = "260816_102735266_backup.gpx",
                bytes = body,
                sha256 = sha
            )
            handled.get(5, TimeUnit.SECONDS)
            assertEquals(body.size.toLong(), result.sizeBytes)
            assertEquals(sha, result.sha256)
            assertFalse(result.alreadyPresent)
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
