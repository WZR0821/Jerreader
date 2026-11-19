package com.jerreader.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose port of iOS `AppGuideView`. Section order and wording follow the iOS
 * guide, including the backup chapter and the 2.0 AI 套餐 chapter. Where iOS
 * describes something only it has (钥匙串, Apple 系统翻译, iOS paths), the
 * Android wording for the same thing stays.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppGuideScreen(
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Scaffold(
        // The 2.0 canvas, with its faint accent wash, as iOS draws behind the list.
        modifier = modifier.fillMaxSize().jerreaderCanvasBackground(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = { JerreaderNavigationBar("操作指南", "设置", onBack) },
        bottomBar = bottomBar
    ) { padding ->
        // The iOS guide is a grouped list spaced like the settings forms, with
        // the 2.0 section headers.
        CompositionLocalProvider(
            LocalGroupedFormSpacing provides PlainFormSpacing,
            LocalSettingsHeader provides true
        ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(PlainFormGroupSpacing),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
        ) {
            item { QuickStartSection() }
            item { ImportSection() }
            item { ReadingSection() }
            item { FeatureSection() }
            item { JapaneseSection() }
            item { TranslationSection() }
            item { PlanSection() }
            item { BackupSection() }
            item { FormatSection() }
        }
        }
    }
}

/**
 * iOS `SettingsTint`: each guide entry takes the colour of the part of the
 * app it describes, the same colours as the settings rows.
 */
private object GuideTint {
    val accent: Color
        @Composable get() = jerreaderPalette.accent

    val reading: Color
        @Composable get() = jerreaderPalette.vermilion

    val translation: Color
        @Composable get() = if (jerreaderPalette.isDark) Color(0xFF6FC3D1) else Color(0xFF227C8C)

    val models: Color
        @Composable get() = if (jerreaderPalette.isDark) Color(0xFFB5A3F5) else Color(0xFF6D55C4)

    val learning: Color
        @Composable get() = jerreaderPalette.jade

    val backup: Color
        @Composable get() = jerreaderPalette.gold

    val guide: Color
        @Composable get() = if (jerreaderPalette.isDark) Color(0xFFA4AEBB) else Color(0xFF5F6B7A)

    /** `.orange`, systemOrange: lighter in dark mode. */
    val warning: Color
        @Composable get() = if (jerreaderPalette.isDark) Color(0xFFFF9F0A) else Color(0xFFFF9500)
}

@Composable
private fun QuickStartSection(modifier: Modifier = Modifier) {
    GuideSection("快速开始", modifier) {
        GuideStepRow(1, "导入书籍", "在“书架”右上角点“＋”，先选 EPUB、PDF、DOCX 或 TXT 格式，再选文件；导入完成后会直接打开。")
        GuideStepRow(2, "打开阅读", "轻点封面进入阅读器；应用会自动保存每本书的阅读位置和排版偏好。")
        GuideStepRow(3, "选择翻译", "在设置的“翻译与 AI”中选择“句子”或“段落”，再轻点正文即可一次翻译一个句子或段落；长按会启用系统选区，并可从菜单选择翻译、查词或划线。")
        GuideStepRow(4, "保存学习内容", "查词卡片可直接收藏进生词本，译文卡片可收藏译文；两者统一放在“学习”栏。", showDivider = false)
    }
}

@Composable
private fun ImportSection(modifier: Modifier = Modifier) {
    GuideSection("如何导入", modifier) {
        GuideItem(
            "从“文件”导入",
            "书架 → 右上角“＋” → 先选格式 → 选择文件。选 PDF 时系统文件列表里只有 PDF 能选，其他格式会变灰；导入后直接进入阅读，重复文件会打开书架中的现有副本。支持设备存储、下载目录、云盘和外接存储。",
            JerreaderIcon.FOLDER, GuideTint.accent
        )
        GuideItem(
            "从其他 App 导入",
            "在文件管理器、浏览器、邮件或聊天应用中选择“用其他应用打开”，选择 Jerreader；应用会导入文档并进入阅读页。",
            JerreaderIcon.DOWNLOAD, GuideTint.accent
        )
        GuideItem("从电脑传输", "可通过 USB 文件传输把文件放入设备存储，再从系统“文件”中选择。", JerreaderIcon.EXTERNAL_DRIVE, GuideTint.accent)
        // `JerreaderLabel(…, icon: "lock")` in `.caption`, all of it secondary.
        GuideLabel("导入时只复制到 App 的本地沙盒，不会自动上传书籍。", JerreaderIcon.LOCK, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReadingSection(modifier: Modifier = Modifier) {
    GuideSection("如何阅读与翻译", modifier) {
        GuideItem(
            "翻页",
            "使用工具栏的“上一页 / 下一页”按钮或左右滑动翻页；“上一章 / 下一章”按章节跳转，进度条可拖到整本书的任意位置。",
            JerreaderIcon.GRID, GuideTint.reading
        )
        GuideItem(
            "点句或点段翻译",
            "开启轻点翻译后，可在设置中选择句子或段落，再轻点正文翻译；开启翻页保护时，滑动仍可翻页，点按不会同时触发翻页。",
            JerreaderIcon.SPEECH_BUBBLE_FILLED, GuideTint.translation
        )
        GuideItem(
            "精确框选",
            "长按进入系统选区，拖动手柄调整范围后，从弹出菜单选择“翻译”“查词”或“划线/批注”，应用会严格采用你最后框选的文字。",
            JerreaderIcon.HAND, GuideTint.reading
        )
        GuideItem(
            "只读打开",
            "打开、阅读、选区和翻译不会改写导入后的书籍文件，也不会刷新它的文件修改时间；阅读位置、书签和批注会另存到应用数据库。",
            JerreaderIcon.LOCK, GuideTint.reading
        )
        GuideItem(
            "跨页句段",
            "句子在页边界被截断时，点译文卡片的“扩展跨页句段”；应用会先在本机补齐可确认的句子，再只请求一次翻译。没有可靠上下文时不会臆造内容。",
            JerreaderIcon.GRID, GuideTint.reading
        )
        GuideItem(
            "PDF 翻译",
            "开启轻点翻译后，直接轻点 PDF 句子即可翻译。双栏论文可在阅读设置开启“论文双栏模式”，自动点句、点段和文字识别会只读取点击的左栏或右栏；横跨整页的标题与图注仍按普通版式处理。",
            JerreaderIcon.NOTE, GuideTint.reading, showDivider = false
        )
    }
}

@Composable
private fun FeatureSection(modifier: Modifier = Modifier) {
    GuideSection("功能介绍", modifier) {
        GuideItem(
            "界面主题",
            "设置 → 界面主题有八套色系（海蓝、森林、紫藤、琥珀、莓红、松石、赤陶、石墨），轻点即换，页面顶部的预览会马上跟着变。外观模式可以跟随系统，也可以固定为浅色或深色；背景光晕可以关掉，换成纯净的纸色。阅读页的纸张仍在默认阅读排版里单独设置。",
            JerreaderIcon.PALETTE, GuideTint.accent
        )
        GuideItem(
            "阅读设置与选区色",
            "每本书可独立保存字体、字号、背景、左右边距、行距、段距、翻页模式和选区颜色。浅色、护眼、冷灰、深色会自动采用适配的选区色；自定义背景可另选选区色。设置 → 默认阅读排版可决定新书样式，并用开关持续同步或立即应用到现有全部书籍。",
            JerreaderIcon.TEXT_FORMAT, GuideTint.reading
        )
        GuideItem(
            "目录、搜索、书签与批注",
            "阅读器的“导航”按钮集中提供章节目录、全文搜索、书签和划线批注；点正文上的高亮可直接编辑该条批注。",
            JerreaderIcon.TABLE_OF_CONTENTS, GuideTint.reading
        )
        GuideItem(
            "悬浮译文",
            "译文框会与完整选区留出安全距离，框内则按实际译文收紧，不会为了段落模式强行留出大块空白；长译文达到高度上限后可在框内上下滑动。原版竖排时优先放在原文左侧或右侧；也可拖动顶部手柄手动调整。",
            JerreaderIcon.TRANSLATION_BUBBLE_FILLED, GuideTint.translation
        )
        GuideItem(
            "查词与生词本",
            "“学习”栏包含翻译、生词本、练习与工具和历史四部分；词典查询合并在翻译的“词语”模式里，译出一个词的同时会补充词典形、读音、词性和释义。阅读时长按一个词，从菜单选“查词”也可直接加入生词本。",
            JerreaderIcon.DICTIONARY, GuideTint.learning
        )
        GuideItem(
            "独立翻译",
            "“学习 → 翻译”提供词语和句子两种模式。原文可自动识别或手动选择中文、英语、日语，译文也可在三种语言间选择；页面顶部可单独选择本机翻译、AI 大模型或 AI 代理，不会改动阅读器的翻译设置。词语模式会补充词典详情；日语原文或译文可开启“日语注音”，读音在本机生成，不改写原书。",
            JerreaderIcon.SPEECH_BUBBLE_FILLED, GuideTint.translation
        )
        GuideItem(
            "日语学习增强",
            "日语词条会显示词典形、假名、词性、常见活用和例句；配置 AI 后可结合书中原句继续分析词语的语法角色与语境义。",
            JerreaderIcon.BOOKS, GuideTint.learning
        )
        GuideItem(
            "AI 句子结构分析",
            "译文卡片的“AI 解析”可分析句子主干、成分、从句或修饰关系，以及英语时态、日语助词和活用等语法点；需要先设置 AI 大模型或 AI 代理。",
            JerreaderIcon.SEARCH, GuideTint.models
        )
        GuideItem("学习内容导出", "在“学习”页选择 CSV、Markdown 或 Anki TSV，分享导出到其他应用。", JerreaderIcon.SHARE, GuideTint.learning)
        GuideItem(
            "书籍管理与统计",
            "书架上的“继续阅读”卡片一点即可回到上次打开的书；“阅读统计”汇总累计阅读时长、已开始的书和平均进度。每本书的更多菜单可更换封面，修改书名、作者、语言、系列、文件夹和标签。顶部批量整理可一次处理多本书，文件夹卡片可快速筛选。",
            JerreaderIcon.CHART, GuideTint.backup
        )
        GuideItem(
            "离线能力",
            "阅读、书架、已缓存的翻译与查词结果可离线使用；默认的本机翻译在语言模型下载完成后也可离线运行。",
            JerreaderIcon.EXTERNAL_DRIVE, GuideTint.guide, showDivider = false
        )
    }
}

@Composable
private fun JapaneseSection(modifier: Modifier = Modifier) {
    GuideSection("日文版式", modifier) {
        GuideItem("原版", "保留出版物声明的横排、竖排和翻页方向；传统日文竖排书通常从右向左翻页。", JerreaderIcon.DICTIONARY, GuideTint.reading)
        GuideItem("横排", "把可重排日文 EPUB 统一改为横排，并让章节间和章节内都从左向右翻页。", JerreaderIcon.TEXT_FORMAT, GuideTint.reading)
        GuideItem("竖排", "强制按竖排显示，适合排版信息缺失的日文书。", JerreaderIcon.TEXT_FORMAT, GuideTint.reading)
        GuideFootnote("可在“设置 → 默认阅读排版”选择新书初始偏好，也可在某本书的阅读设置中单独覆盖。")
    }
}

@Composable
private fun TranslationSection(modifier: Modifier = Modifier) {
    GuideSection("翻译 API 如何使用", modifier) {
        GuideDisclosure("方案一：本机翻译（默认，无需 API Key）") {
            GuideBody("在“设置 → 翻译与 AI”选择“Android 本机翻译”即可，这是默认选项。")
            GuideBody("在“设置 → 翻译与 AI → 离线资源”中点“准备中日翻译”。首次准备需要网络，完成后即可离线翻译。")
            GuideBody("本机模型适合快速理解句意；需要更高质量的文学翻译时，可改用下面的 AI 方案。")
        }

        GuideDisclosure("方案二：AI 大模型（填写 API Key）") {
            GuideStepRow(1, "添加供应商", "打开“设置 → AI 大模型”，点“添加供应商”。Claude、ChatGPT、DeepSeek 排在最前，通义千问、豆包、Kimi、智谱、MiniMax、Gemini 依次在后；列表里没有的服务用“自定义”添加。想用 ChatGPT 或 Claude 的订阅额度，见下方“AI 套餐”。")
            GuideStepRow(2, "粘贴 API Key", "粘贴在该平台创建的 Key，勾选要用的模型。官方请求地址和常用模型已经填好，也可以“获取可用模型”从账户返回的列表里选。")
            GuideStepRow(3, "测试并添加", "点“测试连接”，通过后点“添加”。测试只发送固定的短句，不会发送书籍内容。")
            GuideStepRow(4, "开始翻译", "在“设置 → 翻译与 AI”把翻译服务选为“AI 大模型”，之后轻点或框选正文即可。以后用“换模型”在已添加的供应商之间切换。只有你主动选择的文字和必要上下文会发送给该服务商。")
            GuideFootnote("原文语言可自动识别，也可固定为中文、英语或日语；目标语言可在三者间自由选择。展开“提示词与 AI 行为”可以分别修改翻译和句子结构分析提示词。")
            GuideFootnote("每个平台的 Key 独立保存在本机的加密存储中，不会写入书籍、日志或安装包。同一句译文命中缓存时不会再次请求；短暂网络失败可自动重试一次，也可在设置中指定备用翻译服务。每个模型可单独设置接口格式和思考强度。AI 大模型会产生服务商费用，建议在平台设置用量上限并定期更换 Key。")
        }

        GuideDisclosure("方案三：自建 AI 翻译代理（进阶）") {
            GuideStepRow(1, "准备 HTTPS 代理", "代理由你自己控制，并在服务器端安全保存 OpenAI、Claude、Gemini 等供应商密钥。")
            GuideStepRow(2, "填写地址", "在“设置 → 翻译与 AI”选择“AI 代理”，填写完整的 https:// 地址。")
            GuideStepRow(3, "填写模型", "模型名称是可选项，例如由你的代理映射为实际供应商模型。")
            GuideStepRow(4, "填写访问凭据", "如果代理需要鉴权，可填写代理访问令牌；令牌只保存在本机的加密存储中。")
            GuideStepRow(5, "开始翻译", "选择或点击正文后，应用只发送本次选中的文字、必要上下文和语言方向。")
            GuideHeading("代理请求 JSON")
            GuideCodeBlock(
                """
                {
                  "text": "需要翻译的文字",
                  "context": "可选的上下文",
                  "sourceLanguage": "ja",
                  "targetLanguage": "zh-Hans",
                  "model": "可选模型名",
                  "prompt": "可选的自定义提示词"
                }
                """.trimIndent()
            )
            GuideHeading("代理成功响应（二选一字段）")
            GuideCodeBlock("{ \"translatedText\": \"译文\" }")
            GuideFootnote("如填写了代理访问凭据，客户端会使用 Authorization: Bearer <token>。代理应在 30 秒内返回 2xx 与 JSON。出错时显示代理返回的 error 信息，没有时显示 HTTP 状态码；失败的请求可以在翻译卡片中重试。")
        }

        // 2.0: a 16-point triangle beside the caption, both in orange.
        GuideLabel(
            "AI 大模型适合个人自用；准备公开分发时，仍建议用自建代理，避免让共享密钥存在客户端。",
            JerreaderIcon.WARNING, GuideTint.warning, iconSize = 16.dp
        )
    }
}

/** 2.0: paying with a subscription instead of an API key, in the app's own words. */
@Composable
private fun PlanSection(modifier: Modifier = Modifier) {
    GuideSection("AI 套餐", modifier) {
        GuideItem(
            "ChatGPT 套餐（实验）",
            "在“设置 → AI 大模型 → 添加供应商 → 套餐”点“Continue with ChatGPT”，在 OpenAI 的页面登录 ChatGPT 账号，不用 API Key。翻译和语法解释用你的 Plus、Pro 等套餐额度，不另外收费；Plus 的 5 小时额度和 ChatGPT、Codex 等应用共用。额度用完时翻译会停下并提示你，不会改成按量付费。手机上的登录方式 OpenAI 还没有写进文档，所以标为实验；登录不上时请改用 API Key。",
            JerreaderIcon.SPARKLES, GuideTint.models
        )
        GuideItem(
            "账号与模型",
            "登录后，套餐页的“账号与模型”显示账号和套餐，可重新登录、退出登录或“刷新模型”；模型列表来自 OpenAI，只列这个账号能用的模型。“管理用量”打开 ChatGPT 网站的用量设置。登录信息只存在本机安全存储，不进备份。",
            JerreaderIcon.LOCK, GuideTint.guide
        )
        GuideItem(
            "Claude 套餐额度",
            "Claude 的 Max、Ultra 订阅每月附带 API 额度：Max 每月 \$100、Ultra 每月 \$200，每个账单周期刷新，不累积。先在 claude.ai 的“设置 › 账单 › API 额度”领取并关联一个 Console 组织，再到 Claude 控制台新建 API Key，回到“套餐”页点“添加 Claude 并填入 API Key”。额度挂在 Console 组织上，用法和普通 API Key 一样；费用先从额度里扣，扣完后是否继续按量付费，看 Console 里的设置。这份额度只用于 API，不能给 Claude Code 用。",
            JerreaderIcon.STAR_OUTLINE, GuideTint.reading
        )
        GuideItem(
            "编程套餐",
            "Coding Plan、Token Plan 这类编程套餐的条款只允许在编程工具里使用，阅读翻译不在其列，所以这里不提供。",
            JerreaderIcon.HAND, GuideTint.guide, showDivider = false
        )
    }
}

@Composable
private fun BackupSection(modifier: Modifier = Modifier) {
    GuideSection("备份与恢复", modifier) {
        GuideItem(
            "选择备份文件夹",
            "设置 → 备份与恢复 → 选择备份文件夹，可指定本机存储、SD 卡或已安装的云盘目录。自动备份与“立即备份”都会写入这里，下方列表会直接显示其中可恢复的备份。",
            JerreaderIcon.FOLDER, GuideTint.backup
        )
        GuideItem(
            "自动备份",
            "开启后按所选间隔在打开 App 时检查是否到期；可单独选择自动备份包含哪几类内容，并按保存天数、最多份数和总容量从最旧的一份开始清理。",
            JerreaderIcon.CLOCK, GuideTint.backup
        )
        GuideItem(
            "手动备份",
            "点“立即备份”直接保存到所选文件夹；首次使用会先让你选择目录。“更多备份选项”可调整范围、间隔和保留策略。包含书籍文件的备份明显更大，但重装后无需重新找书。",
            JerreaderIcon.SHARE, GuideTint.backup
        )
        GuideItem(
            "恢复",
            "重装后点“从备份文件夹恢复”，选择原来的目录，确认恢复最新有效备份。恢复成功后会继续使用这个目录；也可展开“更多备份选项”选择单个备份文件。恢复只做合并，较新的本机进度会保留。",
            JerreaderIcon.RESTORE, GuideTint.backup
        )
        GuideItem(
            "不进入备份的内容",
            "文件夹授权只保存在当前安装中，重装后需要再次选择同一文件夹。API Key、代理访问凭据和可再生的翻译缓存都不写入备份文件。",
            JerreaderIcon.LOCK, GuideTint.backup, showDivider = false
        )
    }
}

@Composable
private fun FormatSection(modifier: Modifier = Modifier) {
    GuideSection("格式说明", modifier) {
        GuideValueRow("EPUB", "完整阅读与翻译")
        GuideValueRow("PDF", "点句翻译＋本机文字识别")
        GuideValueRow("DOCX", "转换正文后阅读")
        GuideValueRow("TXT", "自动识别常用编码")
        GuideFootnote("DOCX 会提取正文、段落和基础元数据后转换为本地可重排电子书；复杂分页、批注、修订、宏和嵌入对象不会原样保留。旧式 .doc 请先另存为 .docx。扫描 PDF 的文字识别在本机按需执行；倾斜、低清、手写体或复杂多栏版面可能需要重新点击或长按框选。")
    }
}

@Composable
private fun GuideValueRow(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        JerreaderListDivider(Modifier.padding(start = 20.dp))
    }
}

@Composable
private fun GuideSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    SettingsGroup(title = title, modifier = modifier) {
        // Rows only: the section title keeps the settings header metrics.
        SystemLineHeights { Column { content() } }
    }
}

@Composable
private fun GuideStepRow(
    number: Int,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    Column(modifier) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 2.0: a bold, tabular caption digit in the colour drawn on the
            // accent, which is dark on the light dark-mode accents. Android has
            // no SF Rounded, so the digit keeps the system face.
            Box(
                Modifier.size(24.dp).background(jerreaderPalette.accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    number.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                    color = jerreaderPalette.onAccent
                )
            }
            GuideItemText(title, detail)
        }
        if (showDivider) JerreaderListDivider(Modifier.padding(start = 28.dp))
    }
}

/**
 * A guide entry, 2.0: a 30-point tile in the colour of the part of the app it
 * describes, then the title and the detail under it, top-aligned 14 apart.
 * iOS pads the row 4 points more than a plain one, and its rule starts under
 * the title.
 */
@Composable
private fun GuideItem(
    title: String,
    detail: String,
    icon: JerreaderIcon,
    tint: Color,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp).semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            JerreaderIconTile(icon, tint = tint, size = 30.dp)
            GuideItemText(title, detail, Modifier.weight(1f))
        }
        if (showDivider) JerreaderListDivider(Modifier.padding(start = 64.dp))
    }
}

@Composable
private fun GuideItemText(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * `Label` in `.caption` as a List row: the glyph centred in a 28-point box 20
 * points in and the text from 64, glyph and text in one [color].
 */
@Composable
private fun GuideLabel(
    text: String,
    icon: JerreaderIcon,
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            JerreaderGlyph(icon, color, Modifier.size(iconSize))
        }
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** A `.subheadline` semibold title inside a disclosure, over a code block. */
@Composable
private fun GuideHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun GuideBody(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun GuideFootnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 11.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun GuideDisclosure(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }
            .heightIn(min = 44.dp).padding(horizontal = 20.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            JerreaderDisclosureChevron(expanded)
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) { content() }
        }
        JerreaderListDivider(Modifier.padding(start = 20.dp))
    }
}

/** 2.0: the muted surface on the small radius, the code in `.caption` monospaced. */
@Composable
private fun GuideCodeBlock(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JerreaderRadius.small))
            .background(jerreaderPalette.mutedSurface)
            .horizontalScroll(rememberScrollState())
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
        )
    }
}
