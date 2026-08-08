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
        Section("快速开始") {
            GuideStepRow(number: 1, title: "导入书籍", detail: "在“书架”右上角点“＋”，先选 EPUB、PDF、DOCX 或 TXT 格式，再选文件；导入完成后会直接打开。")
            GuideStepRow(number: 2, title: "打开阅读", detail: "轻点封面进入阅读器；应用会自动保存每本书的阅读位置和排版偏好。")
            GuideStepRow(number: 3, title: "选择翻译", detail: "在阅读设置中选择“点句”或“点段”，再轻点正文即可一次翻译一个句子或段落；长按会智能补全，拖动手柄后则严格按最终选区翻译。")
            GuideStepRow(number: 4, title: "保存学习内容", detail: "长按选中完整词语并完成翻译后，可在译文卡片“更多”中直接加入生词本；译文收藏与生词统一放在“学习”栏。")
        }
    }

    private var importSection: some View {
        Section("如何导入") {
            GuideItem(
                title: "从“文件”导入",
                detail: "书架 → 右上角“＋” → 先选格式 → 选择文件。选 PDF 时系统文件列表只显示 PDF；导入后直接进入阅读，重复文件会打开书架中的现有副本。支持“在我的 iPhone”、iCloud Drive、下载目录和外接存储。",
                systemImage: "folder.fill"
            )
            GuideItem(
                title: "从其他 App 导入",
                detail: "在“文件”、微信、Safari、邮件或 AirDrop 中打开分享菜单，选择“用Jerreader打开”；Jerreader会导入文档并直接进入阅读页。",
                systemImage: "square.and.arrow.down.fill"
            )
            GuideItem(
                title: "从电脑传输",
                detail: "可用 Finder 或 Windows Apple Devices 的文件共享把文件放入Jerreader，再从系统“文件”中选择。",
                systemImage: "laptopcomputer.and.iphone"
            )
            Label("导入时只复制到 App 的本地沙盒，不会自动上传书籍。", systemImage: "lock.shield.fill")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var readingSection: some View {
        Section("如何阅读与翻译") {
            GuideItem(
                title: "翻页",
                detail: "轻点页面左侧或右侧快速翻页，也可使用底部的大号翻页按钮。关闭点句翻译后，轻点正文中央可显示或隐藏阅读控制。",
                systemImage: "rectangle.portrait.on.rectangle.portrait"
            )
            GuideItem(
                title: "点句或点段翻译",
                detail: "工具栏中的“字”图标为开启状态时，可在阅读设置的“翻译范围”选择句子或段落，再轻点正文翻译；开启翻页保护时，滑动仍可翻页，点按不会同时触发翻页。",
                systemImage: "character.bubble.fill"
            )
            GuideItem(
                title: "精确框选",
                detail: "长按后先智能补全单词或句子；日文的「」与『』会连同“と／って”等引用表达正确判断句界。一旦拖动选区手柄，Jerreader会严格采用你最后框选的文字。",
                systemImage: "selection.pin.in.out"
            )
            GuideItem(
                title: "只读打开",
                detail: "打开、阅读、选区和翻译不会改写导入后的书籍文件，也不会刷新它的文件修改时间；阅读位置、最近阅读时间、书签和批注会另存到 App 数据库。",
                systemImage: "lock.doc.fill"
            )
            GuideItem(
                title: "跨页句段",
                detail: "句子在页边界被截断时，打开译文卡片“更多 → 扩展跨页句段”；Jerreader会先在本机补齐可确认的句子，再只请求一次翻译。",
                systemImage: "rectangle.split.2x1"
            )
            GuideItem(
                title: "PDF 翻译",
                detail: "打开点句翻译后，直接轻点 PDF 句子即可翻译。双栏论文可在阅读设置开启“论文双栏模式”，自动点句、点段和 OCR 会只读取点击的左栏或右栏；横跨整页的标题与图注仍按普通版式处理。长按手柄框选始终使用 PDFKit 原生规则，不受该开关影响。",
                systemImage: "doc.richtext"
            )
        }
    }

    private var featureSection: some View {
        Section("功能介绍") {
            GuideItem(title: "界面主题", detail: "设置 → 界面主题可选择海洋、森林、紫藤、琥珀或莓果色系；按钮、卡片和背景会一起变化，并自动适配系统浅色或深色外观。", systemImage: "paintpalette.fill")
            GuideItem(title: "阅读设置与选区色", detail: "每本书可独立保存字体、字号、背景、页边距、行距、段距、翻页模式和选区颜色。浅色、护眼、冷灰、深色会自动采用适配的选区色；自定义背景可另选选区色。设置 → 默认阅读排版可决定新书样式，并用开关持续同步或立即应用到现有全部书籍。", systemImage: "textformat.size")
            GuideItem(title: "目录、进度与书签", detail: "阅读器顶部的目录按钮集中提供章节目录、全文搜索、书签和批注；底部进度条可直接拖到整本书的任意位置。", systemImage: "list.bullet.rectangle")
            GuideItem(title: "悬浮译文", detail: "译文框会与完整选区留出安全距离，框内则按实际译文收紧，不会为了段落模式强行留出大块空白；长译文达到高度上限后可在框内上下滑动。原版竖排时优先放在原文左侧或右侧；也可拖动顶部手柄手动调整。", systemImage: "rectangle.inset.filled.and.person.filled")
            GuideItem(title: "词典与生词本", detail: "“学习 → 翻译”的词语模式已合并词典功能。翻译日语或英语词语后，会继续显示词典形、读音、词性、释义和例句，自动记入历史，并可直接收藏到生词本。阅读正文中长按完整词语，翻译后也可直接加入。", systemImage: "character.book.closed.fill")
            GuideItem(title: "独立翻译", detail: "“学习 → 翻译”提供词语和句子两种模式。原文可自动识别或手动选择中文、英语、日语，译文也可在三种语言间选择；页面顶部可单独选择 Apple 系统翻译、AI API 或 AI 代理，不会改动阅读器的翻译设置。词语模式会补充词典详情；句子译成日文时，汉字上方会显示小字假名注音。", systemImage: "character.bubble.fill")
            GuideItem(title: "日语学习增强", detail: "日语词条会显示词典形、假名、词性、常见活用和例句；配置 AI 后可结合书中原句继续分析词语的语法角色与语境义。", systemImage: "text.book.closed.fill")
            GuideItem(title: "AI 句子结构分析", detail: "译文卡片“更多”可分析句子主干、成分、从句或修饰关系，以及英语时态、日语助词和活用等语法点；需要先配置 AI API 或代理。", systemImage: "text.line.magnify")
            GuideItem(title: "学习内容导出", detail: "在“学习 → 生词本与收藏”右上角选择 CSV、Markdown 或 Anki TSV，导出到“文件”或其他 App。", systemImage: "square.and.arrow.up")
            GuideItem(title: "书籍管理与统计", detail: "书架显示累计阅读时长和进度；每本书的更多菜单可更换封面，修改书名、作者、语言、系列、文件夹和标签。顶部批量整理可一次处理多本书，文件夹卡片可快速筛选。", systemImage: "chart.bar.doc.horizontal")
            GuideItem(title: "离线能力", detail: "阅读、书架与已缓存的查询结果可离线使用；新翻译是否离线取决于所选服务和语言包。", systemImage: "wifi.slash")
        }
    }

    private var japaneseSection: some View {
        Section("日文版式") {
            GuideItem(
                title: "原版",
                detail: "保留出版物声明的横排、竖排和翻页方向；传统日文竖排书通常从右向左翻页。",
                systemImage: "character.book.closed"
            )
            GuideItem(
                title: "横排",
                detail: "把可重排日文 EPUB 统一改为横排，并让章节间和章节内都从左向右翻页。",
                systemImage: "text.alignleft"
            )
            Text("可在“设置 → 默认阅读排版”选择新书初始偏好，也可在某本书的阅读设置中单独覆盖。即使 EPUB 没写语言标签，Jerreader也会检查书内 CSS 来恢复原版竖排。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var translationSection: some View {
        Section("翻译 API 如何使用") {
            DisclosureGroup("方案一：Apple 系统翻译") {
                VStack(alignment: .leading, spacing: 10) {
                    Text("无需填写 API Key。在“设置 → 翻译与 AI”选择“Apple 系统翻译”即可。")
                    Text("首次使用某个语言组合时，系统可能需要数分钟下载语言包。请允许系统提示，并保持 Jerreader 在前台和网络连接。")
                    Text("若下载长时间没有进展，可先打开 Apple“翻译”App，下载原文和目标语言，然后回到 Jerreader 重试。下载完成后通常可离线翻译。")
                }
                .guideBodyStyle()
            }

            DisclosureGroup("方案二：直接填写 AI API Key") {
                VStack(alignment: .leading, spacing: 12) {
                    GuideStepRow(number: 1, title: "选择服务商", detail: "在“设置 → 翻译与 AI”选择“AI API（直接）”，再点 GPT、Claude、Kimi、DeepSeek、Gemini 或兼容服务卡片。")
                    GuideStepRow(number: 2, title: "粘贴 API Key", detail: "直接粘贴对应平台创建的 Key；GPT、Claude、Kimi 等官方请求地址和推荐模型已经自动填写。")
                    GuideStepRow(number: 3, title: "测试连接", detail: "点“测试连接”。测试只发送固定的短句，不会发送书籍内容。")
                    GuideStepRow(number: 4, title: "开始翻译", detail: "测试成功后，轻点或框选正文即可。只有你主动选择的文字和必要上下文会发送给该服务商。")

                    Text("原文语言可自动识别，也可固定为中文、英语或日语；目标语言可在三者间自由选择。展开“提示词与 AI 行为”可以分别修改翻译和句子结构分析提示词。")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Text("每个平台的 Key 独立保存在本机钥匙串，不会写入书籍、日志或 IPA。同一句译文命中缓存时不会再次请求；短暂网络失败可自动重试一次，也可在设置中指定备用翻译服务。模型名可以自行修改。直接 API 会产生服务商费用，建议在平台设置用量上限并定期更换 Key。")
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

            Label("直接 API 适合个人自用；准备公开分发 App 时，仍建议用自建代理，避免让共享密钥存在客户端。", systemImage: "exclamationmark.shield.fill")
                .font(.caption)
                .foregroundStyle(.orange)
        }
    }

    private var formatLimitsSection: some View {
        Section("格式说明") {
            LabeledContent("EPUB") { Text("完整阅读与翻译") }
            LabeledContent("PDF") { Text("点句翻译＋本机 OCR") }
            LabeledContent("DOCX") { Text("转换正文后阅读") }
            LabeledContent("TXT") { Text("自动识别常用编码") }

            Text("DOCX 会提取正文、段落和基础元数据后转换为本地可重排电子书；复杂分页、批注、修订、宏和嵌入对象不会原样保留。旧式 .doc 请先在 Word 中另存为 .docx。扫描 PDF 的 OCR 在本机按需执行；倾斜、低清、手写体或复杂多栏版面可能需要重新点击或长按框选。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var backupSection: some View {
        Section("备份与重新签名") {
            GuideItem(
                title: "选择备份文件夹",
                detail: "设置 → 备份中心 → 选择备份文件夹，可指定 iCloud Drive、“我的 iPhone”或其他文件提供商中的文件夹。自动备份与手动“保存到当前文件夹”都会写入这里，下方列表会直接显示其中可恢复的备份。",
                systemImage: "folder.badge.plus"
            )
            GuideItem(
                title: "自动备份",
                detail: "可分别选择间隔、保存天数、最多份数、总容量以及书籍、阅读、学习、设置四类范围。App 会在启动、回到前台或进入后台时检查是否到期，并写入当前选定的文件夹。",
                systemImage: "externaldrive.badge.timemachine"
            )
            GuideItem(
                title: "手动备份",
                detail: "选择范围后点“保存到当前文件夹”可直接生成备份；“导出到其他位置”可临时另存一份。若当前使用 App 本地文件夹，卸载前应把备份导出到外部。",
                systemImage: "square.and.arrow.up"
            )
            GuideItem(
                title: "恢复与合并",
                detail: "安装并签名新版本后，先重新选择原来的 iCloud Drive 等备份文件夹。备份会出现在下方列表，点每个文件右侧的“恢复”即可直接恢复；“从备份文件恢复”每次都会新建文件选择会话，也可浏览其他位置。为兼容 iCloud 暂时显示为通用文件类型的备份，文件可点选后再由Jerreader严格校验；不是有效备份时不会写入任何数据。",
                systemImage: "arrow.counterclockwise"
            )
            Text("文件夹授权只保存在当前安装中，不进入备份；重装后需要再次选择同一文件夹。API Key 和代理访问凭据也不进入备份。自动备份受 iOS 生命周期限制，App 完全未运行时不能保证准点执行。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

private struct GuideStepRow: View {
    let number: Int
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(number)")
                .font(.caption.weight(.bold))
                .foregroundStyle(.white)
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

private struct GuideItem: View {
    let title: String
    let detail: String
    let systemImage: String

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        } icon: {
            Image(systemName: systemImage)
                .foregroundStyle(JerreaderTheme.accent)
        }
        .padding(.vertical, 2)
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
            Color(uiColor: .secondarySystemBackground),
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
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
