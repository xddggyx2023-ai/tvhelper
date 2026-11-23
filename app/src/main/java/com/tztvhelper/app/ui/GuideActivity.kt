package com.tztvhelper.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import com.tztvhelper.app.core.kb.Brand
import com.tztvhelper.app.core.kb.Credibility
import com.tztvhelper.app.core.kb.KbRepository
import com.tztvhelper.app.core.kb.MatchLevel
import com.tztvhelper.app.core.kb.MatchResult
import com.tztvhelper.app.core.kb.Method
import com.tztvhelper.app.core.kb.MethodStatus
import com.tztvhelper.app.core.kb.VerifyResult
import kotlinx.coroutines.launch
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
    var result by remember { mutableStateOf<MatchResult?>(null) }
    var current by remember { mutableStateOf<Method?>(null) }
    var note by remember { mutableStateOf("") }
    var seeded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        repo.ensureSeeded()
        brands = repo.brands()
        brand = brands.firstOrNull()
        seeded = true
    }

    fun query() {
        val b = brand ?: return
        val r = repo.find(b.id, null, osVersion.ifBlank { null })
        result = r
        current = (r as? MatchResult.Hit)?.method
        note = ""
    }

    fun verify(res: VerifyResult) {
        val m = current ?: return
        current = repo.recordVerification(m.id, res, osVersion.ifBlank { null }, model.ifBlank { null }, null)
        note = when (res) {
            VerifyResult.SUCCESS_APP -> "已记录：APP 实测成功，这条方法升为推荐。"
            VerifyResult.SUCCESS_USER -> "已记录成功。累计 2 次成功后会升为推荐。"
            VerifyResult.FAILURE -> "已记录失败。原成功记录保留，方法转入待复核。"
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
                    onClick = { brand = b; result = null; current = null },
                    label = { Text(b.name) }
                )
            }
        }

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            singleLine = true,
            label = { Text("电视型号（可留空，先按品牌通用教程）") },
            placeholder = { Text("例如 小米4A / 55A7 / 65E8G") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = osVersion,
            onValueChange = { osVersion = it },
            singleLine = true,
            label = { Text("系统版本（可留空）") },
            placeholder = { Text("例如 8.1 / 9 / Android 11") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { query() },
            enabled = brand != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) { Text("查本地知识库", fontSize = 16.sp) }

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
                    Text("实时联网搜索：未配置搜索源，当前只能查本地库。", color = GWarn, fontSize = 12.sp)
                }
            }

            is MatchResult.Hit -> MethodCard(
                m = current ?: r.method,
                level = r.level,
                onSuccessApp = { verify(VerifyResult.SUCCESS_APP) },
                onSuccessUser = { verify(VerifyResult.SUCCESS_USER) },
                onFail = { verify(VerifyResult.FAILURE) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSuccessApp, modifier = Modifier.weight(1f)) { Text("连上了（APP 检测）") }
                OutlinedButton(onClick = onFail, modifier = Modifier.weight(1f)) {
                    Text("没用，失败", color = GBad)
                }
            }
            OutlinedButton(onClick = onSuccessUser, modifier = Modifier.fillMaxWidth()) {
                Text("我按教程开了，先记一次成功")
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
