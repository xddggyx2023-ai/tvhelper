package com.tztvhelper.app.core.adb

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import com.flyfishxu.kadb.install
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import java.io.File

/** 电视的基本信息（连上之后自动读取，不用用户手输） */
data class DeviceInfo(
    val host: String,
    val port: Int,
    val brand: String,
    val model: String,
    val manufacturer: String,
    val release: String,
    val sdk: String,
    val abi: String
) {
    val title: String get() = listOf(manufacturer, brand, model)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" ")
}

data class AppEntry(
    val pkg: String,
    val system: Boolean,
    val disabled: Boolean
)

/**
 * ADB 会话管理。
 *
 * 注意：ADB 的密钥必须持久化保存在 APP 私有目录，
 * 否则每次启动都会生成新密钥，电视会反复弹“允许调试”。
 */
object Adb {

    @Volatile
    private var kadb: Kadb? = null

    @Volatile
    var info: DeviceInfo? = null
        private set

    fun init(context: Context) {
        val keyFile = File(context.filesDir, "adbkey.pem")
        KadbCert.configure(store = OkioFilePrivateKeyStore(keyFile.absolutePath.toPath()))
    }

    fun isConnected(): Boolean = kadb?.connectionCheck() == true

    fun disconnect() {
        runCatching { kadb?.close() }
        kadb = null
        info = null
    }

    /**
     * 反复尝试连接，直到成功或超时。
     *
     * 为什么要循环：第一次连接时电视会弹“允许USB调试吗”，
     * 用户要用遥控器去点“允许”。在点之前，连接会失败或一直挂着。
     * 所以我们每 2.5 秒重试一次，用户点完允许，下一轮就连上了。
     */
    suspend fun connect(
        host: String,
        port: Int = 5555,
        timeoutMs: Long = 180_000,
        onAttempt: (attempt: Int, lastError: String) -> Unit
    ): Result<DeviceInfo> = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        var lastError = ""

        while (System.currentTimeMillis() < deadline) {
            attempt++
            val probe = Kadb.create(host, port, connectTimeout = 4_000, socketTimeout = 8_000)
            try {
                val res = probe.shell("echo __tvhelper_ok__")
                if (res.allOutput.contains("__tvhelper_ok__")) {
                    val d = readInfo(probe, host, port)
                    kadb?.let { runCatching { it.close() } }
                    // 正式会话不设读超时：装 APK 可能要几十秒
                    kadb = Kadb.create(host, port, connectTimeout = 5_000, socketTimeout = 0)
                    info = d
                    return@withContext Result.success(d)
                }
                lastError = "电视没有返回预期结果"
            } catch (t: Throwable) {
                lastError = (t.message ?: t.javaClass.simpleName).take(120)
            } finally {
                runCatching { probe.close() }
            }
            onAttempt(attempt, lastError)
            delay(2_500)
        }
        Result.failure(IllegalStateException("连接超时。最后一次错误：$lastError"))
    }

    private fun readInfo(k: Kadb, host: String, port: Int): DeviceInfo {
        fun prop(name: String): String =
            runCatching { k.shell("getprop $name").output.trim() }.getOrDefault("")

        return DeviceInfo(
            host = host,
            port = port,
            brand = prop("ro.product.brand"),
            model = prop("ro.product.model"),
            manufacturer = prop("ro.product.manufacturer"),
            release = prop("ro.build.version.release"),
            sdk = prop("ro.build.version.sdk"),
            abi = prop("ro.product.cpu.abi")
        )
    }

    suspend fun raw(command: String): Result<String> = withContext(Dispatchers.IO) {
        val k = kadb ?: return@withContext Result.failure(IllegalStateException("还没有连接电视"))
        runCatching {
            val r = k.shell(command)
            r.allOutput.ifBlank { r.output }
        }
    }

    suspend fun listApps(): Result<List<AppEntry>> = withContext(Dispatchers.IO) {
        val k = kadb ?: return@withContext Result.failure(IllegalStateException("还没有连接电视"))
        runCatching {
            val user = pkgSet(k, "pm list packages -3")
            val system = pkgSet(k, "pm list packages -s")
            val disabled = pkgSet(k, "pm list packages -d")
            val all = (user + system)
            all.map { pkg ->
                AppEntry(pkg = pkg, system = pkg in system, disabled = pkg in disabled)
            }.sortedWith(compareBy({ it.system }, { it.pkg }))
        }
    }

    private fun pkgSet(k: Kadb, cmd: String): Set<String> =
        k.shell(cmd).output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter { it.isNotBlank() }
            .toSet()

    /** 禁用预装应用。执行后一定回读状态，失败就报失败，绝不假装成功。 */
    suspend fun disable(pkg: String): Result<String> = withContext(Dispatchers.IO) {
        val k = kadb ?: return@withContext Result.failure(IllegalStateException("还没有连接电视"))
        runCatching {
            val out = k.shell("pm disable-user --user 0 $pkg").allOutput
            val stillEnabled = pkgSet(k, "pm list packages -e").contains(pkg)
            if (stillEnabled) {
                error("系统拒绝了这次禁用。电视返回：${out.trim().take(160)}")
            }
            out.trim()
        }
    }

    suspend fun enable(pkg: String): Result<String> = withContext(Dispatchers.IO) {
        val k = kadb ?: return@withContext Result.failure(IllegalStateException("还没有连接电视"))
        runCatching {
            val out = k.shell("pm enable $pkg").allOutput
            val disabledNow = pkgSet(k, "pm list packages -d").contains(pkg)
            if (disabledNow) error("恢复失败。电视返回：${out.trim().take(160)}")
            out.trim()
        }
    }

    suspend fun uninstall(pkg: String): Result<String> = withContext(Dispatchers.IO) {
        val k = kadb ?: return@withContext Result.failure(IllegalStateException("还没有连接电视"))
        runCatching {
            val out = k.shell("pm uninstall --user 0 $pkg").allOutput
            if (!out.contains("Success")) error("卸载失败：${out.trim().take(160)}")
            out.trim()
        }
    }

    suspend fun installApk(context: Context, doc: DocumentFile): Result<String> =
        withContext(Dispatchers.IO) {
            val k = kadb ?: return@withContext Result.failure(IllegalStateException("还没有连接电视"))
            runCatching {
                k.install(doc, context)
                "安装完成"
            }
        }
}
