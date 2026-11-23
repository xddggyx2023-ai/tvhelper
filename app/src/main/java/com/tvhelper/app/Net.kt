package com.tvhelper.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** 局域网扫描：老电视不会广播 mDNS，所以只能老老实实扫 5555 端口 */
object Net {

    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun prefixOf(ip: String): String? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return parts.take(3).joinToString(".")
    }

    /**
     * 扫描同一网段的 1..254，看哪些地址开着 5555 端口。
     * 64 个并发，单个 400ms 超时，通常 3~6 秒出结果。
     */
    suspend fun scanPort5555(
        localIp: String,
        port: Int = 5555,
        timeoutMs: Int = 400,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<String> = withContext(Dispatchers.IO) {
        val prefix = prefixOf(localIp) ?: return@withContext emptyList()
        val hosts = (1..254).map { "$prefix.$it" }.filter { it != localIp }
        val found = mutableListOf<String>()
        val sem = Semaphore(64)
        val done = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            hosts.map { host ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        if (isPortOpen(host, port, timeoutMs)) {
                            synchronized(found) { found.add(host) }
                        }
                    }
                    onProgress(done.incrementAndGet(), hosts.size)
                }
            }.awaitAll()
        }
        found.sorted()
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)
}
