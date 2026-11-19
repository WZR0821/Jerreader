import SwiftUI

struct AppGuideView: View {
    var body: some View {
        List {
            quickStartSection
            importSection
            readingSection
            featureSection
            japaneseSection
            translationSection
            planSection
            backupSection
            formatLimitsSection
        }
        .scrollContentBackground(.hidden)
        .background(JerreaderCanvasBackground())
        .navigationTitle("操作指南")
        .navigationBarTitleDisplayMode(.inline)
        .tint(JerreaderTheme.accent)
    }

    private var quickStartSection: some View {
        Section {
            GuideStepRow(number: 1, title: "导入书籍", detail: "在“书架”右上角点“＋”，先选 EPUB、PDF、DOCX 或 TXT 格式，再选文件；导入完成后会直接打开。")
            GuideStepRow(number: 2, title: "打开阅读", detail: "轻点封面进入阅读器；应用会自动保存每本书的阅读位置和排版偏好。")
            GuideStepRow(number: 3, title: "选择翻译", detail: "在阅读设置中选择“点句”或“点段”，再轻点正文即可一次翻译一个句子或段落；长按会智能补全，拖动手柄后则严格按最终选区翻译。")
            GuideStepRow(number: 4, title: "保存学习内容", detail: "长按选中完整词语并完成翻译后，可在译文卡片“更多”中直接加入生词本；译文收藏与生词统一放在“学习”栏。")
        } header: {
            Text("快速开始").settingsHeader()
        }
    }

    private var importSection: some View {
        Section {
            GuideItem(
                title: "从“文件”导入",
                detail: "书架 → 右上角“＋” → 先选格式 → 选择文件。选 PDF 时系统文件列表里只有 PDF 能选，其他格式会变灰；导入后直接进入阅读，重复文件会打开书架中的现有副本。支持“在我的 iPhone”、iCloud Drive、下载目录和外接存储。",
                systemImage: "folder.fill", tint: JerreaderTheme.accent
            )
            GuideItem(
                title: "从其他 App 导入",
                detail: "在“文件”、微信、Safari、邮件或 AirDrop 中打开分享菜单，选择“用Jerreader打开”；Jerreader会导入文档并直接进入阅读页。",
                systemImage: "square.and.arrow.down.fill", tint: JerreaderTheme.accent
            )
            GuideItem(
                title: "从电脑传输",
                detail: "可用 Finder 或 Windows Apple Devices 的文件共享把文件放入Jerreader，再从系统“文件”中选择。",
                systemImage: "laptopcomputer.and.iphone", tint: JerreaderTheme.accent
            )
            JerreaderLabel(title: "导入时只复制到 App 的本地沙盒，不会自动上传书籍。", icon: "lock")
                .font(.caption)
                .foregroundStyle(.secondary)
        } header: {
            Text("如何导入").settingsHeader()
        }
    }

    private var readingSection: some View {
        Section {
            GuideItem(
                title: "翻页",
                detail: "轻点页面左侧或右侧快速翻页，也可使用底部的大号翻页按钮。关闭点句翻译后，轻点正文中央可显示或隐藏阅读控制。",
                systemImage: "rectangle.portrait.on.rectangle.portrait", tint: SettingsTint.reading
            )
            GuideItem(
                title: "点句或点段翻译",
                detail: "工具栏中的“字”图标为开启状态时，可在阅读设置的“翻译范围”选择句子或段落，再轻点正文翻译；开启翻页保护时，滑动仍可翻页，点按不会同时触发翻页。",
                systemImage: "character.bubble.fill", tint: SettingsTint.translation
            )
            GuideItem(
                title: "精确框选",
                detail: "长按后先智能补全单词或句子；日文的「」与『』会连同“と／って”等引用表达正确判断句界。一旦拖动选区手柄，Jerreader会严格采用你最后框选的文字。",
                systemImage: "selection.pin.in.out", tint: SettingsTint.reading
            )
            GuideItem(
                title: "只读打开",
                detail: "打开、阅读、选区和翻译不会改写导入后的书籍文件，也不会刷新它的文件修改时间；阅读位置、最近阅读时间、书签和批注会另存到 App 数据库。",
                systemImage: "lock.doc.fill", tint: SettingsTint.reading
            )
            GuideItem(
                title: "跨页句段",
                detail: "句子在页边界被截断时，打开译文卡片“更多 → 扩展跨页句段”；Jerreader会先在本机补齐可确认的句子，再只请求一次翻译。",
                systemImage: "rectangle.split.2x1", tint: SettingsTint.reading
            )
            GuideItem(
                title: "PDF 翻译",
                detail: "打开点句翻译后，直接轻点 PDF 句子即可翻译。双栏论文可在阅读设置开启“论文双栏模式”，自动点句、点段和 OCR 会只读取点击的左栏或右栏；横跨整页的标题与图注仍按普通版式处理。长按手柄框选始终使用 PDFKit 原生规则，不受该开关影响。",
                systemImage: "doc.richtext", tint: SettingsTint.reading
            )
        } header: {
            Text("如何阅读与翻译").settingsHeader()
        }
    }

    private var featureSection: some View {
        Section {
            GuideItem(title: "界面主题", detail: "设置 → 界面主题有八套色系（海蓝、森林、紫藤、琥珀、莓红、松石、赤陶、石墨），轻点即换，页面顶部的预览会马上跟着变。外观模式可以跟随系统，也可以固定为浅色或深色；背景光晕可以关掉，换成纯净的纸色。阅读页的纸张仍在默认阅读排版里单独设置。", systemImage: "paintpalette.fill", tint: JerreaderTheme.accent)
            GuideItem(title: "阅读设置与选区色", detail: "每本书可独立保存字体、字号、背景、左右边距、行距、段距、翻页模式和选区颜色。浅色、护眼、冷灰、深色会自动采用适配的选区色；自定义背景可另选选区色。设置 → 默认阅读排版可决定新书样式，并用开关持续同步或立即应用到现有全部书籍。", systemImage: "textformat.size", tint: SettingsTint.reading)
            GuideItem(title: "目录、进度与书签", detail: "阅读器顶部的目录按钮集中提供章节目录、全文搜索、书签和批注；底部进度条可直接拖到整本书的任意位置。", systemImage: "list.bullet.rectangle", tint: SettingsTint.reading)
            GuideItem(title: "悬浮译文", detail: "译文框会与完整选区留出安全距离，框内则按实际译文收紧，不会为了段落模式强行留出大块空白；长译文达到高度上限后可在框内上下滑动。原版竖排时优先放在原文左侧或右侧；也可拖动顶部手柄手动调整。", systemImage: "rectangle.inset.filled.and.person.filled", tint: SettingsTint.translation)
            GuideItem(title: "词典与生词本", detail: "“学习 → 翻译”的词语模式已合并词典功能。翻译日语或英语词语后，会继续显示词典形、读音、词性、释义和例句，自动记入历史，并可直接收藏到生词本。阅读正文中长按完整词语，翻译后也可直接加入。", systemImage: "character.book.closed.fill", tint: JerreaderTheme.jade)
            GuideItem(title: "独立翻译", detail: "“学习 → 翻译”提供词语和句子两种模式。原文可自动识别或手动选择中文、英语、日语，译文也可在三种语言间选择；页面顶部可单独选择 Apple 系统翻译、AI 大模型或 AI 代理，不会改动阅读器的翻译设置。词语模式会补充词典详情；日语原文或译文可开启“日语注音”，读音在本机生成，不改写原书。", systemImage: "character.bubble.fill", tint: SettingsTint.translation)
            GuideItem(title: "日语学习增强", detail: "日语词条会显示词典形、假名、词性、常见活用和例句；配置 AI 后可结合书中原句继续分析词语的语法角色与语境义。", systemImage: "text.book.closed.fill", tint: JerreaderTheme.jade)
            GuideItem(title: "AI 句子结构分析", detail: "译文卡片“更多”可分析句子主干、成分、从句或修饰关系，以及英语时态、日语助词和活用等语法点；需要先设置 AI 大模型或 AI 代理。", systemImage: "text.line.magnify", tint: SettingsTint.models)
            GuideItem(title: "学习内容导出", detail: "在“学习 → 生词本”右上角选择 CSV、Markdown 或 Anki TSV，导出到“文件”或其他 App。", systemImage: "square.and.arrow.up", tint: JerreaderTheme.jade)
            GuideItem(title: "书籍管理与统计", detail: "书架上的“继续阅读”卡片一点即可回到上次打开的书；“阅读统计”汇总累计阅读时长、已开始的书和平均进度。每本书的更多菜单可更换封面，修改书名、作者、语言、系列、文件夹和标签。顶部批量整理可一次处理多本书，文件夹卡片可快速筛选。", systemImage: "chart.bar.doc.horizontal", tint: SettingsTint.backup)
            GuideItem(title: "离线能力", detail: "阅读、书架与已缓存的查询结果可离线使用；新翻译是否离线取决于所选服务和语言包。", systemImage: "wifi.slash", tint: SettingsTint.guide)
        } header: {
            Text("功能介绍").settingsHeader()
        }
    }

    private var japaneseSection: some View {
        Section {
            GuideItem(
                title: "原版",
                detail: "保留出版物声明的横排、竖排和翻页方向；传统日文竖排书通常从右向左翻页。",
                systemImage: "character.book.closed", tint: SettingsTint.reading
            )
            GuideItem(
                title: "横排",
                detail: "把可重排日文 EPUB 统一改为横排，并让章节间和章节内都从左向右翻页。",
                systemImage: "text.alignleft", tint: SettingsTint.reading
            )
            Text("可在“设置 → 默认阅读排版”选择新书初始偏好，也可在某本书的阅读设置中单独覆盖。即使 EPUB 没写语言标签，Jerreader也会检查书内 CSS 来恢复原版竖排。")
                .font(.caption)
                .foregroundStyle(.secondary)
        } header: {
            Text("日文版式").settingsHeader()
        }
    }

    private var translationSection: some View {
        Section {
            DisclosureGroup("方案一：Apple 系统翻译") {
                VStack(alignment: .leading, spacing: 10) {
                    Text("无需填写 API Key。在“设置 → 翻译与 AI”选择“Apple 系统翻译”即可。")
                    Text("首次使用某个语言组合时，系统可能需要数分钟下载语言包。请允许系统提示，并保持 Jerreader 在前台和网络连接。")
                    Text("若下载长时间没有进展，可先打开 Apple“翻译”App，下载原文和目标语言，然后回到 Jerreader 重试。下载完成后通常可离线翻译。")
                }
                .guideBodyStyle()
            }

            DisclosureGroup("方案二：AI 大模型（填写 API Key）") {
                VStack(alignment: .leading, spacing: 12) {
                    GuideStepRow(number: 1, title: "添加供应商", detail: "打开“设置 → AI 大模型”，点“添加供应商”。Claude、ChatGPT、DeepSeek 排在最前，通义千问、豆包、Kimi、智谱、MiniMax、Gemini 依次在后；列表里没有的服务用“自定义”添加。想用 ChatGPT 或 Claude 的订阅额度，见下方“AI 套餐”。")
                    GuideStepRow(number: 2, title: "粘贴 API Key", detail: "粘贴在该平台创建的 Key，勾选要用的模型。官方请求地址和常用模型已经填好，也可以“获取可用模型”从账户返回的列表里选。")
                    GuideStepRow(number: 3, title: "测试并添加", detail: "点“测试连接”，通过后点“添加”。测试只发送固定的短句，不会发送书籍内容。")
                    GuideStepRow(number: 4, title: "开始翻译", detail: "在“设置 → 翻译与 AI”把翻译服务选为“AI 大模型”，之后轻点或框选正文即可。以后用“换模型”在已添加的供应商之间切换。只有你主动选择的文字和必要上下文会发送给该服务商。")

                    Text("原文语言可自动识别，也可固定为中文、英语或日语；目标语言可在三者间自由选择。展开“提示词与 AI 行为”可以分别修改翻译和句子结构分析提示词。")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Text("每个平台的 Key 独立保存在本机钥匙串，不会写入书籍、日志或 IPA。同一句译文命中缓存时不会再次请求；短暂网络失败可自动重试一次，也可在设置中指定备用翻译服务。每个模型可单独设置接口格式和思考强度。AI 大模型会产生服务商费用，建议在平台设置用量上限并定期更换 Key。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 8)
            }

            DisclosureGroup("方案三：自建 AI 翻译代理（进阶）") {
                VStack(alignment: .leading, spacing: 12) {
                    GuideStepRow(number: 1, title: "准备 HTTPS 代理", detail: "代理由你自己控制，并在服务器端安全保存 OpenAI、Claude、Gemini、DeepL 等供应商密钥。")
                    GuideStepRow(number: 2, title: "填写地址", detail: "在“设置 → 翻译与 AI”选择“AI 代理”，填写完整的 https:// 地址。")
                    GuideStepRow(number: 3, title: "填写模型", detail: "模型名称是可选项，例如由你的代理映射为实际供应商模型。")
                    GuideStepRow(number: 4, title: "填写访问凭据", detail: "如果代理需要鉴权，可填写代理访问令牌；令牌只保存在本机钥匙串。")
                    GuideStepRow(number: 5, title: "开始翻译", detail: "选择或点击正文后，Jerreader只发送本次选中的文字、必要上下文和语言方向。")

                    Text("代理请求 JSON")
                        .font(.subheadline.weight(.semibold))
                    GuideCodeBlock(text: """
                    {
                      "text": "需要翻译的文字",
                      "context": "可选的上下文",
                      "sourceLanguage": "ja",
                      "targetLanguage": "zh-Hans",
                      "model": "可选模型名",
                      "prompt": "可选的自定义提示词"
                    }
                    """)

                    Text("代理成功响应（二选一字段）")
                        .font(.subheadline.weight(.semibold))
                    GuideCodeBlock(text: """
                    { "translatedText": "译文" }
                    """)

                    Text("如填写了代理访问凭据，客户端会使用 Authorization: Bearer <token>。代理应在 30 秒内返回 2xx 与 JSON。401/403 会显示鉴权失败，其他异常可以在翻译卡片中重试。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 8)
            }

            Label {
                Text("AI 大模型适合个人自用；准备公开分发 App 时，仍建议用自建代理，避免让共享密钥存在客户端。")
            } icon: {
                JerreaderSymbol(name: "exclamationmark.triangle", size: 16)
            }
            .font(.caption)
            .foregroundStyle(.orange)
        } header: {
            Text("翻译 API 如何使用").settingsHeader()
        }
    }

    /// 2.0: paying with a subscription instead of an API key, in the app's own words.
    private var planSection: some View {
        Section {
            GuideItem(
                title: "ChatGPT 套餐（实验）",
                detail: "在“设置 → AI 大模型 → 添加供应商 → 套餐”点“Continue with ChatGPT”，在 OpenAI 的页面登录 ChatGPT 账号，不用 API Key。翻译和语法解释用你的 Plus、Pro 等套餐额度，不另外收费；Plus 的 5 小时额度和 ChatGPT、Codex 等应用共用。额度用完时翻译会停下并提示你，不会改成按量付费。手机上的登录方式 OpenAI 还没有写进文档，所以标为实验；登录不上时请改用 API Key。",
                systemImage: "sparkles", tint: SettingsTint.models
            )
            GuideItem(
                title: "账号与模型",
                detail: "登录后，套餐页的“账号与模型”显示账号和套餐，可重新登录、退出登录或“刷新模型”；模型列表来自 OpenAI，只列这个账号能用的模型。“管理用量”打开 ChatGPT 网站的用量设置。登录信息只存在本机钥匙串，不进备份。",
                systemImage: "lock", tint: SettingsTint.guide
            )
            GuideItem(
                title: "Claude 套餐额度",
                detail: "Claude 的 Max、Ultra 订阅每月附带 API 额度：Max 每月 $100、Ultra 每月 $200，每个账单周期刷新，不累积。先在 claude.ai 的“设置 › 账单 › API 额度”领取并关联一个 Console 组织，再到 Claude 控制台新建 API Key，回到“套餐”页点“添加 Claude 并填入 API Key”。额度挂在 Console 组织上，用法和普通 API Key 一样；费用先从额度里扣，扣完后是否继续按量付费，看 Console 里的设置。这份额度只用于 API，不能给 Claude Code 用。",
                systemImage: "star", tint: JerreaderTheme.vermilion
            )
            GuideItem(
                title: "编程套餐",
                detail: "Coding Plan、Token Plan 这类编程套餐的条款只允许在编程工具里使用，阅读翻译不在其列，所以这里不提供。",
                systemImage: "hand.raised", tint: SettingsTint.guide
            )
        } header: {
            Text("AI 套餐").settingsHeader()
        }
    }

    private var formatLimitsSection: some View {
        Section {
            LabeledContent("EPUB") { Text("完整阅读与翻译") }
            LabeledContent("PDF") { Text("点句翻译＋本机 OCR") }
            LabeledContent("DOCX") { Text("转换正文后阅读") }
            LabeledContent("TXT") { Text("自动识别常用编码") }

            Text("DOCX 会提取正文、段落和基础元数据后转换为本地可重排电子书；复杂分页、批注、修订、宏和嵌入对象不会原样保留。旧式 .doc 请先在 Word 中另存为 .docx。扫描 PDF 的 OCR 在本机按需执行；倾斜、低清、手写体或复杂多栏版面可能需要重新点击或长按框选。")
                .font(.caption)
                .foregroundStyle(.secondary)
        } header: {
            Text("格式说明").settingsHeader()
        }
    }

    private var backupSection: some View {
        Section {
            GuideItem(
                title: "选择备份文件夹",
                detail: "设置 → 备份与恢复 → 选择备份文件夹，可指定 iCloud Drive、“我的 iPhone”或其他文件提供商中的文件夹。自动备份与“立即备份”都会写入这里，下方列表会直接显示其中可恢复的备份。",
                systemImage: "folder.badge.plus", tint: SettingsTint.backup
            )
            GuideItem(
                title: "自动备份",
                detail: "可分别选择间隔、保存天数、最多份数、总容量以及书籍、阅读、学习、设置四类范围。App 会在启动、回到前台或进入后台时检查是否到期，并写入当前选定的文件夹。",
                systemImage: "externaldrive.badge.timemachine", tint: SettingsTint.backup
            )
            GuideItem(
                title: "手动备份",
                detail: "点“立即备份”直接保存到所选文件夹；首次使用会先让你选择目录。“更多备份选项”可调整范围、间隔和保留策略，也可导出单独副本。",
                systemImage: "square.and.arrow.up", tint: SettingsTint.backup
            )
            GuideItem(
                title: "恢复与合并",
                detail: "重装或重签后点“从备份文件夹恢复”，选择原来的目录，确认恢复最新有效备份。恢复成功后会继续使用这个目录；也可展开“更多备份选项”选择单个备份文件。恢复只做合并，较新的本机进度会保留。",
                systemImage: "arrow.counterclockwise", tint: SettingsTint.backup
            )
            Text("文件夹授权只保存在当前安装中，不进入备份；重装后需要再次选择同一文件夹。API Key 和代理访问凭据也不进入备份。自动备份受 iOS 生命周期限制，App 完全未运行时不能保证准点执行。")
                .font(.caption)
                .foregroundStyle(.secondary)
        } header: {
            Text("备份与恢复").settingsHeader()
        }
    }
}

private struct GuideStepRow: View {
    let number: Int
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            // The digit takes the colour drawn on the accent, which is dark on
            // the light dark-mode accents.
            Text("\(number)")
                .font(.system(.caption, design: .rounded).weight(.bold).monospacedDigit())
                .foregroundStyle(JerreaderTheme.onPrimaryAction)
                .frame(width: 24, height: 24)
                .background(JerreaderTheme.accent, in: Circle())

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 2)
    }
}

/// A guide entry: a 30 pt tile in the colour of the part of the app it
/// describes, the title, and the detail under it.
private struct GuideItem: View {
    let title: String
    let detail: String
    let systemImage: String
    var tint: Color = JerreaderTheme.accent

    private var sharedIcon: String {
        switch systemImage {
        case "folder.fill", "folder.badge.plus": "FOLDER"
        case "square.and.arrow.down.fill": "DOWNLOAD"
        case "laptopcomputer.and.iphone", "wifi.slash": "EXTERNAL_DRIVE"
        case "rectangle.portrait.on.rectangle.portrait", "rectangle.split.2x1": "GRID"
        case "character.bubble.fill", "rectangle.inset.filled.and.person.filled": "SPEECH_BUBBLE_FILLED"
        case "selection.pin.in.out": "HAND"
        case "lock.doc.fill", "lock.shield.fill": "LOCK"
        case "doc.richtext", "doc.text", "doc.text.fill": "NOTE"
        case "paintpalette.fill": "PALETTE"
        case "textformat.size", "text.alignleft": "TEXT_FORMAT"
        case "list.bullet.rectangle": "TABLE_OF_CONTENTS"
        case "character.book.closed", "character.book.closed.fill": "DICTIONARY"
        case "text.book.closed.fill": "BOOKS"
        case "text.line.magnify": "SEARCH"
        case "square.and.arrow.up": "SHARE"
        case "chart.bar.doc.horizontal": "CHART"
        case "clock.arrow.circlepath", "externaldrive.badge.timemachine": "CLOCK"
        case "arrow.counterclockwise": "RESTORE"
        case "sparkles": "SPARKLES"
        case "lock": "LOCK"
        case "star": "STAR_OUTLINE"
        case "hand.raised": "HAND"
        default: "INFO"
        }
    }

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            JerreaderIconTile(systemImage: sharedIcon, tint: tint, size: 30)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

private struct GuideCodeBlock: View {
    let text: String

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            Text(verbatim: text)
                .font(.caption.monospaced())
                .textSelection(.enabled)
                .padding(12)
        }
        .background(
            JerreaderTheme.mutedSurface,
            in: RoundedRectangle(cornerRadius: JerreaderRadius.small, style: .continuous)
        )
    }
}

private extension View {
    func guideBodyStyle() -> some View {
        font(.caption)
            .foregroundStyle(.secondary)
            .padding(.vertical, 8)
    }
}
