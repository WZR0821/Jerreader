package com.jerreader.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerreader.android.translation.AIModelEntry
import com.jerreader.android.translation.AIProviderKind
import com.jerreader.android.translation.AIProviderSettings
import com.jerreader.android.translation.AndroidTranslationSettingsStore
import com.jerreader.android.translation.chatgptplan.ChatGPTPlan
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanAccount
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanError
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanSignIn
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderIconTile
import com.jerreader.shared.ui.JerreaderMotion
import com.jerreader.shared.ui.JerreaderPrimaryButton
import com.jerreader.shared.ui.jerreaderCanvasBackground
import com.jerreader.shared.ui.jerreaderHeroBackground
import com.jerreader.shared.ui.jerreaderPalette
import com.jerreader.shared.ui.jerreaderPaperCard
import com.jerreader.shared.ui.jerreaderPressScale
import com.jerreader.shared.ui.jerreaderReveal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * The AI 套餐 pages, ported from the iOS `AIPlanViews`: the 套餐 tab of
 * 添加供应商, the page shown once after the first ChatGPT sign-in, the ChatGPT
 * plan's own provider page, and the indicator shown near the composer.
 */

private const val SHEET_WELCOME = "planWelcome"
private const val PLAN_CLAUDE_BILLING_URL = "https://claude.ai/settings/billing"
private const val PLAN_CLAUDE_KEYS_URL = "https://platform.claude.com/settings/keys"
private const val CHATGPT_PLAN_FOOTER =
    "走 OpenAI 为开源应用提供的“Sign in with ChatGPT”。手机上的登录方式 OpenAI 还没有写进文档，所以标为实验；登录不上时请改用 API Key。"

// MARK: Shared wording

/** What a provider still needs before it can translate. */
internal val AIProviderKind.missingCredentialText: String
    get() = if (usesAccountSignIn) "还没有登录 ChatGPT" else "还没有填 API Key"

/** The short status shown in provider lists. */
internal val AIProviderKind.missingCredentialTag: String
    get() = if (usesAccountSignIn) "未登录" else "未填密钥"

/** The account's email and plan, such as “name@example.com · Plus”. */
internal fun planAccountLine(account: ChatGPTPlanAccount): String =
    listOfNotNull(account.email, account.planTitle).joinToString(" · ").ifEmpty { "已登录" }

/** The activity a composable is shown in; the sign-in opens the browser from it. */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

/** The steps the plan pages share with each other. */
internal object ChatGPTPlanActions {
    /** Signs in and puts the plan in use. Returns the notice to show. */
    suspend fun signIn(activity: Activity, store: AndroidTranslationSettingsStore, loginHint: String? = null): String {
        val outcome = ChatGPTPlanSignIn.run(activity, store, loginHint)
        store.chatGPTPlanSignedIn(outcome.account, outcome.models)
        val who = outcome.account.email?.let { "（$it）" } ?: ""
        val modelsError = outcome.modelsError
        if (modelsError != null) {
            return "已登录 ChatGPT$who，但没能取到模型列表：${AIProviderRequests.message(modelsError)} 到“ChatGPT 套餐”页面点“刷新模型”重试。"
        }
        return "已登录 ChatGPT$who，现在用套餐额度翻译。"
    }

    suspend fun refreshModels(store: AndroidTranslationSettingsStore): String {
        val models = store.chatGPTPlanSession.models()
        store.chatGPTPlanModelsRefreshed(models)
        return "已刷新，这个账号可以用 ${models.size} 个模型。"
    }

    /** Forgets the account on this device first, then asks OpenAI to end the sign-in. */
    suspend fun signOut(store: AndroidTranslationSettingsStore) {
        store.chatGPTPlanSignedOut()
        store.chatGPTPlanSession.signOut()
    }

    /**
     * Brings the settings in line with the session, which may have ended on
     * its own (a refused refresh) or renamed the account after a refresh.
     */
    fun sync(store: AndroidTranslationSettingsStore) {
        store.chatGPTPlanSessionChanged(store.chatGPTPlanSession.current()?.account)
    }
}

// MARK: 套餐

/**
 * The 套餐 tab of 添加供应商: plans that pay for translations without an API
 * key, kept to what each provider's terms allow. [header] is the sheet's bar
 * and mode picker, drawn here so the welcome page covers them too.
 */
@Composable
internal fun PlanOptionsList(
    store: AndroidTranslationSettingsStore,
    providers: AIProviderSettings,
    keyed: Set<String>,
    header: @Composable () -> Unit,
    onAddClaude: () -> Unit,
    onOpenAccount: () -> Unit,
    finish: (String) -> Unit
) {
    val account by store.chatGPTPlanAccount.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var welcome by rememberSaveable { mutableStateOf(false) }
    var pendingNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val claudeAdded = providers.hasSetUp(AIProviderKind.ANTHROPIC, keyed)

    fun signIn() {
        val activity = context.findActivity()
        if (activity == null) {
            error = AIProviderRequests.message(ChatGPTPlanError.BrowserUnavailable())
            return
        }
        busy = true
        error = null
        job = scope.launch {
            try {
                val notice = ChatGPTPlanActions.signIn(activity, store)
                if (store.chatGPTPlanWelcomeShown) {
                    finish(notice)
                } else {
                    store.chatGPTPlanWelcomeShown = true
                    pendingNotice = notice
                    welcome = true
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: ChatGPTPlanError.Cancelled) {
                // Closed or abandoned by the user: nothing to report.
            } catch (failure: Exception) {
                error = AIProviderRequests.message(failure)
            } finally {
                busy = false
            }
        }
    }

    // The welcome closes first; then the whole 添加供应商 sheet does.
    AIWithSheet(
        sheet = if (welcome) SHEET_WELCOME else null,
        onDismiss = {},
        sheetContent = { _ ->
            ChatGPTPlanWelcome(onDone = {
                welcome = false
                val notice = pendingNotice
                pendingNotice = null
                if (notice != null) finish(notice)
            })
        }
    ) {
      Column(Modifier.fillMaxSize()) {
        header()
        AIListContent(
            sheet = true,
            rowHeight = AIListMetrics.iconRowHeight,
            top = {
                if (busy) AIBusyBar { job?.cancel() }
                AIFeedback(error, null)
            }
        ) {
            // The ChatGPT card is the page's hero, on its own; once signed in,
            // the account rows follow as a group of their own.
            Column(Modifier.fillMaxWidth()) {
                AISection(title = "ChatGPT 套餐", leading = true) {}
                ChatGPTPlanCard(account, busy, onSignIn = { signIn() })
                if (account == null) AISection(footer = CHATGPT_PLAN_FOOTER) {}
            }
            if (account != null) {
                AISection(footer = CHATGPT_PLAN_FOOTER) {
                    row {
                        AIRow(modifier = Modifier.testTag("planAccountLink"), onClick = onOpenAccount) {
                            Text("账号与模型", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            AIChevron()
                        }
                    }
                    row { AIExternalLinkRow("管理用量", ChatGPTPlan.MANAGE_USAGE_URL) }
                }
            }
            AISection(
                title = "Claude 套餐额度",
                footer = "额度挂在 Console 组织上，用法和普通 API Key 一样；费用先从额度里扣，扣完后是否继续按量付费，看 Console 里的设置。"
            ) {
                row { ClaudePlanCard() }
                row { AIExternalLinkRow("打开 claude.ai 的账单设置", PLAN_CLAUDE_BILLING_URL) }
                row { AIExternalLinkRow("去 Claude 控制台创建 API Key", PLAN_CLAUDE_KEYS_URL) }
                row {
                    if (claudeAdded) {
                        AIRow(modifier = Modifier.testTag("planClaudeAdded").semantics(mergeDescendants = true) {}) {
                            Text(
                                "Claude 已添加",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            JerreaderGlyph(JerreaderIcon.CHECK, jerreaderPalette.jade, Modifier.size(18.dp))
                        }
                    } else {
                        AIRow(modifier = Modifier.testTag("planAddClaude"), onClick = onAddClaude) {
                            Text("添加 Claude 并填入 API Key", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            AIChevron()
                        }
                    }
                }
            }
            AISection(
                footer = "Coding Plan、Token Plan 这类编程套餐的条款只允许在编程工具里使用，阅读翻译不在其列，所以这里不提供。",
                modifier = Modifier.testTag("planCodingNote")
            ) {}
        }
      }
    }
}

/** The hero card: the theme colour running into ink, white text, and the sign-in button in white. */
@Composable
private fun ChatGPTPlanCard(account: ChatGPTPlanAccount?, busy: Boolean, onSignIn: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("planChatGPTCard")
            .jerreaderHeroBackground()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PlanCardHeader(
            kind = AIProviderKind.CHATGPT_PLAN,
            title = "ChatGPT 套餐",
            tag = "实验",
            detail = account?.let { planAccountLine(it) } ?: "用 ChatGPT 账号登录，不用 API Key",
            onHero = true
        )
        PlanFacts(
            listOf(
                "翻译和语法解释用你的 Plus、Pro 等套餐额度，不另外收费",
                "Plus 的 5 小时额度和 ChatGPT、Codex 等应用共用",
                "额度用完就暂停，不会改成按量付费",
                "密码只在 OpenAI 的页面输入，登录信息只存在本机安全存储"
            ),
            onHero = true
        )
        if (account == null) {
            Box(Modifier.padding(top = 2.dp)) {
                ContinueWithChatGPTButton(busy = busy, onHero = true, onClick = onSignIn)
            }
        }
    }
}

@Composable
private fun ClaudePlanCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("planClaudeCard")
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PlanCardHeader(
            kind = AIProviderKind.ANTHROPIC,
            title = "Claude Max / Ultra 的 API 额度",
            tag = null,
            detail = "订阅每月附带的 API 额度，用 Console 的 API Key 使用"
        )
        PlanSteps(
            listOf(
                "在 claude.ai 的“设置 › 账单 › API 额度”领取，并关联一个 Console 组织（关联后不能改；新订阅要等 7 天）",
                "到 Claude 控制台新建一个 API Key；建议单独建一个工作区并设好消费上限",
                "回到这里添加 Claude，填上这个 Key"
            )
        )
        PlanFacts(
            listOf(
                "Max 每月 \$100、Ultra 每月 \$200，每个账单周期刷新，不累积",
                "只用于 API，不能给 Claude Code 用"
            )
        )
    }
}

/** The edge of a provider mark on a hero card. */
private val PlanHeroEdge = Color.White.copy(alpha = 0.35f)

/** iOS `title2` in bold: the title of a hero card. */
@Composable
private fun planHeroTitle(): TextStyle =
    MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)

/** Icon, title, tag and one line under it; [onHero] gives white type, a larger title and a white-edged mark. */
@Composable
private fun PlanCardHeader(kind: AIProviderKind, title: String, tag: String?, detail: String, onHero: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        ProviderIcon(kind, null, 44.dp, edge = if (onHero) PlanHeroEdge else null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = if (onHero) planHeroTitle() else MaterialTheme.typography.titleMedium,
                    color = if (onHero) Color.White else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (tag != null) AITagLabel(tag, tint = if (onHero) Color.White else jerreaderPalette.accent)
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (onHero) Color.White.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Short facts with a check in front. */
@Composable
private fun PlanFacts(items: List<String>, onHero: Boolean = false) {
    val check = if (onHero) Color.White else jerreaderPalette.accent
    val text = if (onHero) Color.White.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AIGlyphIcon(AIGlyph.CHECK_CIRCLE_FILLED, check, Modifier.padding(top = 2.dp).size(15.dp))
                Text(item, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = text)
            }
        }
    }
}

/** Numbered steps. */
@Composable
private fun PlanSteps(items: List<String>) {
    val colors = jerreaderPalette
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        items.forEachIndexed { index, item ->
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // The digit takes the colour drawn on the accent, which is dark on
                // the light dark-mode accents.
                Box(
                    Modifier.size(22.dp).background(colors.accent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${index + 1}",
                        color = colors.onAccent,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                }
                Text(
                    item,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * The sign-in button OpenAI's guidelines ask for: its own words, as
 * prominent as any other way to sign in. White with black type on the hero
 * card and in dark mode; black otherwise.
 */
@Composable
internal fun ContinueWithChatGPTButton(busy: Boolean, onHero: Boolean = false, onClick: () -> Unit) {
    val white = onHero || aiIsDark()
    val background = if (white) Color.White else Color.Black
    val foreground = if (white) Color.Black else Color.White
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .testTag("continueWithChatGPTButton")
            .jerreaderPressScale(source)
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = !busy,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = foreground, strokeWidth = 2.dp)
        } else {
            AIGlyphIcon(AIGlyph.PERSON_CHECK, foreground, Modifier.size(20.dp))
        }
        Text(
            "Continue with ChatGPT",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
            maxLines = 1
        )
    }
}

// MARK: Welcome

/**
 * Shown once, after the first sign-in that puts the plan to use: a hero card
 * whose mark springs in (`hero`), then the three facts on paper, revealed in
 * turn, and a success haptic. Only 开始使用 closes it.
 */
@Composable
internal fun ChatGPTPlanWelcome(onDone: () -> Unit) {
    val colors = jerreaderPalette
    val handler = LocalUriHandler.current
    val haptics = LocalHapticFeedback.current
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        pop.animateTo(1f, JerreaderMotion.hero())
    }
    Column(Modifier.fillMaxSize().jerreaderCanvasBackground()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .jerreaderReveal(order = 0)
                    .jerreaderHeroBackground()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // From 60% and clear to full size, with the hero spring's small overshoot.
                ProviderIcon(
                    AIProviderKind.CHATGPT_PLAN,
                    null,
                    64.dp,
                    modifier = Modifier.graphicsLayer {
                        val value = pop.value
                        val scale = 0.6f + 0.4f * value
                        scaleX = scale
                        scaleY = scale
                        alpha = value.coerceIn(0f, 1f)
                    },
                    edge = PlanHeroEdge
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已连接 ChatGPT 套餐", style = planHeroTitle(), color = Color.White)
                    Text(
                        "Jerreader 现在用你的 ChatGPT 套餐翻译和讲解语法。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.82f)
                    )
                }
            }
            Column(Modifier.fillMaxWidth().jerreaderPaperCard()) {
                WelcomeRow(
                    JerreaderIcon.CHART,
                    colors.accent,
                    "用的是套餐额度",
                    "不另外收费。Plus 的 5 小时额度和 ChatGPT、Codex 等应用共用，Pro 没有这个限制。",
                    order = 1,
                    first = true
                )
                WelcomeRow(
                    JerreaderIcon.HAND,
                    SettingsTint.reading,
                    "用完就暂停",
                    "额度用完时翻译会停下并提示你，不会改成按量付费。",
                    order = 2
                )
                WelcomeRow(
                    JerreaderIcon.LOCK,
                    SettingsTint.privacy,
                    "只发送需要的文字",
                    "只把你选中或输入的文字发给 OpenAI；登录信息只存在本机安全存储，不进备份。",
                    order = 3
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            JerreaderPrimaryButton("开始使用", onClick = onDone, modifier = Modifier.testTag("planWelcomeDone"))
            Text(
                "管理用量",
                modifier = Modifier
                    .clickable(role = Role.Button) { runCatching { handler.openUri(ChatGPTPlan.MANAGE_USAGE_URL) } }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.accent
            )
        }
    }
}

@Composable
private fun WelcomeRow(
    icon: JerreaderIcon,
    tint: Color,
    title: String,
    text: String,
    order: Int,
    first: Boolean = false
) {
    val line = jerreaderPalette.line
    Row(
        Modifier
            .fillMaxWidth()
            .jerreaderReveal(order = order)
            // A 0.5 hairline from the text's edge above every row but the first.
            .drawBehind {
                if (!first) {
                    val start = 66.dp.toPx()
                    drawRect(line, topLeft = Offset(start, 0f), size = Size(size.width - start, 0.5.dp.toPx()))
                }
            }
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        JerreaderIconTile(icon, tint = tint, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// MARK: The plan's own page

/**
 * The ChatGPT plan as a provider: its account, its models, sign-in and
 * sign-out. Opened from the providers list like any other provider.
 */
@Composable
internal fun ChatGPTPlanDetailPage(
    store: AndroidTranslationSettingsStore,
    providers: AIProviderSettings,
    entryID: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
    openModel: (String) -> Unit
) {
    val account by store.chatGPTPlanAccount.collectAsState()
    val names by store.chatGPTPlanModelNames.collectAsState()
    val entry = providers.entry(entryID)
    val models = entry?.models.orEmpty()
    val isActive = providers.activeID == entryID
    var busy by remember { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var welcome by rememberSaveable { mutableStateOf(false) }
    var pendingNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) { ChatGPTPlanActions.sync(store) }

    fun perform(work: suspend () -> Unit) {
        busy = true
        error = null
        notice = null
        job = scope.launch {
            try {
                work()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: ChatGPTPlanError.Cancelled) {
                // Closed or abandoned by the user: nothing to report.
            } catch (failure: Exception) {
                if (failure is ChatGPTPlanError.SignInRequired) store.chatGPTPlanSignedOut()
                error = AIProviderRequests.message(failure)
            } finally {
                busy = false
            }
        }
    }

    fun signIn(hint: String?) {
        val activity = context.findActivity()
        if (activity == null) {
            error = AIProviderRequests.message(ChatGPTPlanError.BrowserUnavailable())
            notice = null
            return
        }
        perform {
            val message = ChatGPTPlanActions.signIn(activity, store, hint)
            if (store.chatGPTPlanWelcomeShown) {
                notice = message
            } else {
                store.chatGPTPlanWelcomeShown = true
                pendingNotice = message
                welcome = true
            }
        }
    }

    fun remove() {
        try {
            // Also signs out: the account is forgotten here and revoked at OpenAI.
            store.removeProvider(entryID)
            job?.cancel()
            onBack()
        } catch (failure: Exception) {
            error = AIProviderRequests.message(failure)
            notice = null
        }
    }

    AIPageFrame(
        title = "ChatGPT 套餐",
        backTitle = "AI 大模型",
        onBack = {
            job?.cancel()
            onBack()
        },
        bottomBar = bottomBar,
        sheet = if (welcome) SHEET_WELCOME else null,
        // Only 开始使用 closes the welcome.
        onDismissSheet = {},
        sheetContent = {
            ChatGPTPlanWelcome(onDone = {
                welcome = false
                notice = pendingNotice
                pendingNotice = null
            })
        }
    ) { padding ->
        AIListContent(
            Modifier.padding(padding).consumeWindowInsets(padding),
            top = {
                if (busy) AIBusyBar { job?.cancel() }
                AIFeedback(error, notice)
            }
        ) {
            AISection {
                row(divider = 0.dp) {
                    ProviderHeader(
                        AIProviderKind.CHATGPT_PLAN,
                        null,
                        caption = null,
                        title = "ChatGPT 套餐",
                        detail = account?.let { planAccountLine(it) } ?: "未登录"
                    )
                }
            }
            AISection(
                title = "账号",
                footer = "翻译用这个账号的 ChatGPT 套餐额度。Plus 的 5 小时额度和 ChatGPT、Codex 等共用；用完会暂停，不会改成按量付费。"
            ) {
                val signedIn = account
                if (signedIn != null) {
                    row {
                        AIRow(modifier = Modifier.testTag("planAccountEmail").semantics(mergeDescendants = true) {}) {
                            PlanValueRowContent("账号", signedIn.email ?: "已登录")
                        }
                    }
                    row {
                        AIRow(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                            PlanValueRowContent("套餐", signedIn.planTitle ?: "未知")
                        }
                    }
                    row { AIExternalLinkRow("管理用量", ChatGPTPlan.MANAGE_USAGE_URL) }
                    row {
                        AIActionRow("重新登录", enabled = !busy, modifier = Modifier.testTag("planSignInAgain")) {
                            signIn(signedIn.email)
                        }
                    }
                    row {
                        AIActionRow(
                            "退出登录",
                            enabled = !busy,
                            destructive = true,
                            modifier = Modifier.testTag("planSignOut")
                        ) { confirmSignOut = true }
                    }
                } else {
                    row {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            ContinueWithChatGPTButton(busy = busy) { signIn(null) }
                        }
                    }
                }
            }
            AISection(title = "模型", footer = "列表来自 OpenAI，只列这个账号能用的模型。换了账号或套餐后点“刷新模型”。") {
                models.forEach { model ->
                    row(key = model.id) {
                        val name = names[model.id] ?: model.id
                        AIRow(modifier = Modifier.testTag("model-" + model.id), onClick = { openModel(model.id) }) {
                            ModelLabel(
                                name,
                                planModelDetail(model, name),
                                Modifier.weight(1f),
                                tag = if (providers.isInUse(model.id, entryID)) "正在用" else null
                            )
                            AIChevron()
                        }
                    }
                }
                if (models.isEmpty()) {
                    row {
                        AIRow {
                            Text(
                                if (account == null) "登录后显示这个账号能用的模型。" else "还没有模型，点“刷新模型”。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                row {
                    AIActionRow(
                        "刷新模型",
                        enabled = !busy && account != null,
                        modifier = Modifier.testTag("planRefreshModels")
                    ) { perform { notice = ChatGPTPlanActions.refreshModels(store) } }
                }
            }
            AISection {
                row { AIExternalLinkRow("Sign in with ChatGPT 说明", ChatGPTPlan.DOCUMENTATION_URL) }
            }
            AISection(footer = if (isActive) "正在用的供应商不能删除。先用“换模型”换到别的供应商。" else null) {
                row {
                    AIActionRow(
                        "删除这个供应商",
                        enabled = !isActive && !busy,
                        destructive = true,
                        modifier = Modifier.testTag("deleteProviderButton")
                    ) { confirmDelete = true }
                }
            }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("退出 ChatGPT 登录？") },
            text = { Text("本机删除登录信息，并请 OpenAI 作废这次登录。模型列表会保留，重新登录后接着用。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    perform {
                        ChatGPTPlanActions.signOut(store)
                        notice = "已退出 ChatGPT 登录。"
                    }
                }) { Text("退出登录", color = aiRed()) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("取消") } }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ChatGPT 套餐？") },
            text = { Text("会退出登录，并从本机移除它的模型设置。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    remove()
                }) { Text("删除", color = aiRed()) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}

/** A plan model's id when its name differs, and its reasoning depth. */
private fun planModelDetail(model: AIModelEntry, name: String): String? {
    val parts = mutableListOf<String>()
    if (name != model.id) parts += model.id
    model.reasoning?.let { parts += "思考" + it.title }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

/** A title on the left and its value on the right, without a chevron: `LabeledContent`. */
@Composable
private fun RowScope.PlanValueRowContent(title: String, value: String) {
    Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
    Text(
        value,
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

// MARK: Near the composer

/**
 * “正在使用 ChatGPT 套餐 · 管理用量”, for where text is typed for translation
 * while the plan pays for it.
 */
@Composable
internal fun ChatGPTPlanIndicator(modifier: Modifier = Modifier) {
    val handler = LocalUriHandler.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    // iOS sets it in `caption` (12): small enough to sit beside the result's language label.
    Row(
        modifier
            .testTag("chatGPTPlanIndicator")
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        JerreaderGlyph(JerreaderIcon.SPARKLES, secondary, Modifier.size(12.dp).clearAndSetSemantics {})
        Text(
            "正在使用 ChatGPT 套餐",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = secondary,
            maxLines = 1
        )
        Text("·", modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelMedium, color = secondary)
        Text(
            "管理用量",
            modifier = Modifier.clickable(role = Role.Button) {
                runCatching { handler.openUri(ChatGPTPlan.MANAGE_USAGE_URL) }
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = secondary,
            maxLines = 1
        )
    }
}
