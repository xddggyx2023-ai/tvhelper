package com.tztvhelper.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tztvhelper.app.core.AppConfig
import com.tztvhelper.app.core.adb.Adb
import com.tztvhelper.app.core.kb.Brand
import com.tztvhelper.app.core.kb.Credibility
import com.tztvhelper.app.core.kb.KbRepository
import com.tztvhelper.app.core.kb.MatchLevel
import com.tztvhelper.app.core.kb.MatchResult
import com.tztvhelper.app.core.kb.Method
import com.tztvhelper.app.core.kb.MethodStatus
import com.tztvhelper.app.core.kb.TvModel
import com.tztvhelper.app.core.kb.VerifyResult
import com.tztvhelper.app.core.search.SaveResult
import com.tztvhelper.app.core.search.SearchError
import com.tztvhelper.app.core.search.SearchOutcome
import com.tztvhelper.app.core.search.SearchProviderFactory
import com.tztvhelper.app.core.search.SearchQuery
import com.tztvhelper.app.core.search.SearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GBg = Color(0xFF0B1220)
private val GCard = Color(0xFF16213A)
private val GAccent = Color(0xFF4DA3FF)
private val GGood = Color(0xFF7CF0C0)
private val GWarn = Color(0xFFFFB86B)
private val GBad = Color(0xFFFF7B7B)
private val GDim = Color(0xFFA9B6CC)

class GuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = GAccent,
                    background = GBg,
                    surface = GCard,
                    onPrimary = Color(0xFF04121F)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = GBg) {
                    GuideScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun GuideScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { KbRepository(ctx.applicationContext) }

    var brands by remember { mutableStateOf<List<Brand>>(emptyList()) }
    var brand by remember { mutableStateOf<Brand?>(null) }
    var model by remember { mutableStateOf("") }
    var osVersion by remember { mutableStateOf("") }
    var resolvedModel by remember { mutableStateOf<TvModel?>(null) }
    var result by remember { mutableStateOf<MatchResult?>(null) }
    var current by remember { mutableStateOf<Method?>(null) }
    var note by remember { mutableStateOf("") }
    var seeded by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<SearchOutcome?>(null) }
    var selectedHit by remember { mutableStateOf<Int?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchNote by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(Adb.isConnected() && Adb.info != null) }
    var failDialog by remember { mutableStateOf(false) }
    val searchStatus = remember { SearchProviderFactory.describe(ctx) }

    LaunchedEffect(Unit) {
        repo.ensureSeeded()
        brands = repo.brands()
        brand = brands.firstOrNull()
        seeded = true
    }

    LaunchedEffect(brand?.id, model) {
        resolvedModel = brand?.let { repo.resolveModel(it.id, model) }
    }

    // 只有真的连着电视，才开启「APP 检测」入口；每 2 秒刷新状态
    LaunchedEffect(Unit) {
        while (true) {
            connected = Adb.isConnected() && Adb.info != null
            delay(2_000)
        }
    }

    fun clearSearch() {
        outcome = null
        selectedHit = null
        searchNote = ""
    }

    fun query() {
        val b = brand ?: return
        val r = repo.find(b.id, resolvedModel?.id, osVersion.ifBlank { null })
        result = r
        current = (r as? MatchResult.Hit)?.method
        note = ""
    }

    fun verify(res: VerifyResult, reason: String? = null) {
        val m = current ?: return
        val updated = repo.recordVerification(
            m.id, res, osVersion.ifBlank { null }, model.ifBlank { null }, reason
        )
        if (updated == null) {
            note = "没记上：APP 当前没有真实连接（或读不到设备信息），不能算「APP 实测成功」。"
            return
        }
        current = updated
        note = when (res) {
            VerifyResult.SUCCESS_APP -> "已记录：APP 实测成功（有真实连接依据），这条方法升为推荐。"
            VerifyResult.SUCCESS_USER -> "已记录成功。累计 2 次成功后会升为推荐。"
            VerifyResult.FAILURE -> "已记录失败，原因已存档。原成功记录保留，方法转入待复核。"
        }
    }

    fun runSearch() {
        val b = brand ?: return
        scope.launch {
            searching = true
            searchNote = ""
            selectedHit = null
            val svc = SearchService(
                repo.database(), repo,
                SearchProviderFactory.create(ctx), AppConfig.searchCacheTtlMs(ctx)
            )
            outcome = withContext(Dispatchers.IO) {
                svc.search(
                    SearchQuery(
                        brandId = b.id,
                        brand = b.name,
                        model = model.ifBlank { null },
                        osVersion = osVersion.ifBlank { null }
                    )
                )
            }
            searching = false
        }
    }

    fun saveCandidate() {
        val b = brand ?: return
        val o = outcome ?: return
        val idx = selectedHit ?: return
        val svc = SearchService(
            repo.database(), repo,
            SearchProviderFactory.create(ctx), AppConfig.searchCacheTtlMs(ctx)
        )
        searchNote = when (
            val r = svc.saveAsCandidate(
                outcome = o,
                brandId = b.id,
                modelId = resolvedModel?.id,
                modelText = model.ifBlank { null },
                osVersion = osVersion.ifBlank { null },
                hitIndex = idx,
                title = "${b.name} 开启方法（来自搜索）"
            )
        ) {
            is SaveResult.Saved -> "已存为候选方法，编号 ${r.methodId}。它在验证成功前不会变成推荐。"
            SaveResult.MockBlocked -> "模拟数据不能写进知识库——否则以后分不清哪条是真的。"
            SaveResult.NoSteps -> "这条结果里没有可用的步骤，不能存成教程。"
            SaveResult.NoHit -> "没有选中任何一条结果。"
            SaveResult.IdentityMismatch -> "搜索时的品牌/型号/版本和现在不一致，不能存。请重新搜索。"
            SaveResult.InvalidUrl -> "这条结果的链接不合法，不能入库。"
            is SaveResult.Failed -> "保存失败：${r.reason}"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.weight(1f))
            Text("开启 ADB 教程", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(56.dp))
        }

        Text(
            "先告诉 APP 你的电视是什么品牌，它会从本地知识库找对应的开启方法。找不到会直说，不会编。",
            color = GDim,
            fontSize = 13.sp
        )

        Text("电视品牌", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!seeded) {
                Text("正在加载知识库…", color = GDim, fontSize = 13.sp)
            }
            brands.forEach { b ->
                FilterChip(
                    selected = b.id == brand?.id,
                    onClick = { brand = b; result = null; current = null; clearSearch() },
                    label = { Text(b.name) }
                )
            }
        }

        OutlinedTextField(
            value = model,
            onValueChange = { model = it; result = null; current = null; clearSearch() },
            singleLine = true,
            label = { Text("电视型号（可留空，先按品牌通用教程）") },
            placeholder = { Text("例如 小米4A / 55A7 / 65E8G") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = osVersion,
            onValueChange = { osVersion = it; result = null; current = null; clearSearch() },
            singleLine = true,
            label = { Text("系统版本（可留空）") },
            placeholder = { Text("例如 8.1 / 9 / Android 11") },
            modifier = Modifier.fillMaxWidth()
        )

        when {
            model.isBlank() -> Text(
                "型号留空：按品牌通用教程查。",
                color = GDim, fontSize = 11.sp
            )
            resolvedModel != null -> Text(
                "已识别型号：${resolvedModel!!.displayName}，优先匹配这个型号的专属教程。",
                color = GGood, fontSize = 11.sp
            )
            else -> Text(
                "知识库里没有「$model」这个型号：只会按 ${brand?.name ?: "品牌"} 通用教程查，不会假装是型号专属。",
                color = GWarn, fontSize = 11.sp
            )
        }

        Button(
            onClick = { query() },
            enabled = brand != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) { Text("查本地知识库", fontSize = 16.sp) }

        OutlinedButton(
            onClick = { runSearch() },
            enabled = brand != null && !searching,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) { Text(if (searching) "搜索中…" else "联网搜索这个品牌 / 型号", fontSize = 14.sp) }

        Text(
            searchStatus,
            color = if (searchStatus.contains("模拟")) GWarn else GDim,
            fontSize = 11.sp
        )

        outcome?.let { o ->
            SearchOutcomeCard(
                o = o,
                selectedHit = selectedHit,
                onSelect = { selectedHit = it; searchNote = "" },
                onSave = { saveCandidate() }
            )
        }
        if (searchNote.isNotBlank()) Text(searchNote, color = GWarn, fontSize = 12.sp)

        when (val r = result) {
            null -> Unit

            is MatchResult.NotFound -> Card(colors = CardDefaults.cardColors(containerColor = GCard)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("没有找到可靠方法", color = GWarn, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "本地知识库里没有「${brand?.name ?: ""} ${model.ifBlank { "（未填型号）" }}」" +
                            "${if (osVersion.isBlank()) "" else " 系统 $osVersion"} 的开启教程。\n\n" +
                            "下一步可以：① 把型号反馈给我们，等待补充；② 连接失败后用「全端口扫描」确认电视到底有没有在监听；" +
                            "③ 试试品牌通用教程（型号留空再查一次）。",
                        color = GDim, fontSize = 13.sp
                    )
                    Text("实时联网搜索：$searchStatus", color = GWarn, fontSize = 12.sp)
                }
            }

            is MatchResult.Hit -> MethodCard(
                m = current ?: r.method,
                level = r.level,
                appDetectionEnabled = connected,
                onSuccessApp = { verify(VerifyResult.SUCCESS_APP) },
                onSuccessUser = { verify(VerifyResult.SUCCESS_USER) },
                onFail = { failDialog = true }
            )
        }

        if (note.isNotBlank()) {
            Text(note, color = GGood, fontSize = 13.sp)
        }

        if (current != null) {
            val m = current!!
            val hist = remember(m.id, m.successCount, m.failCount) { repo.history(m.id) }
            if (hist.isNotEmpty()) {
                Text("这条方法的记录", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                hist.take(6).forEach { h ->
                    Text(
                        "${fmt(h.createdAt)} · ${h.change} · ${h.detail}",
                        color = GDim, fontSize = 12.sp
                    )
                }
            }
        }

        if (failDialog) {
            FailReasonDialog(
                onDismiss = { failDialog = false },
                onSkip = { failDialog = false; verify(VerifyResult.FAILURE, null) },
                onRecord = { text -> failDialog = false; verify(VerifyResult.FAILURE, text) }
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "说明：当前 7 个品牌的通用教程都是「未验证」状态，来自公开资料的整理。" +
                "你每验证一次，APP 都会记下来；验证成功两次以上，这条方法才会变成推荐。",
            color = Color(0xFF8794AB), fontSize = 12.sp
        )
    }
}

@Composable
private fun MethodCard(
    m: Method,
    level: MatchLevel,
    appDetectionEnabled: Boolean,
    onSuccessApp: () -> Unit,
    onSuccessUser: () -> Unit,
    onFail: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = GCard)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusChip(m.status)
            }

            Text(
                when (level) {
                    MatchLevel.EXACT_MODEL_OS -> "匹配：型号 + 系统版本"
                    MatchLevel.MODEL -> "匹配：型号"
                    MatchLevel.BRAND_GENERIC -> "匹配：品牌通用（没有该型号的专属教程）"
                },
                color = GAccent, fontSize = 12.sp
            )

            Text(
                "来源：${sourceText(m)} · ${m.confidence.label()} · " +
                    "成功 ${m.successCount} 次 / 失败 ${m.failCount} 次" +
                    (m.lastVerifiedAt?.let { " · 最后验证 ${fmt(it)}" } ?: " · 尚未验证"),
                color = GDim, fontSize = 12.sp
            )

            m.steps.forEach { s ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${s.n}. ${s.title}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (s.path.isNotEmpty()) {
                        Text("路径：" + s.path.joinToString(" → "), color = GAccent, fontSize = 12.sp)
                    }
                    Text(s.detail, color = GDim, fontSize = 13.sp)
                    if (s.note.isNotBlank()) {
                        Text("注意：${s.note}", color = GWarn, fontSize = 12.sp)
                    }
                }
            }

            if (m.notes.isNotBlank()) {
                Text(m.notes, color = GWarn, fontSize = 12.sp)
            }

            Text("这条方法管用吗？", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (!appDetectionEnabled) {
                Text(
                    "APP 现在没有连上电视，所以不能替你判断是否成功。「APP 检测」要真的连上才能用。",
                    color = GWarn, fontSize = 11.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSuccessApp,
                    enabled = appDetectionEnabled,
                    modifier = Modifier.weight(1f)
                ) { Text(if (appDetectionEnabled) "连上了（APP 检测）" else "APP 检测（未连接）", fontSize = 12.sp) }
                OutlinedButton(onClick = onFail, modifier = Modifier.weight(1f)) {
                    Text("没用，失败", color = GBad)
                }
            }
            OutlinedButton(onClick = onSuccessUser, modifier = Modifier.fillMaxWidth()) {
                Text("我按教程开了，手动记一次成功")
            }
        }
    }
}

@Composable
private fun StatusChip(status: MethodStatus) {
    val (text, color) = when (status) {
        MethodStatus.RECOMMENDED -> "推荐" to GGood
        MethodStatus.CANDIDATE -> "候选" to GWarn
        MethodStatus.NEEDS_REVIEW -> "待复核" to GBad
        MethodStatus.REJECTED -> "已否决" to GBad
    }
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

private fun Credibility.label(): String = when (this) {
    Credibility.OFFICIAL -> "官方资料"
    Credibility.FORUM -> "论坛经验"
    Credibility.USER_REPORT -> "用户反馈"
    Credibility.UNVERIFIED -> "未验证"
}

private fun sourceText(m: Method): String {
    if (m.sources.isEmpty()) return "无来源（内部整理）"
    return m.sources.joinToString("；") { it.title.ifBlank { "未命名来源" } }
}

private fun fmt(ts: Long): String =
    if (ts <= 0) "-" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ts))

@Composable
private fun SearchOutcomeCard(
    o: SearchOutcome,
    selectedHit: Int?,
    onSelect: (Int) -> Unit,
    onSave: () -> Unit
) {
    val r = o.response
    Card(colors = CardDefaults.cardColors(containerColor = GCard)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (r.isMock) "模拟搜索结果" else "搜索结果（${r.hits.size} 条）",
                    color = if (r.isMock) GWarn else Color.White,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                if (o.fromCache) Text("来自缓存", color = GDim, fontSize = 11.sp)
            }

            Text(
                "搜索条件：${o.query.brand} ${o.query.model ?: "（未填型号）"} " +
                    (o.query.osVersion?.let { "· 系统 $it" } ?: ""),
                color = GDim, fontSize = 11.sp
            )

            if (r.isMock) {
                Text(
                    "这不是真实联网结果，只是用来验证搜索流程的样例数据，不能写进知识库。",
                    color = GWarn, fontSize = 12.sp
                )
            }

            val err = r.error
            if (err != null) {
                Text(errText(err), color = GBad, fontSize = 13.sp)
            } else {
                Text("这些是网上找到的原始结果，不是 APP 验证过的教程。请自己点选一条再保存。", color = GDim, fontSize = 11.sp)

                r.hits.forEachIndexed { i, h ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(i) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(selected = selectedHit == i, onClick = { onSelect(i) })
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(h.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(h.url, color = GAccent, fontSize = 11.sp)
                            Text(h.snippet, color = GDim, fontSize = 12.sp)
                            Text(
                                "来源：${SearchService.sourceLabel(h.sourceType)} · ${h.credibility.label()} · 抓取 ${fmt(h.fetchedAt)}",
                                color = Color(0xFF8794AB), fontSize = 11.sp
                            )
                            if (h.extractedSteps.isEmpty()) {
                                Text("这条没有可提取的步骤，不能存成教程。", color = GWarn, fontSize = 11.sp)
                            } else {
                                Text("步骤 ${h.extractedSteps.size} 条", color = GGood, fontSize = 11.sp)
                            }
                        }
                    }
                }

                if (o.canSaveToKb) {
                    if (selectedHit == null) {
                        Text("先点选一条结果，才能保存。", color = GWarn, fontSize = 12.sp)
                    }
                    Button(
                        onClick = onSave,
                        enabled = selectedHit != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("把选中的这一条存为候选（仍需实机验证）", fontSize = 13.sp) }
                } else {
                    Text("这次结果不能存为方法（模拟数据或搜索失败）。", color = GWarn, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun FailReasonDialog(
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onRecord: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记一下失败原因") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "失败发生在哪一步？电视上显示了什么（例如找不到菜单、选项是灰的、提示无权限）？",
                    color = GDim, fontSize = 12.sp
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    placeholder = { Text("不填也可以，会记为「未提供原因」") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onRecord(text) }, enabled = text.isNotBlank()) { Text("记录原因") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSkip) { Text("不填，直接记录") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

private fun errText(e: SearchError): String = when (e) {
    SearchError.NOT_CONFIGURED -> "没有配置搜索服务：当前只能查本地知识库。"
    SearchError.NETWORK -> "网络不通：检查手机网络，或稍后再试。"
    SearchError.BLOCKED -> "被拦截（403/401）：服务器拒绝了这个请求。"
    SearchError.TIMEOUT -> "搜索超时：网络慢或服务器没响应。"
    SearchError.RATE_LIMIT -> "请求太频繁：等一会儿再搜。"
    SearchError.PARSE_FAILED -> "返回结果解析失败：服务器格式可能变了。"
}
