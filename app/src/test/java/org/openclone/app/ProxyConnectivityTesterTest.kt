package org.openclone.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyConnectivityTesterTest {
    @Test
    fun completesHttpConnectHandshake() {
        ServerSocket(0).use { server ->
            val finished = CountDownLatch(1)
            val thread = Thread {
                try {
                    server.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                        while (reader.readLine()?.isNotEmpty() == true) { }
                        socket.getOutputStream().write("HTTP/1.1 200 Connection established\\r\\n\\r\\n".toByteArray(Charsets.US_ASCII))
                        socket.getOutputStream().flush()
                    }
                } finally {
                    finished.countDown()
                }
            }
            thread.start()
            ProxyConnectivityTester.test(ProxyProfile("Local HTTP", "127.0.0.1", server.localPort, "HTTP_CONNECT"), 1500)
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun completesSocks5NoAuthHandshake() {
        ServerSocket(0).use { server ->
            val finished = CountDownLatch(1)
            val thread = Thread {
                try {
                    server.accept().use { socket ->
                        val input = java.io.DataInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()
                        val greeting = ByteArray(3)
                        input.readFully(greeting)
                        output.write(byteArrayOf(5, 0))
                        output.flush()
                        val header = ByteArray(5)
                        input.readFully(header)
                        val hostLength = header[4].toInt() and 0xff
                        val remainder = ByteArray(hostLength + 2)
                        input.readFully(remainder)
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                        output.flush()
                    }
                } finally {
                    finished.countDown()
                }
            }
            thread.start()
            ProxyConnectivityTester.test(ProxyProfile("Local SOCKS", "127.0.0.1", server.localPort, "SOCKS5"), 1500)
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        }
    }
}
