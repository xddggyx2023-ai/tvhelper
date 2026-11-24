package com.tztvhelper.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.tztvhelper.app.core.ai.AiAction
import com.tztvhelper.app.core.ai.AiAnswer
import com.tztvhelper.app.core.ai.AnswerConfidence
import com.tztvhelper.app.core.ai.DeviceProfile
import com.tztvhelper.app.core.ai.RiskLevel
import com.tztvhelper.app.core.ai.RuleEngine
import com.tztvhelper.app.core.kb.Brand
import com.tztvhelper.app.core.kb.KbRepository
import com.tztvhelper.app.core.profile.DeviceProfileStore
import com.tztvhelper.app.core.search.SaveResult
import com.tztvhelper.app.core.search.SearchError
import com.tztvhelper.app.core.search.SearchOutcome
import com.tztvhelper.app.core.search.SearchProviderFactory
import com.tztvhelper.app.core.search.SearchQuery
import com.tztvhelper.app.core.search.SearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ABg = Color(0xFF0B1220)
private val ACard = Color(0xFF16213A)
private val AAccent = Color(0xFF4DA3FF)
private val AGood = Color(0xFF7CF0C0)
private val AWarn = Color(0xFFFFB86B)
private val ABad = Color(0xFFFF7B7B)
private val ADim = Color(0xFFA9B6CC)

class AiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AAccent, background = ABg, surface = ACard, onPrimary = Color(0xFF04121F)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = ABg) {
                    AiScreen(onBack = { finish() }, onOpenGuide = {
                        startActivity(android.content.Intent(this, GuideActivity::class.java))
                    })
                }
            }
        }
    }
}

private data class Msg(val fromUser: Boolean, val text: String, val answer: AiAnswer? = null)

@Composable
private fun AiScreen(onBack: () -> Unit, onOpenGuide: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { KbRepository(ctx.applicationContext) }
    val store = remember { DeviceProfileStore(ctx.applicationContext) }
    val engine = remember { RuleEngine(repo) }
    val searchStatus = remember { SearchProviderFactory.describe(ctx) }

    var brands by remember { mutableStateOf<List<Brand>>(emptyList()) }
    var profile by remember { mutableStateOf(DeviceProfile()) }
    var modelText by remember { mutableStateOf("") }
    var osText by remember { mutableStateOf("") }
    var savedTip by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<Msg>>(emptyList()) }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) {
        repo.ensureSeeded()
        brands = repo.brands()
        profile = store.load()
        modelText = profile.model.orEmpty()
        osText = profile.osVersion.orEmpty()
        messages = listOf(
            Msg(
                false,
                "我是本地规则助手，不联网、不是大模型。回答来自内置知识库和固定排查清单，" +
                    "查不到我会直说。先在上面确认你的电视品牌和型号。"
            )
        )
    }

    fun saveProfile() {
        val next = profile.copy(model = modelText.ifBlank { null }, osVersion = osText.ifBlank { null })
        profile = next
        store.save(next)
        savedTip = "已保存：${next.brandName ?: "未选品牌"} ${next.model ?: ""}"
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        input = ""
        messages = messages + Msg(true, t)
        scope.launch {
            val a = withContext(Dispatchers.IO) { engine.answer(t, profile) }
            messages = messages + Msg(false, a.text, a)
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.weight(1f))
            Text("AI 助手", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(56.dp))
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2A12))) {
            Text(
                "回答由本地规则生成 · 不联网 · 不是大模型。$searchStatus",
                color = AWarn, fontSize = 12.sp, modifier = Modifier.padding(10.dp)
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = ACard)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("我的电视", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    brands.forEach { b ->
                        FilterChip(
                            selected = b.id == profile.brandId,
                            onClick = {
                                profile = profile.copy(brandId = b.id, brandName = b.name)
                            },
                            label = { Text(b.name, fontSize = 12.sp) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = modelText, onValueChange = { modelText = it },
                        singleLine = true, label = { Text("型号") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = osText, onValueChange = { osText = it },
                        singleLine = true, label = { Text("系统版本") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { saveProfile() }, modifier = Modifier.weight(1f)) { Text("保存") }
                    OutlinedButton(onClick = onOpenGuide, modifier = Modifier.weight(1f)) {
                        Text("看教程")
                    }
                }
                if (savedTip.isNotBlank()) Text(savedTip, color = AGood, fontSize = 12.sp)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "我的电视型号在哪看",
                "怎么开 ADB",
                "菜单里找不到这一步",
                "我按教程开了，下一步做什么",
                "连不上电视怎么办",
                "会不会把电视弄坏"
            ).forEach { q ->
                OutlinedButton(onClick = { send(q) }) { Text(q, fontSize = 12.sp) }
            }
        }

        messages.forEach { m ->
            if (m.fromUser) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF24456F))) {
                        Text(m.text, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                    }
                }
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = ACard)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(m.text, color = Color.White, fontSize = 14.sp)

                        m.answer?.let { a ->
                            a.steps.forEach { s ->
                                Column {
                                    Text("${s.n}. ${s.title}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    if (s.path.isNotEmpty()) {
                                        Text("路径：" + s.path.joinToString(" → "), color = AAccent, fontSize = 12.sp)
                                    }
                                    Text(s.detail, color = ADim, fontSize = 13.sp)
                                    if (s.note.isNotBlank()) Text("注意：${s.note}", color = AWarn, fontSize = 12.sp)
                                }
                            }

                            if (a.sources.isNotEmpty()) {
                                Text(
                                    "来源：" + a.sources.joinToString("；") { "${it.title}（${it.credibility.label()}）" },
                                    color = ADim, fontSize = 11.sp
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("依据：" + a.confidence.label(), color = AAccent, fontSize = 11.sp)
                                Text("风险：" + a.risk.label(), color = a.risk.color(), fontSize = 11.sp)
                            }

                            if (a.actions.contains(AiAction.OPEN_GUIDE)) {
                                OutlinedButton(onClick = onOpenGuide) { Text("打开教程页", fontSize = 13.sp) }
                            }

                            // 本地库没有答案时，才给"单独发起一次搜索"的入口；
                            // 搜索不是大模型自动完成的，必须由用户点、用户选结果。
                            if (a.confidence == AnswerConfidence.NO_KB && profile.brandId != null) {
                                AiSearchBlock(profile = profile, repo = repo)
                            }
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("例如：我的电视是小米 4A，怎么开 ADB") },
                modifier = Modifier.weight(1f), maxLines = 3
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { send(input) }, enabled = input.isNotBlank()) { Text("发送") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "提示：回答只依据本地知识库和固定说明；知识库没有教程时，可以点答案里的搜索按钮单独搜一次。" +
                "搜到的是网上原始资料，不是 AI 验证过的教程。",
            color = Color(0xFF8794AB), fontSize = 11.sp
        )
    }
}

/**
 * 知识库没答案时，用户手动发起的一次搜索。
 * 明确：不是大模型自动联网；结果要用户自己挑，存进去也只是"候选"。
 */
@Composable
private fun AiSearchBlock(profile: DeviceProfile, repo: KbRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf<SearchOutcome?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val brandId = profile.brandId ?: return

    // 换了品牌/型号/版本，旧结果立即作废，避免把小米的结果存到海信名下
    LaunchedEffect(profile.brandId, profile.model, profile.osVersion) {
        outcome = null
        selected = null
        note = ""
    }

    fun service() = SearchService(
        repo.database(), repo,
        SearchProviderFactory.create(ctx), AppConfig.searchCacheTtlMs(ctx)
    )

    fun brandName() = repo.brands().firstOrNull { it.id == brandId }?.name ?: brandId

    fun search() {
        scope.launch {
            busy = true
            note = ""
            selected = null
            outcome = withContext(Dispatchers.IO) {
                service().search(
                    SearchQuery(brandId, brandName(), profile.model, profile.osVersion)
                )
            }
            busy = false
        }
    }

    fun save() {
        val o = outcome ?: return
        val idx = selected ?: return
        note = when (
            val r = service().saveAsCandidate(
                outcome = o,
                brandId = brandId,
                modelText = profile.model,
                osVersion = profile.osVersion,
                hitIndex = idx,
                title = "${brandName()} 开启方法（来自搜索）"
            )
        ) {
            is SaveResult.Saved -> "已存为候选方法，编号 ${r.methodId}；验证成功前不会变成推荐。"
            SaveResult.MockBlocked -> "模拟数据不能写进知识库。"
            SaveResult.NoSteps -> "这条结果没有步骤，不能存成教程。"
            SaveResult.NoHit -> "先点选一条结果。"
            SaveResult.IdentityMismatch -> "搜索条件和当前电视不一致，请重新搜索。"
            SaveResult.InvalidUrl -> "这条结果的链接不合法，不能入库。"
            SaveResult.UnresolvedModel -> "你填的型号不在知识库型号表里，不能存成品牌通用方法（会推给同品牌所有型号）。请先选一个知识库已有的型号。"
            is SaveResult.Failed -> "保存失败：${r.reason}"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "知识库里没有这个型号的答案。你可以单独发起一次联网搜索——这是搜索按钮，不是大模型自己上网。",
            color = ADim, fontSize = 12.sp
        )
        Button(onClick = { search() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "搜索中…" else "联网搜索这个型号")
        }

        val o = outcome
        if (o != null) {
            if (o.response.isMock) {
                Text("模拟数据：只用来验证流程，不是真实结果，不能入库。", color = AWarn, fontSize = 12.sp)
            }
            val err = o.response.error
            if (err != null) {
                Text(searchErrText(err), color = ABad, fontSize = 12.sp)
            } else {
                Text(
                    "搜索条件：${o.query.brand} ${o.query.model ?: "（未填型号）"}；共 ${o.response.hits.size} 条，点一条再存。",
                    color = ADim, fontSize = 11.sp
                )
                o.response.hits.forEachIndexed { i, h ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = i }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(selected = selected == i, onClick = { selected = i })
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(h.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(h.url, color = AAccent, fontSize = 11.sp)
                            Text(h.snippet, color = ADim, fontSize = 12.sp)
                            Text(
                                "来源：${SearchService.sourceLabel(h.sourceType)} · ${h.credibility.label()} · 抓取 ${timeText(h.fetchedAt)}",
                                color = Color(0xFF8794AB), fontSize = 11.sp
                            )
                            if (h.extractedSteps.isEmpty()) {
                                Text("这条没有步骤，不能存成教程。", color = AWarn, fontSize = 11.sp)
                            }
                        }
                    }
                }
                if (o.canSaveToKb) {
                    Button(
                        onClick = { save() },
                        enabled = selected != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("把选中的存为候选（仍需实机验证）", fontSize = 12.sp) }
                }
            }
        }
        if (note.isNotBlank()) Text(note, color = AWarn, fontSize = 12.sp)
    }
}

private fun timeText(ts: Long): String =
    if (ts <= 0) "-" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ts))

private fun searchErrText(e: SearchError): String = when (e) {
    SearchError.NOT_CONFIGURED -> "没有配置搜索服务，只能查本地知识库。"
    SearchError.NETWORK -> "网络不通：检查网络后再试。"
    SearchError.TIMEOUT -> "搜索超时：稍后再试。"
    SearchError.BLOCKED -> "搜索被拦截：服务器被目标站点拦住了。"
    SearchError.RATE_LIMIT -> "搜索太频繁：过一会儿再试。"
    SearchError.PARSE_FAILED -> "搜索返回的数据不合格，已拒绝使用。"
}

private fun AnswerConfidence.label(): String = when (this) {
    AnswerConfidence.KB_RECOMMENDED -> "知识库·已推荐"
    AnswerConfidence.KB_CANDIDATE -> "知识库·候选（未验证）"
    AnswerConfidence.KB_NEEDS_REVIEW -> "知识库·待复核"
    AnswerConfidence.NO_KB -> "知识库无结果"
    AnswerConfidence.GENERAL -> "通用说明"
}

private fun RiskLevel.label(): String = when (this) {
    RiskLevel.LOW -> "低"
    RiskLevel.MEDIUM -> "中（资料未实机验证）"
    RiskLevel.HIGH -> "高（有失败记录）"
}

private fun RiskLevel.color(): Color = when (this) {
    RiskLevel.LOW -> AGood
    RiskLevel.MEDIUM -> AWarn
    RiskLevel.HIGH -> ABad
}

private fun com.tztvhelper.app.core.kb.Credibility.label(): String = when (this) {
    com.tztvhelper.app.core.kb.Credibility.OFFICIAL -> "官方"
    com.tztvhelper.app.core.kb.Credibility.FORUM -> "论坛"
    com.tztvhelper.app.core.kb.Credibility.USER_REPORT -> "用户反馈"
    com.tztvhelper.app.core.kb.Credibility.UNVERIFIED -> "未验证"
}
