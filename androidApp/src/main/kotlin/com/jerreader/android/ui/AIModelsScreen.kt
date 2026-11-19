package com.jerreader.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jerreader.android.translation.AIAPIStyle
import com.jerreader.android.translation.AIModelCatalogException
import com.jerreader.android.translation.AIModelEntry
import com.jerreader.android.translation.AIProviderEntry
import com.jerreader.android.translation.AIProviderKind
import com.jerreader.android.translation.AIProviderSettings
import com.jerreader.android.translation.AIReasoningEffort
import com.jerreader.android.translation.AndroidTranslationSettingsStore
import com.jerreader.shared.translation.TranslationProviderMode
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderIconTile
import com.jerreader.shared.ui.JerreaderPillOption
import com.jerreader.shared.ui.JerreaderPillPicker
import com.jerreader.shared.ui.jerreaderPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * 设置 › AI 大模型, ported from the iOS `AIModelsView` and
 * `AIProviderViews`: the model in use, the providers that were set up, and
 * the pages to add a provider, edit it and tune each of its models.
 */

private const val STACK_SEPARATOR = "\u001E"
private const val FIELD_SEPARATOR = "\u001F"
private const val SHEET_SWITCH = "switch"
private const val SHEET_ADD = "add"
private const val SHEET_HELP = "help"
private const val SHEET_CREDENTIALS = "credentials"
private const val SHEET_ADD_MODEL = "addModel"
private const val ADD_MODE_PRESET = "preset"
private const val ADD_MODE_PLAN = "plan"
private const val ADD_MODE_CUSTOM = "custom"

/** The name the translation service picker gives each mode. */
internal fun providerModeTitle(mode: TranslationProviderMode): String = when (mode) {
    TranslationProviderMode.ON_DEVICE -> "Android 本机翻译"
    TranslationProviderMode.DIRECT_API -> "AI 大模型"
    TranslationProviderMode.BACKEND_PROXY -> "AI 代理"
}

/**
 * The AI 大模型 page and the pages pushed from it. The pages keep their own
 * small stack so the system back gesture walks back through them before it
 * leaves the settings page.
 */
@Composable
internal fun AIModelsScreen(
    store: AndroidTranslationSettingsStore,
    requests: AIProviderRequests,
    backTitle: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {}
) {
    val providers by store.aiProviders.collectAsState()
    val keyed by store.keyedProviderIDs.collectAsState()
    val preferences by store.preferences.collectAsState()
    var stackText by rememberSaveable { mutableStateOf("") }
    val stack = if (stackText.isEmpty()) emptyList() else stackText.split(STACK_SEPARATOR)
    val holder = rememberSaveableStateHolder()
    val push: (String) -> Unit = { page -> stackText = (stack + page).joinToString(STACK_SEPARATOR) }
    val pop: () -> Unit = {
        stack.lastOrNull()?.let { top ->
            holder.removeState(top)
            stackText = stack.dropLast(1).joinToString(STACK_SEPARATOR)
        }
    }
    BackHandler(enabled = stack.isNotEmpty(), onBack = pop)
    val page = stack.lastOrNull()
    holder.SaveableStateProvider(page ?: "root") {
        when {
            page == null -> AIModelsRootPage(
                store = store,
                requests = requests,
                providers = providers,
                keyed = keyed,
                mode = preferences.providerMode,
                backTitle = backTitle,
                onBack = onBack,
                bottomBar = bottomBar,
                openProvider = { push("provider:$it") }
            )
            page.startsWith("provider:") -> {
                val entryID = page.removePrefix("provider:")
                if (entryID == AndroidTranslationSettingsStore.CHATGPT_PLAN_ID) {
                    ChatGPTPlanDetailPage(
                        store = store,
                        providers = providers,
                        entryID = entryID,
                        onBack = pop,
                        bottomBar = bottomBar,
                        openModel = { model -> push("model:$entryID$FIELD_SEPARATOR$model") }
                    )
                } else {
                    ProviderDetailPage(
                        store = store,
                        requests = requests,
                        providers = providers,
                        keyed = keyed,
                        entryID = entryID,
                        onBack = pop,
                        bottomBar = bottomBar,
                        openModel = { model -> push("model:$entryID$FIELD_SEPARATOR$model") }
                    )
                }
            }
            else -> {
                val parts = page.removePrefix("model:").split(FIELD_SEPARATOR, limit = 2)
                ModelDetailPage(
                    store = store,
                    requests = requests,
                    providers = providers,
                    keyed = keyed,
                    entryID = parts[0],
                    modelID = parts.getOrElse(1) { "" },
                    onBack = pop,
                    bottomBar = bottomBar
                )
            }
        }
    }
}

// MARK: AI 大模型

@Composable
private fun AIModelsRootPage(
    store: AndroidTranslationSettingsStore,
    requests: AIProviderRequests,
    providers: AIProviderSettings,
    keyed: Set<String>,
    mode: TranslationProviderMode,
    backTitle: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
    openProvider: (String) -> Unit
) {
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val active = providers.active
    val activeModel = active.model.trim()
    val activeHasKey = providers.activeID in keyed
    val footer = when {
        !activeHasKey -> if (active.kind.usesAccountSignIn) {
            "ChatGPT 套餐还没有登录，登录后才能翻译。"
        } else {
            "这个供应商还没有填 API Key，填好后才能翻译。"
        }
        mode != TranslationProviderMode.DIRECT_API ->
            "翻译服务目前选的是“${providerModeTitle(mode)}”，阅读和学习页暂时不会用这里的模型。"
        else -> null
    }
    val finished: (String) -> Unit = { message ->
        notice = message
        error = null
        sheet = null
    }
    // The plan's session may have ended, or renamed its account, since this page was last shown.
    LaunchedEffect(Unit) { ChatGPTPlanActions.sync(store) }
    AIPageFrame(
        title = "AI 大模型",
        backTitle = backTitle,
        onBack = onBack,
        bottomBar = bottomBar,
        trailing = { AIHelpButton { sheet = SHEET_HELP } },
        sheet = sheet,
        onDismissSheet = { sheet = null },
        sheetContent = { which ->
            when (which) {
                SHEET_SWITCH -> ModelSwitcherSheet(store, providers, keyed, onClose = { sheet = null }, onSwitch = finished)
                SHEET_ADD -> AddProviderSheet(
                    store,
                    requests,
                    providers,
                    keyed,
                    onClose = { sheet = null },
                    onAdded = finished,
                    onOpenPlan = {
                        sheet = null
                        openProvider(AndroidTranslationSettingsStore.CHATGPT_PLAN_ID)
                    }
                )
                else -> AIHelpSheet(onClose = { sheet = null })
            }
        }
    ) { padding ->
        AIListContent(
            Modifier.padding(padding).consumeWindowInsets(padding),
            rowHeight = AIListMetrics.iconRowHeight,
            top = { AIFeedback(error, notice) }
        ) {
            AISection(footer = footer, warnFooter = !activeHasKey) {
                // The line under the card runs its whole width.
                row(divider = 0.dp) {
                    ProviderHeader(
                        active.kind,
                        active.name,
                        caption = "Jerreader 正在用",
                        title = if (activeModel.isEmpty()) "还没有选模型" else store.modelTitle(activeModel, active),
                        detail = active.displayName
                    )
                }
                row { AIActionRow("换模型") { sheet = SHEET_SWITCH } }
                if (activeHasKey && mode != TranslationProviderMode.DIRECT_API) {
                    row {
                        AIActionRow("用它翻译") {
                            store.updateProviderMode(TranslationProviderMode.DIRECT_API)
                            notice = "翻译服务已换成 AI 大模型。"
                            error = null
                        }
                    }
                }
            }
            AISection(
                title = "供应商",
                footer = "每个供应商各自保存密钥、地址和模型。点开可以增删模型，或改接口格式和思考强度。"
            ) {
                providers.entries.forEach { entry ->
                    row(divider = AIListMetrics.iconDivider, key = entry.id) {
                        val hasKey = entry.id in keyed
                        val count = entry.models.size
                        AIRow(onClick = { openProvider(entry.id) }) {
                            ProviderRowLabel(
                                kind = entry.kind,
                                name = entry.name,
                                title = entry.displayName,
                                detail = if (hasKey) "$count 个模型" else "$count 个模型 · ${entry.kind.missingCredentialTag}",
                                modifier = Modifier.weight(1f),
                                detailColor = if (hasKey) Color.Unspecified else aiWarning(),
                                tag = if (entry.id == providers.activeID) "正在用" else null
                            )
                            AIChevron()
                        }
                    }
                }
                row(divider = AIListMetrics.iconDivider) {
                    AIRow(onClick = { sheet = SHEET_ADD }, spacing = 13.dp) {
                        JerreaderIconTile(JerreaderIcon.PLUS, size = 34.dp)
                        Text("添加供应商", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        AIChevron()
                    }
                }
            }
        }
    }
}

/** A preset's description of one of its models, such as “Claude Haiku 5.5 · 日常翻译”. */
private fun presetDetail(kind: AIProviderKind, modelID: String): String? =
    kind.modelPresets.firstOrNull { it.id == modelID }?.let { "${it.title} · ${it.detail}" }

// MARK: 换模型

@Composable
private fun ModelSwitcherSheet(
    store: AndroidTranslationSettingsStore,
    providers: AIProviderSettings,
    keyed: Set<String>,
    onClose: () -> Unit,
    onSwitch: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        AISheetBar("换模型", trailing = AIBarAction("完成", onClick = onClose))
        AIListContent(sheet = true) {
            providers.entries.forEachIndexed { index, entry ->
                val hasKey = entry.id in keyed
                key(entry.id) {
                    AISection(
                        title = entry.displayName,
                        footer = if (hasKey) null else "${entry.kind.missingCredentialText}，暂时不能选。",
                        leading = index == 0
                    ) {
                        entry.models.forEach { model ->
                            row(key = model.id) {
                                // A plan model goes by the name its account gives it, with its id underneath.
                                val title = store.modelTitle(model.id, entry)
                                val detail = if (entry.kind.usesAccountSignIn) {
                                    model.id.takeIf { it != title }
                                } else {
                                    presetDetail(entry.kind, model.id)
                                }
                                AIRow(
                                    onClick = {
                                        store.changeProviders { it.activating(entry.id, model.id) }
                                        onSwitch("已换成 ${entry.displayName} 的 $title。")
                                    },
                                    enabled = hasKey
                                ) {
                                    ModelLabel(title, detail, Modifier.weight(1f), dimmed = !hasKey)
                                    if (providers.isInUse(model.id, entry.id)) {
                                        JerreaderGlyph(
                                            JerreaderIcon.CHECK,
                                            jerreaderPalette.accent,
                                            Modifier.size(18.dp).semantics { contentDescription = "正在用" }
                                        )
                                    }
                                }
                            }
                        }
                        if (entry.models.isEmpty()) {
                            row {
                                AIRow {
                                    Text(
                                        "还没有模型，到这个供应商的页面里添加。",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: 说明

private val AI_HELP_ITEMS = listOf(
    "供应商是什么" to "提供大模型接口的服务商，比如 Claude、ChatGPT、DeepSeek。Jerreader 自己不提供模型：你在服务商那里申请 API Key 填到这里，翻译时由 Jerreader 直接向它发请求，费用由服务商按用量收取。",
    "怎么添加" to "点“添加供应商”，选一家，填上 API Key 就可以。列表里没有的服务用“自定义”，填它的 API 地址、接口格式和模型 ID。",
    "套餐" to "不想按量付 API 费用，可以在“添加供应商 › 套餐”里用订阅额度：ChatGPT 套餐用 ChatGPT 账号登录，翻译用 Plus、Pro 等套餐的额度（实验功能）；Claude 的 Max、Ultra 每月附带 API 额度，领取后用 Console 的 API Key 添加 Claude 即可。编程专用的套餐条款不允许用在这里，所以没有列出。",
    "换模型" to "一个供应商下面可以留几个模型。“Jerreader 正在用”的那一个负责翻译和句子结构分析；点“换模型”可以在所有填了密钥的供应商之间切换。",
    "接口格式和思考强度" to "点开某个模型可以单独设置。接口格式照服务商文档选，测试连接能通就对；思考强度越高，译得越慢、费用越高，日常翻译用“自动”即可。",
    "会发送什么" to "只发送你选中或输入的文字，以及必要的上下文。API Key 只保存在本机安全存储里，不会写进备份。"
)

@Composable
private fun AIHelpSheet(onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AISheetBar("说明", trailing = AIBarAction("完成", onClick = onClose))
        AIListContent(sheet = true) {
            AI_HELP_ITEMS.forEach { (title, text) ->
                AISection {
                    row {
                        // iOS gives each item 6 points more above and below than a plain row.
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// MARK: 添加供应商

@Composable
private fun AddProviderSheet(
    store: AndroidTranslationSettingsStore,
    requests: AIProviderRequests,
    providers: AIProviderSettings,
    keyed: Set<String>,
    onClose: () -> Unit,
    onAdded: (String) -> Unit,
    onOpenPlan: () -> Unit
) {
    var mode by rememberSaveable { mutableStateOf(ADD_MODE_PRESET) }
    var setupRaw by rememberSaveable { mutableStateOf<String?>(null) }
    var help by rememberSaveable { mutableStateOf(false) }
    val setupKind = setupRaw?.let { AIProviderKind.fromRaw(it) }
    AIWithSheet(
        sheet = if (help) SHEET_HELP else null,
        onDismiss = { help = false },
        sheetContent = { AIHelpSheet(onClose = { help = false }) }
    ) {
        if (setupKind != null) {
            // A preset's form is pushed inside the sheet; back returns to the list.
            BackHandler { setupRaw = null }
            ProviderSetupForm(
                kind = setupKind,
                store = store,
                requests = requests,
                onAdded = onAdded,
                back = AIBarAction("添加供应商") { setupRaw = null }
            )
        } else {
            val modePicker: @Composable () -> Unit = {
                // The 2.0 pill control: the chosen way sits on the theme colour.
                JerreaderPillPicker(
                    options = listOf(
                        JerreaderPillOption(ADD_MODE_PRESET, "预设"),
                        JerreaderPillOption(ADD_MODE_PLAN, "套餐"),
                        JerreaderPillOption(ADD_MODE_CUSTOM, "自定义")
                    ),
                    selected = mode,
                    onSelect = { mode = it },
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)
                        .semantics { contentDescription = "添加方式" }
                        .testTag("addProviderMode")
                )
            }
            if (mode == ADD_MODE_CUSTOM) {
                ProviderSetupForm(
                    kind = AIProviderKind.OPENAI_COMPATIBLE,
                    store = store,
                    requests = requests,
                    onAdded = onAdded,
                    leading = AIBarAction("取消", onClick = onClose),
                    header = modePicker
                )
            } else if (mode == ADD_MODE_PLAN) {
                PlanOptionsList(
                    store = store,
                    providers = providers,
                    keyed = keyed,
                    header = {
                        AISheetBar("添加供应商", leading = AIBarAction("取消", onClick = onClose))
                        modePicker()
                    },
                    // Claude's form is pushed inside the sheet, like a preset's.
                    onAddClaude = { setupRaw = AIProviderKind.ANTHROPIC.raw },
                    onOpenAccount = onOpenPlan,
                    finish = onAdded
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    AISheetBar(
                        "添加供应商",
                        leading = AIBarAction("取消", onClick = onClose),
                        trailingContent = { AIHelpButton { help = true } }
                    )
                    modePicker()
                    AIListContent(sheet = true, rowHeight = AIListMetrics.iconRowHeight) {
                        AISection(footer = "选一家，填上它的 API Key。想用 ChatGPT 或 Claude 的订阅额度，看“套餐”；没有列出的服务，用“自定义”添加。") {
                            AIProviderKind.presets.forEach { kind ->
                                row(divider = AIListMetrics.iconDivider, key = kind.raw) {
                                    val added = providers.hasSetUp(kind, keyed)
                                    AIRow(
                                        modifier = if (added) Modifier.alpha(0.55f) else Modifier,
                                        onClick = { setupRaw = kind.raw },
                                        enabled = !added
                                    ) {
                                        ProviderRowLabel(
                                            kind = kind,
                                            name = null,
                                            title = kind.title,
                                            detail = kind.subtitle,
                                            modifier = Modifier.weight(1f),
                                            tag = if (!added && kind.isRecommended) "推荐" else null
                                        )
                                        if (added) {
                                            Text("已添加", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            AIChevron()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The formats offered for a model: the three selectable ones, plus the current one if it is not among them. */
private fun styleOptions(current: AIAPIStyle): List<Pair<AIAPIStyle, String>> =
    (AIAPIStyle.selectable + current).distinct().map { it to it.displayName }

/** Key, models and address for one new provider. */
@Composable
private fun ProviderSetupForm(
    kind: AIProviderKind,
    store: AndroidTranslationSettingsStore,
    requests: AIProviderRequests,
    onAdded: (String) -> Unit,
    leading: AIBarAction? = null,
    back: AIBarAction? = null,
    header: @Composable () -> Unit = {}
) {
    val custom = kind.isCustom
    val customID = rememberSaveable { AIProviderEntry.newCustomID() }
    var name by rememberSaveable { mutableStateOf("") }
    // The key is kept out of the saved instance state on purpose.
    var key by remember { mutableStateOf("") }
    var baseURL by rememberSaveable { mutableStateOf(kind.defaultBaseURL) }
    var styleRaw by rememberSaveable { mutableStateOf(kind.defaultStyle.raw) }
    var chosenText by rememberSaveable { mutableStateOf(kind.modelPresets.joinToString("\n") { it.id }) }
    var modelID by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var fetched by remember { mutableStateOf<List<String>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    val style = AIAPIStyle.fromRaw(styleRaw) ?: kind.defaultStyle
    val chosen = chosenText.split("\n").filter(String::isNotEmpty).toSet()
    val modelIDs = if (custom) {
        listOfNotNull(modelID.trim().takeIf(String::isNotEmpty))
    } else {
        kind.modelPresets.map { it.id }.filter { it in chosen }
    }
    val canAdd = key.trim().isNotEmpty() && modelIDs.isNotEmpty() && baseURL.trim().isNotEmpty()

    // What would be saved; the first chosen model becomes the one in use.
    fun draft() = AIProviderEntry(
        id = if (custom) customID else kind.raw,
        kind = kind,
        name = if (custom) name.trim().ifEmpty { null } else null,
        baseURL = baseURL.trim(),
        model = modelIDs.firstOrNull().orEmpty(),
        models = modelIDs.map { AIModelEntry(it, style) }
    )

    fun add() {
        focus.clearFocus()
        val value = draft()
        try {
            val activated = store.addProvider(value, key)
            val title = value.displayName
            onAdded(
                when {
                    !activated -> "已添加 $title。要用它，点“换模型”。"
                    store.preferences.value.providerMode == TranslationProviderMode.DIRECT_API -> "已添加 $title，现在用它翻译。"
                    else -> "已添加 $title，它现在是正在用的模型。"
                }
            )
        } catch (failure: Exception) {
            error = AIProviderRequests.message(failure)
            notice = null
        }
    }

    fun fetchModels() {
        focus.clearFocus()
        error = null
        notice = null
        val value = draft()
        val apiKey = key.trim()
        if (apiKey.isEmpty()) {
            error = AIModelCatalogException.configuration().message
            return
        }
        busy = true
        job = scope.launch {
            try {
                fetched = requests.models(value.baseURL, style, apiKey)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = AIProviderRequests.message(failure)
            } finally {
                busy = false
            }
        }
    }

    fun testConnection() {
        focus.clearFocus()
        error = null
        notice = null
        val value = draft()
        val configuration = try {
            store.configuration(value, value.activeModel, key)
        } catch (failure: Exception) {
            error = AIProviderRequests.message(failure)
            return
        }
        busy = true
        job = scope.launch {
            try {
                val seconds = requests.check(configuration)
                notice = "连接正常，用时 ${AIProviderRequests.seconds(seconds)} 秒。点“添加”保存。"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = AIProviderRequests.message(failure)
            } finally {
                busy = false
            }
        }
    }

    AIWithSheet(
        sheet = fetched,
        onDismiss = { fetched = null },
        sheetContent = { list ->
            AIFetchedModelPicker(list, kept = emptySet(), onClose = { fetched = null }, choose = { modelID = it })
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            AISheetBar(
                title = if (custom) "添加供应商" else kind.title,
                leading = leading,
                back = back,
                trailing = AIBarAction("添加", enabled = !busy && canAdd, emphasized = true) { add() }
            )
            header()
            AIListContent(
                sheet = true,
                top = {
                    if (busy) AIBusyBar { job?.cancel() }
                    AIFeedback(error, notice)
                }
            ) {
                if (custom) {
                    AISection(
                        title = "服务",
                        leading = true,
                        footer = "填服务商文档里的 API 地址，一般以 /v1 结尾，必须是 HTTPS。接口格式照文档选；测试连接通了就对。"
                    ) {
                        row { AIFieldRow(name, { name = it }, "名称（可不填）", enabled = !busy) }
                        row {
                            AIFieldRow(baseURL, { baseURL = it }, "https://…", label = "API 地址", keyboardType = KeyboardType.Uri, enabled = !busy)
                        }
                        row { AIMenuRow("接口格式", styleOptions(style), style, { styleRaw = it.raw }, enabled = !busy) }
                    }
                    AISection(title = "API Key", footer = "密钥只保存在本机安全存储中，不会写进备份。") {
                        row { AIAPIKeyRow(key, { key = it }, enabled = !busy) }
                    }
                    AISection(title = "模型", footer = "直接填模型 ID，或在填好地址和密钥后从服务返回的列表里选。") {
                        row { AIFieldRow(modelID, { modelID = it }, "模型 ID", enabled = !busy) }
                        row { AIActionRow("获取可用模型", enabled = !busy) { fetchModels() } }
                    }
                } else {
                    AISection {
                        row(divider = 0.dp) { ProviderHeader(kind, null, caption = null, title = kind.title, detail = kind.subtitle) }
                    }
                    AISection(title = "API Key", footer = "密钥只保存在本机安全存储中，不会写进备份。") {
                        row { AIAPIKeyRow(key, { key = it }, enabled = !busy) }
                        kind.keyURL?.let { url -> row { AIExternalLinkRow("去 ${kind.title} 控制台申请密钥", url) } }
                    }
                    AISection(title = "模型", footer = "勾上的模型会一起加入，排在最前的那个先用。以后可以在供应商页面里增删。") {
                        kind.modelPresets.forEach { preset ->
                            row(key = preset.id) {
                                AICheckRow(preset.id, "${preset.title} · ${preset.detail}", preset.id in chosen, enabled = !busy) {
                                    val next = if (preset.id in chosen) chosen - preset.id else chosen + preset.id
                                    chosenText = kind.modelPresets.map { it.id }.filter { it in next }.joinToString("\n")
                                }
                            }
                        }
                    }
                    AISection(footer = kind.setupNote) {
                        row { AIDisclosureRow("高级", advanced, { advanced = !advanced }, enabled = !busy) }
                        if (advanced) {
                            row {
                                AIFieldRow(baseURL, { baseURL = it }, "https://…", label = "API 地址", keyboardType = KeyboardType.Uri, enabled = !busy)
                            }
                            row { AIMenuRow("接口格式", styleOptions(style), style, { styleRaw = it.raw }, enabled = !busy) }
                        }
                    }
                }
                AISection(footer = "用上面填写的内容发送一句“Hello.”，可能产生少量 API 费用。") {
                    row { AIActionRow("测试连接", enabled = !busy) { testConnection() } }
                }
                if (!custom) {
                    AISection { row { AIExternalLinkRow("${kind.title} 官方文档", kind.documentationURL) } }
                }
            }
        }
    }
}

// MARK: One provider

@Composable
private fun ProviderDetailPage(
    store: AndroidTranslationSettingsStore,
    requests: AIProviderRequests,
    providers: AIProviderSettings,
    keyed: Set<String>,
    entryID: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
    openModel: (String) -> Unit
) {
    val entry = providers.entry(entryID)
    if (entry == null) {
        // Removed elsewhere (a restore, say): there is nothing left to show.
        LaunchedEffect(entryID) { onBack() }
        return
    }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val kind = entry.kind
    val isActive = providers.activeID == entryID
    val hasKey = entryID in keyed
    val finished: (String) -> Unit = { message ->
        notice = message
        error = null
        sheet = null
    }
    AIPageFrame(
        title = entry.displayName,
        backTitle = "AI 大模型",
        onBack = onBack,
        bottomBar = bottomBar,
        sheet = sheet,
        onDismissSheet = { sheet = null },
        sheetContent = { which ->
            when (which) {
                SHEET_CREDENTIALS -> CredentialsEditorSheet(store, entry, onClose = { sheet = null }, onSaved = finished)
                else -> AddModelSheet(store, requests, entry, hasKey, onClose = { sheet = null }, onAdded = finished)
            }
        }
    ) { padding ->
        AIListContent(
            Modifier.padding(padding).consumeWindowInsets(padding),
            top = { AIFeedback(error, notice) }
        ) {
            AISection {
                row(divider = 0.dp) {
                    ProviderHeader(
                        kind,
                        entry.name,
                        caption = null,
                        title = entry.displayName,
                        detail = if (kind.isCustom) entry.host else kind.subtitle
                    )
                }
            }
            AISection(title = "密钥与地址", footer = if (kind.isCustom) "点一下修改名称、密钥或地址。" else kind.setupNote) {
                // Both rows open the same editor.
                row {
                    AIRow(onClick = { sheet = SHEET_CREDENTIALS }) {
                        AIValueRowContent("API Key", if (hasKey) "已填写" else "未填写", warn = !hasKey)
                    }
                }
                row { AIRow(onClick = { sheet = SHEET_CREDENTIALS }) { AIValueRowContent("API 地址", entry.host) } }
            }
            AISection(title = "模型", footer = "点开模型可改接口格式、思考强度，或设为正在用。") {
                entry.models.forEach { model ->
                    row(key = model.id) {
                        AIRow(modifier = Modifier.testTag("model-" + model.id), onClick = { openModel(model.id) }) {
                            ModelLabel(
                                model.id,
                                modelDetail(kind, model),
                                Modifier.weight(1f),
                                tag = if (providers.isInUse(model.id, entryID)) "正在用" else null
                            )
                            AIChevron()
                        }
                    }
                }
                row { AIActionRow("添加模型") { sheet = SHEET_ADD_MODEL } }
            }
            AISection {
                row {
                    AIExternalLinkRow(
                        if (kind.isCustom) "OpenAI 兼容接口说明" else "${kind.title} 官方文档",
                        kind.documentationURL
                    )
                }
            }
            AISection(footer = if (isActive) "正在用的供应商不能删除。先用“换模型”换到别的供应商。" else null) {
                row {
                    AIActionRow(
                        "删除这个供应商",
                        enabled = !isActive,
                        destructive = true,
                        modifier = Modifier.testTag("deleteProviderButton")
                    ) { confirmDelete = true }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${entry.displayName}？") },
            text = { Text("它的 API Key、地址和模型设置都会从本机移除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    try {
                        store.removeProvider(entryID)
                        onBack()
                    } catch (failure: Exception) {
                        error = AIProviderRequests.message(failure)
                        notice = null
                    }
                }) { Text("删除", color = aiRed()) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}

/** A model's preset description, interface format and reasoning depth. */
private fun modelDetail(kind: AIProviderKind, model: AIModelEntry): String {
    val parts = mutableListOf<String>()
    presetDetail(kind, model.id)?.let(parts::add)
    parts += model.style.displayName
    model.reasoning?.let { parts += "思考" + it.title }
    return parts.joinToString(" · ")
}

/** Edits a provider's key and address. Nothing changes until 保存. */
@Composable
private fun CredentialsEditorSheet(
    store: AndroidTranslationSettingsStore,
    entry: AIProviderEntry,
    onClose: () -> Unit,
    onSaved: (String) -> Unit
) {
    val kind = entry.kind
    val savedKey = remember(entry.id) { store.apiKey(entry.id) }
    var key by remember(entry.id) { mutableStateOf(savedKey) }
    var name by rememberSaveable { mutableStateOf(entry.name.orEmpty()) }
    var baseURL by rememberSaveable { mutableStateOf(entry.baseURL) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val changed = key != savedKey || baseURL != entry.baseURL || name != entry.name.orEmpty()

    fun save() {
        focus.clearFocus()
        val address = baseURL.trim().ifEmpty { kind.defaultBaseURL }
        try {
            store.saveProvider(entry.id, name, address, key)
            onSaved("已保存。")
        } catch (failure: Exception) {
            error = AIProviderRequests.message(failure)
        }
    }

    Column(Modifier.fillMaxSize()) {
        AISheetBar(
            "密钥与地址",
            leading = AIBarAction("取消", onClick = onClose),
            trailing = AIBarAction("保存", enabled = changed, emphasized = true) { save() }
        )
        AIListContent(sheet = true, top = { AIFeedback(error, null) }) {
            if (kind.isCustom) {
                AISection(title = "名称", leading = true) { row { AIFieldRow(name, { name = it }, "自定义 API", label = "名称") } }
            }
            AISection(
                title = "API Key",
                footer = "密钥只保存在本机安全存储中。清空后保存会删除已存的密钥。",
                leading = !kind.isCustom
            ) {
                row { AIAPIKeyRow(key, { key = it }) }
            }
            AISection(
                title = "API 地址",
                footer = if (kind.isCustom) "服务商文档里的 API 地址，必须是 HTTPS。" else kind.setupNote
            ) {
                row { AIFieldRow(baseURL, { baseURL = it }, "https://…", label = "API 地址", keyboardType = KeyboardType.Uri) }
                if (!kind.isCustom && baseURL != kind.defaultBaseURL) {
                    row { AIActionRow("恢复官方地址") { baseURL = kind.defaultBaseURL } }
                }
            }
        }
    }
}

/** Adds a model to a provider: a preset, one the account can use, or a typed id. */
@Composable
private fun AddModelSheet(
    store: AndroidTranslationSettingsStore,
    requests: AIProviderRequests,
    entry: AIProviderEntry,
    hasKey: Boolean,
    onClose: () -> Unit,
    onAdded: (String) -> Unit
) {
    var modelID by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var fetched by remember { mutableStateOf<List<String>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val kept = entry.models.map { it.id }.toSet()
    val typed = modelID.trim()
    val presets = entry.kind.modelPresets.filter { it.id !in kept }

    fun add(id: String) {
        focus.clearFocus()
        if (id.isEmpty() || id in kept) return
        // A new model starts with the format the provider's other models use.
        store.changeProviders { providers ->
            providers.updating(entry.id) { provider -> provider.upserting(AIModelEntry(id, provider.styleForNewModel)) }
        }
        onAdded("已添加 $id。")
    }

    fun fetchModels() {
        focus.clearFocus()
        error = null
        val apiKey = store.apiKey(entry.id)
        if (!hasKey || apiKey.isEmpty()) {
            error = AIModelCatalogException.configuration().message
            return
        }
        busy = true
        job = scope.launch {
            try {
                fetched = requests.models(entry.baseURL, entry.styleForNewModel, apiKey)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = AIProviderRequests.message(failure)
            } finally {
                busy = false
            }
        }
    }

    AIWithSheet(
        sheet = fetched,
        onDismiss = { fetched = null },
        sheetContent = { list ->
            AIFetchedModelPicker(list, kept, onClose = { fetched = null }, choose = { add(it) })
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            AISheetBar("添加模型", leading = AIBarAction("取消", onClick = onClose))
            AIListContent(
                sheet = true,
                top = {
                    if (busy) AIBusyBar { job?.cancel() }
                    AIFeedback(error, null)
                }
            ) {
                if (presets.isNotEmpty()) {
                    AISection(title = "常用模型", leading = true) {
                        presets.forEach { preset ->
                            row(key = preset.id) {
                                AIRow(onClick = { add(preset.id) }, enabled = !busy) {
                                    ModelLabel(preset.id, "${preset.title} · ${preset.detail}", Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                AISection(
                    title = "手动填写",
                    leading = presets.isEmpty(),
                    footer = if (typed in kept) "这个模型已经在列表里了。" else "照服务商文档里的模型 ID 填写。"
                ) {
                    row { AIFieldRow(modelID, { modelID = it }, "模型 ID", enabled = !busy) }
                    row { AIActionRow("添加这个模型", enabled = !busy && typed.isNotEmpty() && typed !in kept) { add(typed) } }
                }
                AISection(footer = "用已保存的密钥向服务商查询这个账户能用的模型。") {
                    row { AIActionRow("获取可用模型", enabled = !busy) { fetchModels() } }
                }
            }
        }
    }
}

// MARK: One model

private sealed interface ModelTestOutcome {
    data class Passed(val seconds: Double) : ModelTestOutcome
    data class Failed(val message: String) : ModelTestOutcome
}

/** One model: its interface format, reasoning depth, a connection test, and the switches to use or delete it. */
@Composable
private fun ModelDetailPage(
    store: AndroidTranslationSettingsStore,
    requests: AIProviderRequests,
    providers: AIProviderSettings,
    keyed: Set<String>,
    entryID: String,
    modelID: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit
) {
    val entry = providers.entry(entryID)
    val model = entry?.models?.firstOrNull { it.id == modelID }
    if (entry == null || model == null) {
        LaunchedEffect(entryID, modelID) { onBack() }
        return
    }
    var advanced by rememberSaveable { mutableStateOf(true) }
    var testing by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ModelTestOutcome?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val kind = entry.kind
    val inUse = providers.isInUse(modelID, entryID)
    val hasKey = entryID in keyed
    // A ChatGPT plan model: named by its account, signed in instead of keyed, Responses only.
    val isPlan = kind.usesAccountSignIn
    val shownTitle = store.modelTitle(modelID, entry)

    // Saves a change to this model at once; an earlier test result no longer applies.
    fun update(changed: AIModelEntry) {
        if (changed == model) return
        store.changeProviders { providers -> providers.updating(entryID) { it.upserting(changed) } }
        error = null
        outcome = null
    }

    fun test() {
        val configuration = try {
            store.configuration(entry, model, store.apiKey(entryID))
        } catch (failure: Exception) {
            outcome = ModelTestOutcome.Failed(AIProviderRequests.message(failure))
            return
        }
        testing = true
        outcome = null
        error = null
        job = scope.launch {
            try {
                outcome = ModelTestOutcome.Passed(requests.check(configuration))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                outcome = ModelTestOutcome.Failed(AIProviderRequests.message(failure))
            } finally {
                testing = false
            }
        }
    }

    AIPageFrame(
        title = "模型",
        backTitle = providers.displayName(entryID),
        onBack = {
            job?.cancel()
            onBack()
        },
        bottomBar = bottomBar
    ) { padding ->
        AIListContent(
            Modifier.padding(padding).consumeWindowInsets(padding),
            top = { AIFeedback(error, null) }
        ) {
            AISection {
                row(divider = 0.dp) {
                    ProviderHeader(
                        kind,
                        entry.name,
                        caption = null,
                        title = shownTitle,
                        detail = if (isPlan && shownTitle != modelID) modelID else providers.displayName(entryID)
                    )
                }
            }
            if (!isPlan) {
                AISection(title = "接口格式", footer = "照服务商文档里写的格式选；测试连接通了就对。") {
                    row {
                        // Gemini's own format is offered only to a model that already uses it.
                        AIMenuRow("接口格式", styleOptions(model.style), model.style, { update(model.copy(style = it)) })
                    }
                }
            }
            if (model.style.supportsReasoning) {
                AISection(
                    footer = if (advanced) {
                        kind.reasoningNote + "强度越高越慢、费用越高。模型不支持所选档位时会显示服务商原话，改回自动即可。"
                    } else {
                        null
                    }
                ) {
                    row { AIDisclosureRow("高级", advanced, { advanced = !advanced }) }
                    if (advanced) {
                        row {
                            val options: List<Pair<AIReasoningEffort?, String>> =
                                listOf<Pair<AIReasoningEffort?, String>>(null to "自动") + kind.reasoningOptions.map { it to it.title }
                            AIMenuRow("思考强度", options, model.reasoning, { update(model.copy(reasoning = it)) })
                        }
                    }
                }
            }
            AISection(
                footer = if (isPlan) {
                    "发送一句“Hello.”看这个模型能不能用，会用掉一点套餐额度。"
                } else {
                    "发送一句“Hello.”看这个模型能不能用，可能产生少量 API 费用。"
                }
            ) {
                row {
                    AIRow(onClick = { test() }, enabled = !testing) {
                        Text(
                            "测试连接",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (testing) aiTertiary() else MaterialTheme.colorScheme.primary
                        )
                        if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                row {
                    AIRow {
                        when (val result = outcome) {
                            is ModelTestOutcome.Passed -> {
                                JerreaderGlyph(JerreaderIcon.CHECK_CIRCLE, jerreaderPalette.jade, Modifier.size(18.dp))
                                Text(
                                    "连接正常，用时 ${AIProviderRequests.seconds(result.seconds)} 秒",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = jerreaderPalette.jade
                                )
                            }
                            is ModelTestOutcome.Failed -> {
                                JerreaderGlyph(JerreaderIcon.WARNING, jerreaderPalette.vermilion, Modifier.size(18.dp))
                                // Selectable, so OpenAI's reason and request id can be copied.
                                SelectionContainer(modifier = Modifier.weight(1f)) {
                                    Text(
                                        result.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            null -> Text(
                                if (testing) "正在等服务商回复…" else "还没有测试过",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            AISection(
                // The fresh default is in use without a key; it then needs the key to translate.
                footer = when {
                    hasKey -> null
                    isPlan -> if (inUse) "还没有登录 ChatGPT，登录后才能翻译。" else "还没有登录 ChatGPT，登录后才能设为正在用。"
                    inUse -> "这个供应商还没有填 API Key，填好后才能翻译。"
                    else -> "这个供应商还没有填 API Key，填好后才能设为正在用。"
                }
            ) {
                row {
                    AIRow(
                        onClick = {
                            store.changeProviders { it.activating(entryID, modelID) }
                            error = null
                        },
                        enabled = !inUse && hasKey
                    ) {
                        Text(
                            if (inUse) "正在使用" else "设为正在用",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (!inUse && hasKey) MaterialTheme.colorScheme.primary else aiTertiary()
                        )
                        if (inUse) JerreaderGlyph(JerreaderIcon.CHECK, jerreaderPalette.accent, Modifier.size(18.dp))
                    }
                }
            }
            AISection(footer = if (inUse) "正在用的模型不能删除，先换到别的模型。" else null) {
                row { AIActionRow("删除这个模型", enabled = !inUse, destructive = true) { confirmDelete = true } }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 $shownTitle？") },
            text = { Text("只是从这个供应商的列表里移除，以后可以再添加。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    try {
                        store.changeProviders { it.removingModel(modelID, entryID) }
                        job?.cancel()
                        onBack()
                    } catch (failure: Exception) {
                        error = AIProviderRequests.message(failure)
                    }
                }) { Text("删除", color = aiRed()) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}
