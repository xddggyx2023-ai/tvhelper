package com.tztvhelper.app.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tztvhelper.app.core.adb.Adb
import com.tztvhelper.app.core.ai.AiAnswer
import com.tztvhelper.app.core.ai.AnswerConfidence
import com.tztvhelper.app.core.ai.DeviceProfile
import com.tztvhelper.app.core.ai.RuleEngine
import com.tztvhelper.app.core.chat.AnswerFold
import com.tztvhelper.app.core.chat.AnswerPresenter
import com.tztvhelper.app.core.chat.ChatAction
import com.tztvhelper.app.core.chat.ChatActionKind
import com.tztvhelper.app.core.chat.ChatOnboarding
import com.tztvhelper.app.core.chat.QuickReplies
import com.tztvhelper.app.core.discovery.Net
import com.tztvhelper.app.core.guide.GuideCopy
import com.tztvhelper.app.core.guide.GuideEvent
import com.tztvhelper.app.core.guide.GuideFlow
import com.tztvhelper.app.core.guide.GuideProgressStore
import com.tztvhelper.app.core.guide.GuideStage
import com.tztvhelper.app.core.guide.GuideState
import com.tztvhelper.app.core.guide.GuideStorage
import com.tztvhelper.app.core.guide.TroubleshootBranch
import com.tztvhelper.app.core.kb.Brand
import com.tztvhelper.app.core.kb.KbRepository
import com.tztvhelper.app.core.kb.MatchResult
import com.tztvhelper.app.core.kb.Method
import com.tztvhelper.app.core.kb.TutorialStep
import com.tztvhelper.app.core.kb.VerificationRules
import com.tztvhelper.app.core.profile.DeviceProfileStore
import com.tztvhelper.app.core.search.SaveResult
import com.tztvhelper.app.core.search.SearchOutcome
import com.tztvhelper.app.core.search.SearchProviderFactory
import com.tztvhelper.app.core.search.SearchQuery
import com.tztvhelper.app.core.search.SearchService
import com.tztvhelper.app.core.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        Adb.init(this)
        setContent { ChatScreen() }
    }
}

private enum class MsgKind { TEXT, PROGRESS, ERROR, SEARCH }

private data class ChatMessage(
    val id: Long,
    val user: Boolean,
    val text: String,
    val answer: AiAnswer? = null,
    val search: SearchOutcome? = null,
    val kind: MsgKind = MsgKind.TEXT,
    val step: TutorialStep? = null,
    val stepIndex: Int = 0,
    val stepTotal: Int = 0
)

/** 引导进度存在独立的 SharedPreferences 里，退出再进来能接着走 */
private class PrefsGuideStorage(ctx: android.content.Context) : GuideStorage {
    private val prefs = ctx.getSharedPreferences("guide_progress", android.content.Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString("state", null)
    override fun write(value: String) { prefs.edit().putString("state", value).apply() }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen() {
    val ctx = LocalContext.current
    val repo = remember { KbRepository(ctx.applicationContext) }
    val store = remember { DeviceProfileStore(ctx) }
    val engine = remember { RuleEngine(repo) }
    val service = remember {
        SearchService(repo.database(), repo, SearchProviderFactory.create(ctx), AppConfig.searchCacheTtlMs(ctx))
    }

    var profile by remember { mutableStateOf(store.load()) }
    var brands by remember { mutableStateOf<List<Brand>>(emptyList()) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var awaitingModel by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf(GuideState()) }
    var guideMethod by remember { mutableStateOf<Method?>(null) }
    var pendingHosts by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastHost by remember { mutableStateOf<String?>(null) }
    val guideStore = remember { GuideProgressStore(PrefsGuideStorage(ctx.applicationContext)) }
    var folds by remember { mutableStateOf<Map<Long, AnswerFold>>(emptyMap()) }
    var selection by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var notes by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var seq by remember { mutableLongStateOf(1L) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun nextId(): Long = seq++
    fun add(m: ChatMessage) { messages = messages + m }
    fun addText(text: String, answer: AiAnswer? = null, kind: MsgKind = MsgKind.TEXT) =
        add(ChatMessage(nextId(), false, text, answer = answer, kind = kind))

    fun updateText(id: Long, text: String) {
        messages = messages.map { if (it.id == id) it.copy(text = text) else it }
    }

    fun persist(g: GuideState) {
        guide = g
        guideStore.save(g)
    }

    fun addStepMessage(m: Method, s: GuideState) {
        val step = m.steps.getOrNull(s.stepIndex) ?: return
        add(
            ChatMessage(
                nextId(), false, "第 ${s.stepNumber} 步，共 ${s.totalSteps} 步。",
                step = step, stepIndex = s.stepIndex, stepTotal = s.totalSteps
            )
        )
    }

    fun profileMatches(deviceBrand: String, deviceModel: String): Boolean {
        val b = brands.firstOrNull { it.id == profile.brandId } ?: return false
        if (!VerificationRules.matchesBrand(deviceBrand, b)) return false
        val typed = profile.model?.takeIf { it.isNotBlank() } ?: return true
        val key = repo.resolveModel(b.id, typed)?.model ?: typed
        return VerificationRules.matchesModel(deviceModel, key)
    }

    fun acceptDetected() {
        val d = guide.detectedBrand ?: return
        val matched = brands.firstOrNull { VerificationRules.matchesBrand(d, it) }
        val p = profile.copy(
            brandId = matched?.id ?: profile.brandId,
            brandName = matched?.name ?: profile.brandName ?: d,
            model = guide.detectedModel,
            osVersion = guide.detectedOs,
            adbOpened = true
        )
        profile = p
        store.save(p)
        persist(GuideFlow.reduce(guide, GuideEvent.AcceptDetected).state)
        addText("已按电视实际信息更新档案：${p.brandName} ${p.model ?: ""}，系统 ${p.osVersion ?: "未知"}。")
    }

    fun keepMine() {
        val p = profile.copy(adbOpened = true)
        profile = p
        store.save(p)
        persist(GuideFlow.reduce(guide, GuideEvent.KeepMine).state)
        addText("好，保留你填的档案。连接已经建立，可以用首页的功能了。")
    }

    fun connect(host: String) {
        lastHost = host
        val pid = nextId()
        add(
            ChatMessage(
                pid, false,
                "正在连接 $host …请看电视屏幕，弹出「允许调试」就用遥控器点允许。",
                kind = MsgKind.PROGRESS
            )
        )
        scope.launch {
            val r = Adb.connect(host) { attempt, err ->
                scope.launch {
                    updateText(
                        pid,
                        "正在连接 $host：第 $attempt 次尝试" + if (err.isNotBlank()) "（$err）" else ""
                    )
                }
            }
            r.onSuccess { info ->
                val brand = info.brand.ifBlank { info.manufacturer }.ifBlank { "未知品牌" }
                val same = profileMatches(brand, info.model)
                var s = GuideFlow.reduce(guide, GuideEvent.Connected(same)).state
                s = GuideFlow.withDevice(s, brand, info.model, info.release)
                persist(s)
                updateText(pid, "连上了：$brand ${info.model}，系统 ${info.release}。")
                if (same) {
                    profile = profile.copy(adbOpened = true)
                    store.save(profile)
                    addText("已经连上你的电视，整个流程走完了。\n现在可以回首页，搜索附近的电视或手动输入 IP，安装和管理应用。")
                } else {
                    addText(
                        "检测到你的电视实际是「$brand ${info.model}」，系统 ${info.release}；" +
                            "和你填的「${profile.brandName ?: ""} ${profile.model ?: ""}」不一致。\n是否以此为准？"
                    )
                }
            }.onFailure { e ->
                persist(guide.copy(stage = GuideStage.CONNECT, awaitConfirm = false))
                updateText(pid, "没连上：${e.message ?: "电视没有响应"}")
                addText("先确认电视上的 ADB 开关还开着、手机和电视在同一个 Wi-Fi，再点「重新连接」。")
            }
        }
    }

    fun checkPort() {
        val m = guideMethod ?: return
        val ip = Net.localIpv4()
        if (ip == null) {
            addText("手机现在没有连 Wi-Fi，没法检查电视端口。先连上同一个 Wi-Fi 再点「我看到了，下一步」。")
            return
        }
        val pid = nextId()
        add(
            ChatMessage(
                pid, false,
                "教程走完了。现在检查电视有没有打开 5555 端口（大约几秒），这一步是真检测。",
                kind = MsgKind.PROGRESS
            )
        )
        scope.launch {
            val found = runCatching {
                Net.scanPort5555(ip) { done, total ->
                    if (done % 32 == 0 || done == total) {
                        scope.launch { updateText(pid, "正在检查：$done/$total") }
                    }
                }
            }.getOrDefault(emptyList())

            if (found.isEmpty()) {
                val t = GuideFlow.reduce(guide, GuideEvent.PortClosed).state
                persist(t)
                updateText(
                    pid,
                    "没有找到开着 5555 端口的电视。可能原因：\n" +
                        "1) 电视上的 ADB 开关没有真正打开；\n" +
                        "2) 手机和电视不在同一个 Wi-Fi；\n" +
                        "3) 电视只在有线网络下开放调试。"
                )
                addText("我先退回到上一步，你对照电视再看一眼。")
                addStepMessage(m, t)
            } else if (found.size == 1) {
                val t = GuideFlow.reduce(guide, GuideEvent.PortOpen).state
                persist(t)
                updateText(pid, "电视的 5555 端口开着：${found[0]}")
                connect(found[0])
            } else {
                val t = GuideFlow.reduce(guide, GuideEvent.PortOpen).state
                persist(t)
                pendingHosts = found
                updateText(pid, "找到 ${found.size} 台开着 5555 的设备。请点下面你电视的 IP：")
            }
        }
    }

    fun nextStep() {
        val m = guideMethod ?: return
        val t = GuideFlow.reduce(guide, GuideEvent.Next)
        persist(t.state)
        if (t.effects.contains(com.tztvhelper.app.core.guide.GuideEffect.ScanPort)) {
            addText("教程的步骤做完了，下面开始检测电视。")
            checkPort()
        } else if (t.state.stage == GuideStage.STEP) {
            addText("好，继续。")
            addStepMessage(m, t.state)
        }
    }

    fun startGuide() {
        val brandId = profile.brandId
        if (brandId == null) {
            addText("先告诉我你的电视品牌，我才能查对应的开启方法。")
            return
        }
        val modelId = repo.resolveModelId(brandId, profile.model)
        when (val r = repo.find(brandId, modelId, profile.osVersion)) {
            is MatchResult.Hit -> {
                guideMethod = r.method
                val t = GuideFlow.reduce(GuideState(), GuideEvent.Start(r.method.id, r.method.steps.size))
                persist(t.state)
                addText("我会一步一步来，一次只做一步。把遥控器拿在手上，跟着做就行。")
                addStepMessage(r.method, t.state)
            }
            is MatchResult.NotFound -> {
                val a = AiAnswer(
                    text = "知识库里没有这台电视的开启教程，我不会编菜单路径。\n" +
                        "可以点下面的按钮联网搜这个型号；搜到的是网上原始资料，需要你自己对照和判断。",
                    confidence = AnswerConfidence.NO_KB,
                    intent = com.tztvhelper.app.core.ai.Intent.FIND_TUTORIAL,
                    actions = listOf(com.tztvhelper.app.core.ai.AiAction.OPEN_SEARCH),
                    risk = com.tztvhelper.app.core.ai.RiskLevel.MEDIUM
                )
                addText(a.text, a)
            }
        }
    }

    fun showBranch(event: GuideEvent) {
        val t = GuideFlow.reduce(guide, event).state
        persist(t)
        t.branch?.let { b ->
            val copy = GuideCopy.branch(b)
            addText("${copy.title}\n${copy.body}\n${copy.next}")
        }
    }

    fun branchDone() {
        val t = GuideFlow.reduce(guide, GuideEvent.BranchDone).state
        persist(t)
        guideMethod?.let { addStepMessage(it, t) }
    }

    fun stillStuck() {
        if (guide.branch == TroubleshootBranch.NO_DEVELOPER_OPTIONS) {
            showBranch(GuideEvent.NoAdbSwitch)
        } else {
            addText("如果还是不行，说明这台电视的系统没有向普通用户开放 ADB。\n可以换个办法：在电视自带的应用商店里装，或者联系品牌客服问「怎么打开 ADB 调试」。我不会给你编一个不存在的菜单。")
        }
    }

    fun retryConnect() {
        val host = lastHost
        if (host == null) {
            addText("还不知道电视的 IP。先点「我看到了，下一步」，我去扫一下端口。")
            return
        }
        if (guide.stage != GuideStage.CONNECT) persist(guide.copy(stage = GuideStage.CONNECT, awaitConfirm = false))
        connect(host)
    }

    LaunchedEffect(Unit) {
        repo.ensureSeeded()
        brands = repo.brands()
        if (ChatOnboarding.needsBrand(profile)) addText(ChatOnboarding.firstPrompt())
        else addText(ChatOnboarding.welcome(profile))

        // 退出再进来：能接着上次的步骤走
        val restored = guideStore.load()
        if (restored != null && restored.stage != GuideStage.IDLE) {
            val m = restored.methodId?.let { repo.method(it) }
            if (m != null) {
                guide = restored
                guideMethod = m
                if (restored.stage == GuideStage.STEP) {
                    addText("上次的进度还在：第 ${restored.stepNumber} 步，共 ${restored.totalSteps} 步。继续吧。")
                    addStepMessage(m, restored)
                } else if (restored.stage == GuideStage.CONNECT) {
                    addText("上次停在连接这一步。确认电视上的 ADB 还开着，再点「重新连接」。")
                } else if (restored.stage == GuideStage.PORT_CHECK) {
                    addText("上次停在端口检测这一步，我再检查一次。")
                    checkPort()
                } else {
                    addText("上次已经连上了。如果现在连不上，回首页重新搜索电视。")
                }
            } else {
                guideStore.clear()
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun chooseBrand(b: Brand) {
        profile = profile.copy(brandId = b.id, brandName = b.name)
        store.save(profile)
        awaitingModel = true
        addText(ChatOnboarding.afterBrand(b.name))
    }

    fun skipModel() {
        awaitingModel = false
        addText(ChatOnboarding.afterSkipModel(profile.brandName ?: "你的电视"))
    }

    fun startSearch() {
        val brandId = profile.brandId ?: return
        if (busy) return
        busy = true
        addText("正在网上找「${profile.brandName ?: ""} ${profile.model ?: ""}」的开启方法…", kind = MsgKind.PROGRESS)
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    service.search(
                        SearchQuery(brandId, profile.brandName ?: "", profile.model, profile.osVersion)
                    )
                }
            }.getOrNull()
            busy = false
            if (outcome == null) {
                addText("搜索没有完成，请稍后再试。", kind = MsgKind.ERROR)
            } else {
                add(
                    ChatMessage(
                        nextId(), false,
                        if (outcome.response.error != null) "这次搜索没有成功：${outcome.response.error}"
                        else "网上找到 ${outcome.response.hits.size} 条结果。这些是原始资料，不是验证过的教程，点选一条才能存。",
                        search = outcome,
                        kind = MsgKind.SEARCH
                    )
                )
            }
        }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        add(ChatMessage(nextId(), true, t))
        input = ""

        if (awaitingModel) {
            profile = profile.copy(model = t)
            store.save(profile)
            awaitingModel = false
            addText("记下了：${profile.brandName ?: ""} $t。\n想现在开始，就说「怎么开 ADB」，或者点下面的「开始一步步引导」。")
            return
        }
        when (t) {
            ChatOnboarding.SKIP_MODEL -> { skipModel(); return }
            "开始一步步引导" -> { startGuide(); return }
            "先按品牌通用方法查" -> {
                val p = profile.copy(model = null)
                profile = p; store.save(p)
                val a = engine.answer("怎么开 ADB", p)
                addText(a.text, a)
                return
            }
        }
        val a = engine.answer(t, profile)
        addText(a.text, a)
    }

    fun onQuickReply(text: String) {
        when (text) {
            "我看到了，下一步" -> { add(ChatMessage(nextId(), true, text)); nextStep() }
            "我没找到这个" -> { add(ChatMessage(nextId(), true, text)); showBranch(GuideEvent.NotFound) }
            "和我看到的不一样" -> { add(ChatMessage(nextId(), true, text)); showBranch(GuideEvent.Different) }
            "我做完了，回到刚才那一步" -> { add(ChatMessage(nextId(), true, text)); branchDone() }
            "还是不行" -> { add(ChatMessage(nextId(), true, text)); stillStuck() }
            "电视没弹窗", "还是没弹窗" -> { add(ChatMessage(nextId(), true, text)); showBranch(GuideEvent.NoAuthDialog) }
            "重新连接" -> { add(ChatMessage(nextId(), true, text)); retryConnect() }
            "是，用检测到的" -> { add(ChatMessage(nextId(), true, text)); acceptDetected() }
            "保持我填的" -> { add(ChatMessage(nextId(), true, text)); keepMine() }
            else -> send(text)
        }
    }

    fun saveProfile(p: DeviceProfile) {
        profile = p
        store.save(p)
        awaitingModel = false
        addText("已保存：${p.brandName ?: ""} ${p.model ?: ""} ${p.osVersion ?: ""}".trim() + "。之后都用这份档案。")
    }

    fun runAction(a: ChatAction) {
        when (a.kind) {
            ChatActionKind.SET_PROFILE -> showSheet = true
            ChatActionKind.OPEN_SCAN -> (ctx as? ComponentActivity)?.finish()
            ChatActionKind.SEARCH_WEB -> startSearch()
            ChatActionKind.OPEN_GUIDE -> {
                val m = messages.lastOrNull()?.answer?.methodId ?: return
                ctx.startActivity(
                    Intent(ctx, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_METHOD_ID, m)
                )
            }
        }
    }

    fun saveHit(msg: ChatMessage) {
        val o = msg.search ?: return
        val idx = selection[msg.id] ?: return
        val brandId = profile.brandId ?: return
        val r = service.saveAsCandidate(
            outcome = o,
            brandId = brandId,
            modelText = profile.model,
            osVersion = profile.osVersion,
            hitIndex = idx,
            title = "${profile.brandName ?: ""} 开启方法（来自搜索）"
        )
        notes = notes + (msg.id to when (r) {
            is SaveResult.Saved -> "已存为候选方法。它要经过实际操作验证，才会变成推荐方法。"
            SaveResult.MockBlocked -> "模拟数据不能写进知识库——否则以后分不清哪条是真的。"
            SaveResult.NoSteps -> "这条结果没有可用的步骤，不能存成教程。"
            SaveResult.NoHit -> "先点选一条结果。"
            SaveResult.IdentityMismatch -> "搜索条件和现在的电视不一致，请重新搜索。"
            SaveResult.InvalidUrl -> "这条结果的链接不合法，不能入库。"
            SaveResult.UnresolvedModel -> "你填的型号不在知识库型号表里，不能存成品牌通用方法（会推给同品牌所有型号）。先在「我的电视」里选一个已有型号，或清空型号后按品牌通用保存。"
            is SaveResult.Failed -> "保存失败：${r.reason}"
        })
    }

    val lastAssistant = messages.indexOfLast { !it.user }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tv.bg)
            .imePadding()
    ) {
        ChatTopBar(
            profile = profile,
            onBack = { (ctx as? ComponentActivity)?.finish() },
            onInfo = { showInfo = true },
            onProfile = { showSheet = true }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = TvSpace.l, vertical = TvSpace.m),
            verticalArrangement = Arrangement.spacedBy(TvSpace.m)
        ) {
            items(messages, key = { it.id }) { msg ->
                val isLatestAssistant = !msg.user && messages.indexOf(msg) == lastAssistant
                if (msg.user) {
                    UserBubble(msg.text)
                } else {
                    AssistantBubble(
                        msg = msg,
                        fold = folds[msg.id] ?: AnswerFold(),
                        onToggleSteps = { folds = folds + (msg.id to (folds[msg.id] ?: AnswerFold()).toggleSteps()) },
                        onToggleSources = { folds = folds + (msg.id to (folds[msg.id] ?: AnswerFold()).toggleSources()) },
                        onAction = ::runAction,
                        hasBrand = profile.brandId != null,
                        selection = selection[msg.id],
                        onSelect = { i -> selection = selection + (msg.id to i) },
                        note = notes[msg.id],
                        onSave = { saveHit(msg) }
                    )

                    if (isLatestAssistant) {
                        if (ChatOnboarding.needsBrand(profile) && brands.isNotEmpty()) {
                            BrandChoices(brands, onPick = ::chooseBrand)
                        } else if (awaitingModel) {
                            QuickRow(listOf(ChatOnboarding.SKIP_MODEL)) { skipModel() }
                        } else {
                            val q = QuickReplies.forAnswer(msg.answer, profile, guide)
                            if (q.isNotEmpty()) QuickRow(q, ::onQuickReply)
                            if (pendingHosts.isNotEmpty()) {
                                QuickRow(pendingHosts) { host ->
                                    pendingHosts = emptyList()
                                    add(ChatMessage(nextId(), true, host))
                                    connect(host)
                                }
                            }
                        }
                    }
                }
            }
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            onSend = { send(input) },
            enabled = !busy
        )
    }

    if (showSheet) {
        ProfileSheet(
            brands = brands,
            profile = profile,
            repo = repo,
            onDismiss = { showSheet = false },
            onSave = { p -> showSheet = false; saveProfile(p) }
        )
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("这个助手怎么工作") },
            text = {
                Text(
                    "回答来自手机里的本地知识库和固定规则，不联网、不是大模型，也不会替你执行任何 ADB 命令。\n\n" +
                        "知识库里查不到时，我会直说，并给你一个「联网搜索这个型号」的按钮；搜到的只是网上原始资料，" +
                        "要你自己点选、实际操作验证过，才可能变成推荐方法。\n\n" +
                        "ADB 是安卓的调试通道：打开后，手机才能通过 Wi-Fi 连接电视、安装和管理应用。它不是 root，关掉开关就断开。",
                    fontSize = TvText.bodySmall, color = Tv.dim
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }, modifier = Modifier.heightIn(min = TvSpace.touch)) {
                    Text("知道了")
                }
            },
            containerColor = Tv.card
        )
    }
}

@Composable
private fun ChatTopBar(
    profile: DeviceProfile,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onProfile: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Tv.card)
            .padding(horizontal = TvSpace.s, vertical = TvSpace.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = TvSpace.touch)) {
            Text("返回", color = Tv.dim, fontSize = TvText.body)
        }
        Column(Modifier.weight(1f)) {
            Text("开启助手", color = Tv.onAccent, fontSize = TvText.title, fontWeight = FontWeight.Bold)
            Text(
                "本地规则 · 不联网",
                color = Tv.dim,
                fontSize = TvText.tiny,
                modifier = Modifier
                    .heightIn(min = TvSpace.touch)
                    .clickable { onInfo() }
                    .padding(vertical = TvSpace.m)
            )
        }
        val label = if (profile.brandId == null) "设置电视"
        else "${profile.brandName ?: ""} ${profile.model ?: ""}".trim() + " ›"
        OutlinedButton(
            onClick = onProfile,
            modifier = Modifier.heightIn(min = TvSpace.touch)
        ) {
            Text(label, fontSize = TvText.small, color = Tv.dim)
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier
                .fillMaxWidth(0.85f)
                .background(Tv.accent, RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .padding(horizontal = TvSpace.l, vertical = TvSpace.m)
        ) {
            Text(text, color = Tv.onAccent, fontSize = TvText.body)
        }
    }
}

@Composable
private fun AssistantBubble(
    msg: ChatMessage,
    fold: AnswerFold,
    onToggleSteps: () -> Unit,
    onToggleSources: () -> Unit,
    onAction: (ChatAction) -> Unit,
    hasBrand: Boolean,
    selection: Int?,
    onSelect: (Int) -> Unit,
    note: String?,
    onSave: () -> Unit
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f),
            colors = CardDefaults.cardColors(containerColor = Tv.card),
            shape = RoundedCornerShape(TvSpace.l)
        ) {
            Column(Modifier.padding(TvSpace.l), verticalArrangement = Arrangement.spacedBy(TvSpace.s)) {
                val a = msg.answer
                Text(
                    text = if (a != null) AnswerPresenter.visibleText(a) else msg.text,
                    color = if (msg.kind == MsgKind.ERROR) Tv.warn else Tv.dim,
                    fontSize = TvText.body,
                    lineHeight = 22.sp
                )

                msg.step?.let { s ->
                    val expect = when {
                        s.path.isNotEmpty() -> "做完你应该看到：" + s.path.joinToString(" → ")
                        s.note.isNotBlank() -> "注意：" + s.note
                        else -> ""
                    }
                    StepCard(index = msg.stepIndex + 1, title = s.title, detail = s.detail, expect = expect)
                    if (msg.stepTotal > 1) {
                        Text(
                            "第 ${msg.stepIndex + 1} 步 / 共 ${msg.stepTotal} 步",
                            color = Tv.dimmer, fontSize = TvText.tiny
                        )
                    }
                }

                if (a != null && AnswerPresenter.hasSteps(a)) {
                    a.steps.take(AnswerPresenter.stepsToShow(a.steps, fold)).forEachIndexed { i, s ->
                        StepCard(index = i + 1, title = s.title, detail = s.detail, expect = "")
                    }
                    if (a.steps.size > 1) {
                        TextButton(
                            onClick = onToggleSteps,
                            modifier = Modifier.heightIn(min = TvSpace.touch)
                        ) {
                            Text(
                                if (fold.stepsOpen) fold.stepButton else "共 ${a.steps.size} 步，查看全部",
                                color = Tv.accent, fontSize = TvText.small
                            )
                        }
                    }
                }

                if (a != null) {
                    AnswerPresenter.metaLine(a)?.let {
                        Text(it, color = Tv.dimmer, fontSize = TvText.tiny, lineHeight = 18.sp)
                    }
                    if (a.sources.isNotEmpty()) {
                        TextButton(
                            onClick = onToggleSources,
                            modifier = Modifier.heightIn(min = TvSpace.touch)
                        ) {
                            Text(
                                if (fold.sourcesOpen) "收起来源" else "查看来源",
                                color = Tv.dim, fontSize = TvText.small
                            )
                        }
                        if (fold.sourcesOpen) {
                            a.sources.forEach { s ->
                                val label = when (s.type.name) {
                                    "OFFICIAL" -> "官方资料"
                                    "FORUM" -> "论坛经验"
                                    "USER" -> "用户反馈"
                                    else -> "来源不明"
                                }
                                Text(
                                    "$label · ${s.title.ifBlank { s.url }}",
                                    color = Tv.dimmer, fontSize = TvText.tiny
                                )
                                if (s.url.isNotBlank()) {
                                    Text(s.url, color = Tv.accent, fontSize = TvText.tiny)
                                }
                            }
                        }
                    }

                    val actions = AnswerPresenter.actions(a, hasBrand)
                    if (actions.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.s)) {
                            actions.forEachIndexed { i, act ->
                                if (i == 0) {
                                    Button(
                                        onClick = { onAction(act) },
                                        modifier = Modifier.heightIn(min = TvSpace.touch),
                                        colors = ButtonDefaults.buttonColors(containerColor = Tv.accent)
                                    ) {
                                        Text(act.label, color = Tv.onAccent, fontSize = TvText.bodySmall)
                                    }
                                } else {
                                    TextButton(
                                        onClick = { onAction(act) },
                                        modifier = Modifier.heightIn(min = TvSpace.touch)
                                    ) {
                                        Text(act.label, color = Tv.dim, fontSize = TvText.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }

                msg.search?.let { o ->
                    HorizontalDivider(color = Tv.line)
                    Text(
                        if (o.response.isMock) "这是模拟数据，用来验证流程，不能存进知识库。"
                        else "这些是网上找到的原始资料，不是验证过的教程。",
                        color = Tv.warn, fontSize = TvText.tiny
                    )
                    o.response.hits.forEachIndexed { i, hit ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = TvSpace.touch)
                                .clickable { onSelect(i) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selection == i, onClick = { onSelect(i) })
                            Column(Modifier.weight(1f)) {
                                Text(hit.title, color = Tv.dim, fontSize = TvText.bodySmall)
                                Text(hit.url, color = Tv.accent, fontSize = TvText.tiny)
                                Text(
                                    "${SearchService.sourceLabel(hit.sourceType)} · 步骤 ${hit.extractedSteps.size} 条",
                                    color = Tv.dimmer, fontSize = TvText.tiny
                                )
                            }
                        }
                    }
                    note?.let { Text(it, color = Tv.good, fontSize = TvText.tiny) }
                    if (o.canSaveToKb) {
                        Button(
                            onClick = onSave,
                            enabled = selection != null,
                            modifier = Modifier.heightIn(min = TvSpace.touch)
                        ) { Text("存为候选方法", fontSize = TvText.small) }
                    } else {
                        Text(
                            "这次结果不能存为方法（模拟数据或搜索失败）。",
                            color = Tv.warn, fontSize = TvText.tiny
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepCard(index: Int, title: String, detail: String, expect: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Tv.cardHi, RoundedCornerShape(TvSpace.m))
            .padding(TvSpace.m),
        verticalArrangement = Arrangement.spacedBy(TvSpace.xs)
    ) {
        Text("第 $index 步 · $title", color = Tv.good, fontSize = TvText.bodySmall, fontWeight = FontWeight.SemiBold)
        Text("现在做什么：$detail", color = Tv.dim, fontSize = TvText.body)
        if (expect.isNotBlank()) Text(expect, color = Tv.dimmer, fontSize = TvText.tiny)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickRow(items: List<String>, onClick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(TvSpace.s)) {
        items.forEach { q ->
            OutlinedButton(
                onClick = { onClick(q) },
                modifier = Modifier.heightIn(min = TvSpace.touch)
            ) { Text(q, fontSize = TvText.small, color = Tv.dim) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrandChoices(brands: List<Brand>, onPick: (Brand) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(TvSpace.s)) {
        brands.forEach { b ->
            OutlinedButton(
                onClick = { onPick(b) },
                modifier = Modifier.heightIn(min = TvSpace.touch)
            ) { Text(b.name, fontSize = TvText.small, color = Tv.dim) }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Column(Modifier.background(Tv.card).navigationBarsPadding()) {
        HorizontalDivider(color = Tv.line)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TvSpace.m, vertical = TvSpace.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                enabled = enabled,
                placeholder = { Text("说说你的电视，或点上面的快捷回复", fontSize = TvText.small, color = Tv.dimmer) },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(TvSpace.s))
            Button(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
                modifier = Modifier.heightIn(min = TvSpace.touch)
            ) { Text("发送", fontSize = TvText.body) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheet(
    brands: List<Brand>,
    profile: DeviceProfile,
    repo: KbRepository,
    onDismiss: () -> Unit,
    onSave: (DeviceProfile) -> Unit
) {
    var brandId by remember { mutableStateOf(profile.brandId) }
    var brandName by remember { mutableStateOf(profile.brandName) }
    var model by remember { mutableStateOf(profile.model ?: "") }
    var os by remember { mutableStateOf(profile.osVersion ?: "") }

    val models = remember(brandId, brands) {
        brands.firstOrNull { it.id == brandId }?.let { repo.models(it.id) } ?: emptyList()
    }
    val resolved = remember(brandId, model, brands) {
        brandId?.let { repo.resolveModelId(it, model.ifBlank { null }) }
    }
    val unresolved = model.isNotBlank() && resolved == null

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(TvSpace.xl),
            verticalArrangement = Arrangement.spacedBy(TvSpace.m)
        ) {
            Text("我的电视", color = Tv.onAccent, fontSize = TvText.title, fontWeight = FontWeight.Bold)
            Text(
                "这里填一次，聊天和教程页都用这份档案。型号不知道可以空着，我不会假装有型号专属教程。",
                color = Tv.dim, fontSize = TvText.small
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(TvSpace.s)) {
                brands.forEach { b ->
                    val on = b.id == brandId
                    if (on) {
                        Button(
                            onClick = { brandId = b.id; brandName = b.name },
                            modifier = Modifier.heightIn(min = TvSpace.touch)
                        ) { Text(b.name, fontSize = TvText.small) }
                    } else {
                        OutlinedButton(
                            onClick = { brandId = b.id; brandName = b.name },
                            modifier = Modifier.heightIn(min = TvSpace.touch)
                        ) { Text(b.name, fontSize = TvText.small) }
                    }
                }
            }
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("型号（可留空）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (models.isNotEmpty()) {
                Text(
                    "知识库里有：" + models.joinToString("、") { it.displayName },
                    color = Tv.dimmer, fontSize = TvText.tiny
                )
            }
            if (unresolved) {
                Text(
                    "知识库里没有这个型号：保存后只会按品牌通用方法查，不会假装是型号专属。",
                    color = Tv.warn, fontSize = TvText.small
                )
            }
            OutlinedTextField(
                value = os,
                onValueChange = { os = it },
                label = { Text("系统版本（可留空，例如 8.1）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onSave(profile.copy(brandId = brandId, brandName = brandName, model = model.ifBlank { null }, osVersion = os.ifBlank { null })) },
                enabled = brandId != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = TvSpace.touch)
            ) { Text("保存") }
            Spacer(Modifier.size(TvSpace.m))
        }
    }
}
