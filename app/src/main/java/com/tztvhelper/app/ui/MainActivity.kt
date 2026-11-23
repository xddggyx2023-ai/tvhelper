package com.tztvhelper.app.ui

import com.tztvhelper.app.core.adb.Adb
import com.tztvhelper.app.core.adb.AppEntry
import com.tztvhelper.app.core.adb.DeviceInfo
import com.tztvhelper.app.core.discovery.Net
import com.tztvhelper.app.data.Pkgs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0B1220)
private val CardBg = Color(0xFF16213A)
private val Accent = Color(0xFF4DA3FF)
private val Good = Color(0xFF7CF0C0)
private val Warn = Color(0xFFFFB86B)
private val Bad = Color(0xFFFF7B7B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Adb.init(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    background = Bg,
                    surface = CardBg,
                    onPrimary = Color(0xFF04121F)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    App()
                }
            }
        }
    }
}

private enum class Screen { HOME, CONNECTING, DEVICE }

private enum class Tab { OVERVIEW, APPS, INSTALL }

@Composable
private fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.HOME) }
    var localIp by remember { mutableStateOf(Net.localIpv4().orEmpty()) }
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    var manualIp by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var attempts by remember { mutableStateOf(0) }
    var lastError by remember { mutableStateOf("") }
    var device by remember { mutableStateOf<DeviceInfo?>(null) }
    var connectJob by remember { mutableStateOf<Job?>(null) }
    var pendingDisable by remember { mutableStateOf<AppEntry?>(null) }
    var pendingUninstall by remember { mutableStateOf<AppEntry?>(null) }

    fun startScan() {
        if (scanning) return
        scope.launch {
            scanning = true
            found = emptyList()
            progress = 0 to 0
            message = ""
            val ip = localIp.ifBlank { Net.localIpv4().orEmpty() }
            localIp = ip
            if (ip.isBlank()) {
                scanning = false
                message = "手机没有连上 Wi-Fi。请先连到和电视同一个 Wi-Fi。"
                return@launch
            }
            val res = Net.scanPort5555(ip) { done, total -> progress = done to total }
            found = res
            scanning = false
            message = if (res.isEmpty()) {
                "没有扫描到任何设备。最常见的原因：电视上的 ADB 调试还没打开。"
            } else {
                "找到 ${res.size} 个可能的设备，点一下开始连接。"
            }
        }
    }

    fun startConnect(host: String) {
        screen = Screen.CONNECTING
        attempts = 0
        lastError = ""
        message = ""
        connectJob?.cancel()
        connectJob = scope.launch {
            val result = Adb.connect(host) { a, e ->
                attempts = a
                lastError = e
            }
            result.onSuccess {
                device = it
                screen = Screen.DEVICE
            }.onFailure {
                message = it.message ?: "连接失败"
                screen = Screen.HOME
            }
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            localIp = localIp,
            scanning = scanning,
            progress = progress,
            found = found,
            manualIp = manualIp,
            onManualIpChange = { manualIp = it },
            onScan = ::startScan,
            onConnect = ::startConnect,
            message = message
        )

        Screen.CONNECTING -> ConnectingScreen(
            host = "连接中",
            attempts = attempts,
            lastError = lastError,
            onCancel = {
                connectJob?.cancel()
                screen = Screen.HOME
            }
        )

        Screen.DEVICE -> {
            val d = device
            if (d == null) {
                screen = Screen.HOME
            } else {
                DeviceScreen(
                    device = d,
                    onDisconnect = {
                        Adb.disconnect()
                        device = null
                        screen = Screen.HOME
                        localIp = Net.localIpv4().orEmpty()
                    },
                    pendingDisable = pendingDisable,
                    pendingUninstall = pendingUninstall,
                    onAskDisable = { pendingDisable = it },
                    onAskUninstall = { pendingUninstall = it },
                    onCancelDialog = {
                        pendingDisable = null
                        pendingUninstall = null
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    localIp: String,
    scanning: Boolean,
    progress: Pair<Int, Int>,
    found: List<String>,
    manualIp: String,
    onManualIpChange: (String) -> Unit,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    message: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("兔子电视助手", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "手机和电视连同一个 Wi-Fi，先打开电视上的 ADB 调试，再点下面的按钮。",
            color = Color(0xFFA9B6CC), fontSize = 14.sp
        )

        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("本机 Wi-Fi 地址", color = Color(0xFFA9B6CC), fontSize = 13.sp)
                Text(
                    localIp.ifBlank { "未连接 Wi-Fi" },
                    color = if (localIp.isBlank()) Warn else Good,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Button(
            onClick = onScan,
            enabled = !scanning,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Text(if (scanning) "正在搜索…" else "搜索附近的电视", fontSize = 17.sp)
        }

        if (scanning) {
            val total = progress.second.coerceAtLeast(1)
            LinearProgressIndicator(
                progress = { progress.first.toFloat() / total },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "已检查 ${progress.first}/${progress.second} 个地址",
                color = Color(0xFFA9B6CC),
                fontSize = 13.sp
            )
        }

        if (message.isNotBlank()) {
            Text(message, color = Warn, fontSize = 14.sp)
        }

        if (found.isNotEmpty()) {
            Text("找到的设备", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 16.sp)
            found.forEach { host ->
                Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(host, color = Color.White, fontSize = 16.sp)
                            Text("5555 端口开着", color = Color(0xFFA9B6CC), fontSize = 12.sp)
                        }
                        Button(onClick = { onConnect(host) }) { Text("连接") }
                    }
                }
            }
        }

        HorizontalDivider(color = Color(0xFF22304D))

        Text("没搜到？手动输入电视 IP", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 15.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = manualIp,
                onValueChange = onManualIpChange,
                singleLine = true,
                placeholder = { Text("例如 192.168.1.23") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = { onConnect(manualIp.trim()) },
                enabled = manualIp.trim().count { it == '.' } == 3
            ) { Text("连接") }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "提示：电视上的 ADB 开关各品牌位置不一样。小米在“账号与安全”，" +
                "TCL/雷鸟在“本机信息”里按上下左右，创维/酷开在工厂菜单里。",
            color = Color(0xFF8794AB),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ConnectingScreen(
    host: String,
    attempts: Int,
    lastError: String,
    onCancel: () -> Unit
) {
    BackHandler { onCancel() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Accent)
        Spacer(Modifier.height(24.dp))
        Text("正在连接电视…", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))

        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("现在请看电视屏幕", color = Good, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    "如果电视弹出「允许 USB 调试吗」或「是否允许调试」，" +
                        "请用遥控器选「允许 / 确定」。点完之后这里会自动连上。",
                    color = Color(0xFFD5DEEE),
                    fontSize = 14.sp
                )
                Text(
                    "已经尝试 $attempts 次。没有弹窗的话，说明电视上的 ADB 调试还没打开。",
                    color = Color(0xFFA9B6CC),
                    fontSize = 13.sp
                )
                if (lastError.isNotBlank()) {
                    Text("最近一次错误：$lastError", color = Warn, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("取消")
        }
    }
}

@Composable
private fun DeviceScreen(
    device: DeviceInfo,
    onDisconnect: () -> Unit,
    pendingDisable: AppEntry?,
    pendingUninstall: AppEntry?,
    onAskDisable: (AppEntry) -> Unit,
    onAskUninstall: (AppEntry) -> Unit,
    onCancelDialog: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.OVERVIEW) }
    var message by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var onlyThird by remember { mutableStateOf(false) }
    var installResult by remember { mutableStateOf("") }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            installResult = "正在安装，请稍等…"
            val doc = DocumentFile.fromSingleUri(ctx, uri)
            if (doc == null || !doc.name.orEmpty().endsWith(".apk", true)) {
                installResult = "这不是 APK 文件，请重新选择"
                return@launch
            }
            Adb.installApk(ctx, doc).fold(
                onSuccess = { installResult = "安装完成：${doc.name}" },
                onFailure = { installResult = "安装失败：${it.message}" }
            )
        }
    }

    fun reload() {
        scope.launch {
            loadingApps = true
            Adb.listApps().fold(
                onSuccess = { apps = it },
                onFailure = { message = "读取应用列表失败：${it.message}" }
            )
            loadingApps = false
        }
    }

    BackHandler { onDisconnect() }

    Scaffold(containerColor = Bg) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Android ${device.release} · ${device.host}",
                        color = Good,
                        fontSize = 12.sp
                    )
                }
                TextButton(onClick = onDisconnect) { Text("断开") }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabChip("概览", tab == Tab.OVERVIEW) { tab = Tab.OVERVIEW }
                TabChip("应用管理", tab == Tab.APPS) {
                    tab = Tab.APPS
                    if (apps.isEmpty()) reload()
                }
                TabChip("装应用", tab == Tab.INSTALL) { tab = Tab.INSTALL }
            }

            if (message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(message, color = Warn, fontSize = 13.sp)
            }

            Spacer(Modifier.height(12.dp))

            when (tab) {
                Tab.OVERVIEW -> Overview(device)
                Tab.APPS -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("搜包名或中文名") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { onlyThird = !onlyThird }) {
                            Text(if (onlyThird) "只看第三方" else "全部应用", fontSize = 12.sp)
                        }
                    }
                    if (loadingApps) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    val filtered = apps.filter { a ->
                        (!onlyThird || !a.system) && (
                            query.isBlank() ||
                                a.pkg.contains(query, true) ||
                                Pkgs.label(a.pkg).contains(query, true)
                            )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.pkg }) { app ->
                            AppRow(
                                app = app,
                                onAskDisable = onAskDisable,
                                onAskUninstall = onAskUninstall,
                                onEnable = { entry ->
                                    scope.launch {
                                        message = "正在恢复…"
                                        Adb.enable(entry.pkg).fold(
                                            onSuccess = { message = "已恢复 ${Pkgs.label(entry.pkg)}" },
                                            onFailure = { message = "恢复失败：${it.message}" }
                                        )
                                        reload()
                                    }
                                }
                            )
                        }
                    }
                }

                Tab.INSTALL -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("从手机装到电视", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(
                                "先在手机上下载好电视版的 APK，然后点下面的按钮选中它。",
                                color = Color(0xFFA9B6CC), fontSize = 13.sp
                            )
                        }
                    }
                    Button(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) { Text("选择手机里的 APK") }
                    if (installResult.isNotBlank()) {
                        Text(
                            installResult,
                            color = if (installResult.startsWith("安装完成")) Good else Warn,
                            fontSize = 14.sp
                        )
                    }
                    Text(
                        "安装失败最常见的原因：电视禁止安装未知来源应用；或者 APK 是手机版，" +
                            "电视装得上但用不了（需要 TV 版 / 横屏版）。",
                        color = Color(0xFF8794AB), fontSize = 12.sp
                    )
                }
            }
        }
    }

    if (pendingDisable != null) {
        val app = pendingDisable!!
        AlertDialog(
            onDismissRequest = onCancelDialog,
            title = { Text("确认禁用？") },
            text = {
                Text(
                    "将禁用：${Pkgs.label(app.pkg)}\n${app.pkg}\n\n" +
                        "禁用后这个应用会从电视上消失，但不会删除，可以随时恢复。" +
                        "不要禁用不认识的系统应用。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onCancelDialog()
                    scope.launch {
                        message = "正在禁用…"
                        Adb.disable(app.pkg).fold(
                            onSuccess = { message = "已禁用 ${Pkgs.label(app.pkg)}" },
                            onFailure = { message = "禁用失败：${it.message}" }
                        )
                        reload()
                    }
                }) { Text("确认禁用", color = Bad) }
            },
            dismissButton = { TextButton(onClick = onCancelDialog) { Text("取消") } }
        )
    }

    if (pendingUninstall != null) {
        val app = pendingUninstall!!
        AlertDialog(
            onDismissRequest = onCancelDialog,
            title = { Text("确认卸载？") },
            text = { Text("将卸载：${Pkgs.label(app.pkg)}\n${app.pkg}\n\n卸载后要重新装回来才能用。") },
            confirmButton = {
                TextButton(onClick = {
                    onCancelDialog()
                    scope.launch {
                        message = "正在卸载…"
                        Adb.uninstall(app.pkg).fold(
                            onSuccess = { message = "已卸载 ${Pkgs.label(app.pkg)}" },
                            onFailure = { message = "卸载失败：${it.message}" }
                        )
                        reload()
                    }
                }) { Text("确认卸载", color = Bad) }
            },
            dismissButton = { TextButton(onClick = onCancelDialog) { Text("取消") } }
        )
    }
}

@Composable
private fun TabChip(text: String, active: Boolean, onClick: () -> Unit) {
    if (active) {
        Button(onClick = onClick) { Text(text, fontSize = 13.sp) }
    } else {
        OutlinedButton(onClick = onClick) { Text(text, fontSize = 13.sp) }
    }
}

@Composable
private fun Overview(device: DeviceInfo) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        InfoCard("品牌 / 型号", device.title.ifBlank { "未读到" })
        InfoCard("系统版本", "Android ${device.release}（SDK ${device.sdk}）")
        InfoCard("芯片架构", device.abi)
        InfoCard("连接地址", "${device.host}:${device.port}")

        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("下面这些操作会改动电视", color = Warn, fontWeight = FontWeight.SemiBold)
                Text(
                    "1. 禁用预装应用：只禁用你认识的，禁用前会二次确认。\n" +
                        "2. 换桌面：部分电视锁定了默认桌面，改了可能不生效。\n" +
                        "3. 出问题先做「恢复」：把禁用的应用全部开回来。",
                    color = Color(0xFFA9B6CC), fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun InfoCard(label: String, value: String) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = Color(0xFFA9B6CC), fontSize = 12.sp)
            Text(value, color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    onAskDisable: (AppEntry) -> Unit,
    onAskUninstall: (AppEntry) -> Unit,
    onEnable: (AppEntry) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        Pkgs.label(app.pkg),
                        color = Color.White,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(app.pkg, color = Color(0xFF8794AB), fontSize = 11.sp)
                }
                if (app.system) Tag("系统", Color(0xFF3B4B6B))
                if (app.disabled) Tag("已禁用", Color(0xFF6B3B3B))
            }

            when {
                Pkgs.isCritical(app.pkg) -> {
                    Text("系统关键应用，不提供禁用", color = Color(0xFF8794AB), fontSize = 12.sp)
                }

                app.disabled -> {
                    Button(onClick = { onEnable(app) }) { Text("恢复", fontSize = 13.sp) }
                }

                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onAskDisable(app) }) {
                            Text("禁用", fontSize = 13.sp)
                        }
                        if (!app.system) {
                            OutlinedButton(onClick = { onAskUninstall(app) }) {
                                Text("卸载", fontSize = 13.sp, color = Bad)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = Color.White, fontSize = 11.sp)
    }
}
