package com.tztvhelper.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tztvhelper.app.core.adb.Adb
import com.tztvhelper.app.core.adb.AdbConnectionOracle
import com.tztvhelper.app.core.ai.DeviceProfile
import com.tztvhelper.app.core.kb.Brand
import com.tztvhelper.app.core.kb.KbRepository
import com.tztvhelper.app.core.kb.MatchLevel
import com.tztvhelper.app.core.kb.Method
import com.tztvhelper.app.core.kb.MethodStatus
import com.tztvhelper.app.core.kb.RecordOutcome
import com.tztvhelper.app.core.kb.SourceType
import com.tztvhelper.app.core.kb.TvModel
import com.tztvhelper.app.core.kb.VerifyResult
import com.tztvhelper.app.core.profile.DeviceProfileStore
import com.tztvhelper.app.core.search.SaveResult
import com.tztvhelper.app.core.search.SearchOutcome
import com.tztvhelper.app.core.search.SearchProviderFactory
import com.tztvhelper.app.core.search.SearchQuery
import com.tztvhelper.app.core.search.SearchService
import com.tztvhelper.app.core.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 完整教程 / 验证记录页。
 *
 * 它不再是首页的并列入口，也没有品牌/型号/系统版本表单——
 * 档案只从 DeviceProfileStore 读，编辑入口只有聊天页的「我的电视」。
 */
class GuideActivity : ComponentActivity() {

    companion object {
        const val EXTRA_METHOD_ID = "method_id"
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        Adb.init(this)
        setContent { GuideScreen(intent.getStringExtra(EXTRA_METHOD_ID)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideScreen(methodId: String?) {
    val ctx = LocalContext.current
    val repo = remember { KbRepository(ctx.applicationContext) }
    val store = remember { DeviceProfileStore(ctx) }
    val service = remember {
        SearchService(repo.database(), repo, SearchProviderFactory.create(ctx), AppConfig.searchCacheTtlMs(ctx))
    }
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf(store.load()) }
    var method by remember { mutableStateOf<Method?>(null) }
    var brand by remember { mutableStateOf<Brand?>(null) }
    var model by remember { mutableStateOf<TvModel?>(null) }
    var level by remember { mutableStateOf<MatchLevel?>(null) }
    var history by remember { mutableStateOf(repo.method(methodId ?: "")?.let { repo.history(it.id) } ?: emptyList()) }
    var connected by remember { mutableStateOf(Adb.isConnected()) }
    var note by remember { mutableStateOf<String?>(null) }
    var showFail by remember { mutableStateOf(false) }
    var failReason by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<SearchOutcome?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var saveNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        repo.ensureSeeded()
        val brands = repo.brands()
        brand = brands.firstOrNull { it.id == profile.brandId }
        model = profile.brandId?.let { repo.resolveModel(it, profile.model) }
        val m = methodId?.let { repo.method(it) } ?: run {
            val r = repo.find(profile.brandId, model?.id, profile.osVersion)
            if (r is com.tztvhelper.app.core.kb.MatchResult.Hit) {
                level = r.level
                r.method
            } else null
        }
        method = m
        if (m != null) {
            history = repo.history(m.id)
            brand = brands.firstOrNull { it.id == m.brandId } ?: brand
            model = m.modelId?.let { id -> repo.models(m.brandId).firstOrNull { it.id == id } }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            connected = Adb.isConnected()
            delay(2000)
        }
    }

    fun refreshHistory() {
        method?.let { history = repo.history(it.id) }
    }

    fun verify(res: VerifyResult, reason: String?) {
        val m = method ?: return
        when (val r = repo.recordVerification(
            m.id, res,
            profile.osVersion ?: Adb.info?.release,
            profile.model ?: Adb.info?.model,
            reason
        )) {
            is RecordOutcome.Rejected -> note = "没记上：${r.reason}"
            is RecordOutcome.Updated -> {
                method = r.method
                note = when (res) {
                    VerifyResult.SUCCESS_APP -> "已记录 APP 实测成功：连接是真的，设备和方法对得上，系统与型号用的是电视上报的值。"
                    VerifyResult.SUCCESS_USER -> "已记录成功。累计 2 次成功后会升为推荐。"
                    VerifyResult.FAILURE -> "已记录失败，原因已存档；原来的成功记录保留。"
                }
            }
        }
        refreshHistory()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tv.bg)
            .imePadding()
    ) {
        Row(
            Modifier.fillMaxWidth().background(Tv.card).padding(TvSpace.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { (ctx as? ComponentActivity)?.finish() },
                modifier = Modifier.heightIn(min = TvSpace.touch)
            ) { Text("返回", color = Tv.dim, fontSize = TvText.body) }
            Column(Modifier.weight(1f)) {
                Text("完整教程", color = Tv.onAccent, fontSize = TvText.title, fontWeight = FontWeight.Bold)
                Text(
                    "验证记录与来源都在这一页",
                    color = Tv.dimmer, fontSize = TvText.tiny
                )
            }
        }

        if (profile.brandId == null) {
            Box(Modifier.fillMaxSize().padding(TvSpace.xl), contentAlignment = Alignment.Center) {
                Text(
                    "还没有电视档案。请回到聊天页，点右上角「设置电视」先选品牌。",
                    color = Tv.dim, fontSize = TvText.body
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(TvSpace.l),
            verticalArrangement = Arrangement.spacedBy(TvSpace.m)
        ) {
            val m = method
            if (m == null) {
                item {
                    Text(
                        "知识库里没有这个电视的教程。回到聊天页问助手，或联网搜索。",
                        color = Tv.dim, fontSize = TvText.body
                    )
                }
            } else {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Tv.card)) {
                        Column(Modifier.padding(TvSpace.l), verticalArrangement = Arrangement.spacedBy(TvSpace.s)) {
                            Text(m.title, color = Tv.onAccent, fontSize = TvText.title, fontWeight = FontWeight.Bold)
                            Text(
                                "${brand?.name ?: ""} ${model?.displayName ?: "品牌通用"}",
                                color = Tv.dimmer, fontSize = TvText.small
                            )
                            Text(
                                when (level) {
                                    MatchLevel.EXACT_MODEL_OS -> "型号和系统版本都对上了。"
                                    MatchLevel.MODEL -> "型号对上了，系统版本没有专门记录。"
                                    MatchLevel.BRAND_GENERIC, null -> "这是品牌通用方法，菜单可能和你的电视略有出入。"
                                },
                                color = Tv.dimmer, fontSize = TvText.tiny
                            )
                            Text(statusText(m.status), color = statusColor(m.status), fontSize = TvText.small)
                            Text(
                                "成功 ${m.successCount} 次 · 失败 ${m.failCount} 次",
                                color = Tv.dimmer, fontSize = TvText.tiny
                            )
                            if (m.notes.isNotBlank()) {
                                Text(m.notes, color = Tv.dim, fontSize = TvText.small)
                            }
                        }
                    }
                }

                items(m.steps) { s ->
                    Card(colors = CardDefaults.cardColors(containerColor = Tv.card)) {
                        Column(Modifier.padding(TvSpace.l), verticalArrangement = Arrangement.spacedBy(TvSpace.xs)) {
                            Text("第 ${s.n} 步 · ${s.title}", color = Tv.good, fontSize = TvText.body, fontWeight = FontWeight.SemiBold)
                            Text(s.detail, color = Tv.dim, fontSize = TvText.body)
                            if (s.path.isNotEmpty()) {
                                Text("菜单路径：" + s.path.joinToString(" → "), color = Tv.accent, fontSize = TvText.small)
                            }
                            if (s.note.isNotBlank()) {
                                Text(s.note, color = Tv.warn, fontSize = TvText.tiny)
                            }
                        }
                    }
                }

                item {
                    HorizontalDivider(color = Tv.line)
                    Text("这条方法管用吗？", color = Tv.onAccent, fontSize = TvText.body, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(TvSpace.s)) {
                        Button(
                            onClick = { verify(VerifyResult.SUCCESS_APP, null) },
                            enabled = connected,
                            modifier = Modifier.heightIn(min = TvSpace.touch)
                        ) { Text("APP 检测成功", fontSize = TvText.small) }
                        OutlinedButton(
                            onClick = { verify(VerifyResult.SUCCESS_USER, null) },
                            modifier = Modifier.heightIn(min = TvSpace.touch)
                        ) { Text("我按步骤成功了", fontSize = TvText.small) }
                        OutlinedButton(
                            onClick = { showFail = true },
                            modifier = Modifier.heightIn(min = TvSpace.touch)
                        ) { Text("没用，失败", fontSize = TvText.small) }
                    }
                    if (!connected) {
                        Text(
                            "「APP 检测成功」需要先真的连上电视，而且连的必须是这个品牌/型号；没连上时不能记。",
                            color = Tv.warn, fontSize = TvText.tiny
                        )
                    }
                    note?.let { Text(it, color = Tv.good, fontSize = TvText.small) }
                    if (history.isNotEmpty()) {
                        Text("变更记录", color = Tv.dim, fontSize = TvText.small, fontWeight = FontWeight.SemiBold)
                        history.take(8).forEach { h ->
                            Text("· ${changeText(h.change)}：${h.detail}", color = Tv.dimmer, fontSize = TvText.tiny)
                        }
                    }
                }

                item {
                    HorizontalDivider(color = Tv.line)
                    TextButton(
                        onClick = { showSearch = !showSearch },
                        modifier = Modifier.heightIn(min = TvSpace.touch)
                    ) { Text(if (showSearch) "收起联网搜索" else "没找到可用方法？联网搜索补充", color = Tv.accent, fontSize = TvText.small) }
                }

                if (showSearch) {
                    item {
                        Button(
                            onClick = {
                                val b = profile.brandId ?: return@Button
                                searching = true
                                saveNote = null
                                scope.launch {
                                    val o = runCatching {
                                        withContext(Dispatchers.IO) {
                                            service.search(SearchQuery(b, profile.brandName ?: "", profile.model, profile.osVersion))
                                        }
                                    }.getOrNull()
                                    outcome = o
                                    selected = null
                                    searching = false
                                    if (o == null) saveNote = "搜索没有完成，请稍后再试。"
                                }
                            },
                            enabled = !searching,
                            modifier = Modifier.heightIn(min = TvSpace.touch)
                        ) { Text(if (searching) "正在搜索…" else "搜索 ${brand?.name ?: ""} ${profile.model ?: ""}", fontSize = TvText.small) }
                    }
                    outcome?.let { o ->
                        item {
                            Text(
                                if (o.response.isMock) "模拟数据，仅用于验证流程，不能入库。" else "网上原始资料，不是验证过的教程。",
                                color = Tv.warn, fontSize = TvText.tiny
                            )
                        }
                        items(o.response.hits.size) { i ->
                            val hit = o.response.hits[i]
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = TvSpace.touch).clickable { selected = i },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected == i, onClick = { selected = i })
                                Column(Modifier.weight(1f)) {
                                    Text(hit.title, color = Tv.dim, fontSize = TvText.bodySmall)
                                    Text(hit.url, color = Tv.accent, fontSize = TvText.tiny)
                                    Text(sourceLabel(hit.sourceType), color = Tv.dimmer, fontSize = TvText.tiny)
                                }
                            }
                        }
                        item {
                            Button(
                                onClick = {
                                    val idx = selected ?: return@Button
                                    val b = profile.brandId ?: return@Button
                                    val r = service.saveAsCandidate(
                                        outcome = o, brandId = b, modelText = profile.model,
                                        osVersion = profile.osVersion, hitIndex = idx,
                                        title = "${brand?.name ?: ""} 开启方法（来自搜索）"
                                    )
                                    saveNote = when (r) {
                                        is SaveResult.Saved -> "已存为候选。要实际验证成功，才会变成推荐方法。"
                                        SaveResult.MockBlocked -> "模拟数据不能写进知识库。"
                                        SaveResult.NoSteps -> "这条结果没有步骤，不能存成教程。"
                                        SaveResult.NoHit -> "先选一条。"
                                        SaveResult.IdentityMismatch -> "搜索条件与当前电视不一致，请重新搜索。"
                                        SaveResult.InvalidUrl -> "链接不合法，不能入库。"
                                        SaveResult.UnresolvedModel -> "这个型号不在知识库型号表里，不能存成品牌通用；请换一个已有型号，或清空型号。"
                                        is SaveResult.Failed -> "保存失败：${r.reason}"
                                    }
                                },
                                enabled = selected != null,
                                modifier = Modifier.heightIn(min = TvSpace.touch)
                            ) { Text("存为候选方法", fontSize = TvText.small) }
                            saveNote?.let { Text(it, color = Tv.good, fontSize = TvText.tiny) }
                        }
                    }
                }
            }
        }
    }

    if (showFail) {
        AlertDialog(
            onDismissRequest = { showFail = false },
            title = { Text("哪里没成功？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(TvSpace.s)) {
                    Text("写下具体卡住的地方，对以后判断这条方法很有用。也可以不填，会记成「未提供原因」。", color = Tv.dim, fontSize = TvText.small)
                    OutlinedTextField(
                        value = failReason,
                        onValueChange = { failReason = it },
                        label = { Text("例如：第 3 步找不到开发者选项") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = { showFail = false; verify(VerifyResult.FAILURE, null) },
                        modifier = Modifier.heightIn(min = TvSpace.touch)
                    ) { Text("不填，直接记录") }
                    TextButton(
                        onClick = { showFail = false; verify(VerifyResult.FAILURE, failReason) },
                        modifier = Modifier.heightIn(min = TvSpace.touch)
                    ) { Text("记录失败") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showFail = false }, modifier = Modifier.heightIn(min = TvSpace.touch)) {
                    Text("取消")
                }
            },
            containerColor = Tv.card
        )
    }
}

private fun statusText(s: MethodStatus): String = when (s) {
    MethodStatus.RECOMMENDED -> "状态：在真电视上成功过，推荐"
    MethodStatus.CANDIDATE -> "状态：还没在真电视上验证过"
    MethodStatus.NEEDS_REVIEW -> "状态：成功过，后来有人失败，需再确认"
    MethodStatus.REJECTED -> "状态：已确认不可用"
}

private fun statusColor(s: MethodStatus) = when (s) {
    MethodStatus.RECOMMENDED -> Tv.good
    MethodStatus.NEEDS_REVIEW -> Tv.warn
    MethodStatus.REJECTED -> Tv.bad
    MethodStatus.CANDIDATE -> Tv.dimmer
}

private fun changeText(c: String) = when (c) {
    "PROMOTED" -> "升为推荐"
    "DEMOTED" -> "转为待复核"
    "FAILED" -> "记录失败"
    "VERIFIED" -> "记录成功"
    "CREATED" -> "入库"
    else -> c
}

private fun sourceLabel(t: SourceType) = when (t) {
    SourceType.OFFICIAL -> "官方资料"
    SourceType.FORUM -> "论坛经验"
    SourceType.USER -> "用户反馈"
    SourceType.UNKNOWN -> "来源不明"
}
