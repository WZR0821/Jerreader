package com.jerreader.shared.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Compose port of iOS `AppGuideView`. Section order and wording follow the iOS
 * guide, including the backup chapter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppGuideScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("操作指南") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
        ) {
            item { QuickStartSection() }
            item { ImportSection() }
            item { ReadingSection() }
            item { FeatureSection() }
            item { JapaneseSection() }
            item { TranslationSection() }
            item { BackupSection() }
            item { FormatSection() }
        }
    }
}

@Composable
private fun QuickStartSection() {
    GuideSection("快速开始") {
        GuideStepRow(1, "导入书籍", "在“书架”右上角点“导入电子书”，选择 EPUB、PDF、DOCX 或 TXT 文件；导入完成后会直接打开。")
        GuideStepRow(2, "打开阅读", "轻点封面进入阅读器；应用会自动保存每本书的阅读位置和排版偏好。")
        GuideStepRow(3, "选择翻译", "在设置的“翻译与 AI”中选择“句子”或“段落”，再轻点正文即可一次翻译一个句子或段落；长按会启用系统选区，并可从菜单选择翻译、查词或划线。")
        GuideStepRow(4, "保存学习内容", "查词卡片可直接收藏进生词本，译文卡片可收藏译文；两者统一放在“学习”栏。")
    }
}

@Composable
private fun ImportSection() {
    GuideSection("如何导入") {
        GuideItem(
            "从系统文件导入",
            "书架 → “导入电子书” → 在系统文件选择器中选择文件。导入后直接进入阅读，重复文件会打开书架中的现有副本。支持设备存储、下载目录、云盘和外接存储。"
        )
        GuideItem(
            "从其他 App 导入",
            "在文件管理器、浏览器、邮件或聊天应用中选择“用其他应用打开”，选择 Jerreader；应用会导入文档并进入阅读页。"
        )
        GuideItem(
            "隐私",
            "导入时只把副本复制到应用私有目录，不会自动上传书籍。原文件的内容和修改时间都不会被改写。"
        )
    }
}

@Composable
private fun ReadingSection() {
    GuideSection("如何阅读与翻译") {
        GuideItem("翻页", "使用工具栏的“上一页 / 下一页”按钮或左右滑动翻页；“上一章 / 下一章”按章节跳转，进度条可拖到整本书的任意位置。")
        GuideItem(
            "点句或点段翻译",
            "开启轻点翻译后，可在设置中选择句子或段落，再轻点正文翻译；开启翻页保护时，滑动仍可翻页，点按不会同时触发翻页。"
        )
        GuideItem(
            "精确框选",
            "长按进入系统选区，拖动手柄调整范围后，从弹出菜单选择“翻译”“查词”或“划线/批注”，应用会严格采用你最后框选的文字。"
        )
        GuideItem(
            "只读打开",
            "打开、阅读、选区和翻译不会改写导入后的书籍文件，也不会刷新它的文件修改时间；阅读位置、书签和批注会另存到应用数据库。"
        )
        GuideItem(
            "跨页句段",
            "句子在页边界被截断时，点译文卡片的“扩展跨页句段”；应用会先在本机补齐可确认的句子，再只请求一次翻译。没有可靠上下文时不会臆造内容。"
        )
        GuideItem(
            "PDF 翻译",
            "开启轻点翻译后，直接轻点 PDF 句子即可翻译。双栏论文可在阅读设置开启“论文双栏模式”，自动点句、点段和文字识别会只读取点击的左栏或右栏；横跨整页的标题与图注仍按普通版式处理。"
        )
    }
}

@Composable
private fun FeatureSection() {
    GuideSection("功能介绍") {
        GuideItem("界面主题", "设置 → 界面主题可选择海蓝、森林、紫藤、琥珀或莓红色系；按钮、卡片和背景会一起变化，并自动适配系统浅色或深色外观。")
        GuideItem(
            "阅读设置与选区色",
            "每本书可独立保存字体、字号、背景、页边距、行距、段距、翻页模式和选区颜色。浅色、护眼、冷灰、深色会自动采用适配的选区色；自定义背景可另选选区色。设置 → 默认阅读排版可决定新书样式，并用开关持续同步或立即应用到现有全部书籍。"
        )
        GuideItem("目录、搜索、书签与批注", "阅读器的“导航”按钮集中提供章节目录、全文搜索、书签和划线批注；点正文上的高亮可直接编辑该条批注。")
        GuideItem("查词与生词本", "“学习”栏包含翻译、生词本和历史三部分；词典查询合并在翻译的“词语”模式里，译出一个词的同时会补充词典形、读音、词性和释义。阅读时长按一个词，从菜单选“查词”也可直接加入生词本。")
        GuideItem("日语学习增强", "日语词条会显示词典形、假名、词性、常见活用和例句；配置 AI 后可结合书中原句继续分析词语的语法角色与语境义。")
        GuideItem("AI 句子结构分析", "译文卡片的“AI 解析”可分析句子主干、成分、从句或修饰关系，以及英语时态、日语助词和活用等语法点；需要先配置 AI API 或代理。")
        GuideItem("学习内容导出", "在“学习”页选择 CSV、Markdown 或 Anki TSV，分享导出到其他应用。")
        GuideItem("书籍管理与统计", "书架显示累计阅读时长和进度；每本书的更多菜单可更换封面，修改书名、作者、语言、系列、文件夹和标签，顶部批量整理可一次处理多本书。")
        GuideItem("离线能力", "阅读、书架、已缓存的翻译与查词结果可离线使用；默认的本机翻译在语言模型下载完成后也可离线运行。")
    }
}

@Composable
private fun JapaneseSection() {
    GuideSection("日文版式") {
        GuideItem("原版", "保留出版物声明的横排、竖排和翻页方向；传统日文竖排书通常从右向左翻页。")
        GuideItem("横排", "把可重排日文 EPUB 统一改为横排，并让章节间和章节内都从左向右翻页。")
        GuideItem("竖排", "强制按竖排显示，适合排版信息缺失的日文书。")
        GuideFootnote("可在“设置 → 默认阅读排版”选择新书初始偏好，也可在某本书的阅读设置中单独覆盖。")
    }
}

@Composable
private fun TranslationSection() {
    GuideSection("翻译 API 如何使用") {
        GuideDisclosure("方案一：本机翻译（默认，无需 API Key）") {
            GuideBody("在“设置 → 翻译与 AI”选择“本机翻译”即可，这是默认选项。")
            GuideBody("首次使用某个语言组合时，系统需要下载语言模型，请保持网络连接。下载完成后即可离线翻译。")
            GuideBody("本机模型适合快速理解句意；需要更高质量的文学翻译时，可改用下面的 AI 方案。")
        }

        GuideDisclosure("方案二：直接填写 AI API Key") {
            GuideStepRow(1, "选择服务商", "在“设置 → 翻译与 AI”选择“AI API（直接）”，再点 GPT、Claude、Kimi、DeepSeek、Gemini 或兼容服务。")
            GuideStepRow(2, "粘贴 API Key", "直接粘贴对应平台创建的 Key；官方请求地址和推荐模型已经自动填写。")
            GuideStepRow(3, "测试连接", "点“测试连接”。测试只发送固定的短句，不会发送书籍内容。")
            GuideStepRow(4, "开始翻译", "测试成功后，轻点或框选正文即可。只有你主动选择的文字和必要上下文会发送给该服务商。")
            GuideFootnote("原文语言可自动识别，也可固定为中文、英语或日语；目标语言可在三者间自由选择。翻译与句子结构分析的提示词都可以自行修改。")
            GuideFootnote("每个平台的 Key 独立保存在本机的加密存储中，不会写入书籍、日志或安装包。同一句译文命中缓存时不会再次请求；短暂网络失败可自动重试一次，也可指定备用翻译服务。直接 API 会产生服务商费用，建议在平台设置用量上限并定期更换 Key。")
        }

        GuideDisclosure("方案三：自建 AI 翻译代理（进阶）") {
            GuideStepRow(1, "准备 HTTPS 代理", "代理由你自己控制，并在服务器端安全保存 OpenAI、Claude、Gemini 等供应商密钥。")
            GuideStepRow(2, "填写地址", "在“设置 → 翻译与 AI”选择“AI 代理”，填写完整的 https:// 地址。")
            GuideStepRow(3, "填写模型", "模型名称是可选项，例如由你的代理映射为实际供应商模型。")
            GuideStepRow(4, "填写访问凭据", "如果代理需要鉴权，可填写代理访问令牌；令牌只保存在本机的加密存储中。")
            GuideStepRow(5, "开始翻译", "选择或点击正文后，应用只发送本次选中的文字、必要上下文和语言方向。")
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
            GuideBody("代理成功响应（二选一字段）")
            GuideCodeBlock("{ \"translatedText\": \"译文\" }")
            GuideFootnote("如填写了代理访问凭据，客户端会使用 Authorization: Bearer <token>。代理应在 30 秒内返回 2xx 与 JSON。401/403 会显示鉴权失败，其他异常可以在翻译卡片中重试。")
        }

        GuideFootnote("直接 API 适合个人自用；准备公开分发时，仍建议用自建代理，避免让共享密钥存在客户端。")
    }
}

@Composable
private fun BackupSection() {
    GuideSection("备份与恢复") {
        GuideItem(
            "选择备份文件夹",
            "设置 → 备份与恢复 → 选择备份文件夹，可指定本机存储、SD 卡或已安装的云盘目录。自动备份与“立即备份到当前文件夹”都会写入这里，下方列表会直接显示其中可恢复的备份。"
        )
        GuideItem(
            "自动备份",
            "开启后按所选间隔在打开 App 时检查是否到期；可单独选择自动备份包含哪几类内容，并按保存天数、最多份数和总容量从最旧的一份开始清理。"
        )
        GuideItem(
            "手动备份",
            "选择“书籍与封面”“进度、书签与批注”“生词与翻译收藏”“阅读与翻译偏好”中需要的范围后点“立即备份到当前文件夹”。包含书籍文件的备份明显更大，但重装后无需重新找书。"
        )
        GuideItem(
            "恢复",
            "重装后重新选择原来的文件夹，备份会出现在列表中，点右侧“恢复”即可；也可用“选择备份文件”从其他位置恢复。恢复只做合并，已存在的书籍与记录不会产生重复。"
        )
        GuideItem(
            "不进入备份的内容",
            "文件夹授权只保存在当前安装中，重装后需要再次选择同一文件夹。API Key、代理访问凭据和可再生的翻译缓存都不写入备份文件。"
        )
    }
}

@Composable
private fun FormatSection() {
    GuideSection("格式说明") {
        GuideItem("EPUB", "完整阅读与翻译")
        GuideItem("PDF", "点句翻译＋本机文字识别")
        GuideItem("DOCX", "转换正文后阅读")
        GuideItem("TXT", "自动识别常用编码")
        GuideFootnote("DOCX 会提取正文、段落和基础元数据后转换为本地可重排电子书；复杂分页、批注、修订、宏和嵌入对象不会原样保留。旧式 .doc 请先另存为 .docx。扫描 PDF 的文字识别在本机按需执行；倾斜、低清、手写体或复杂多栏版面可能需要重新点击或长按框选。")
    }
}

@Composable
private fun GuideSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun GuideStepRow(number: Int, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GuideItem(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GuideBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun GuideFootnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun GuideDisclosure(title: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = (if (expanded) "▾  " else "▸  ") + title,
                modifier = Modifier.fillMaxWidth(),
                fontWeight = FontWeight.SemiBold
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) { content() }
        }
    }
}

@Composable
private fun GuideCodeBlock(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
