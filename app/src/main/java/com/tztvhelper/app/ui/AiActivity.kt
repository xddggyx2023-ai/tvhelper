package com.tztvhelper.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.tztvhelper.app.core.ai.AiAction
import com.tztvhelper.app.core.ai.AiAnswer
import com.tztvhelper.app.core.ai.AnswerConfidence
import com.tztvhelper.app.core.ai.DeviceProfile
import com.tztvhelper.app.core.ai.RiskLevel
import com.tztvhelper.app.core.ai.RuleEngine
import com.tztvhelper.app.core.kb.Brand
import com.tztvhelper.app.core.kb.KbRepository
import com.tztvhelper.app.core.profile.DeviceProfileStore
import com.tztvhelper.app.core.search.SearchProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                "本地规则问答 · 不联网 · 不是大模型。$searchStatus",
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
            "提示：这里的回答只依据本地知识库和固定说明；没有联网搜索，也没有云端大模型。",
            color = Color(0xFF8794AB), fontSize = 11.sp
        )
    }
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
