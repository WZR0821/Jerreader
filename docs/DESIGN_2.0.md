# Jerreader 2.0 设计规范「纸墨」

2.0 是大版本：静态视觉和动效一起重做，iOS 与 Android 按这份规范逐项一致。
参考的设计 skill 见文末。数值都以 pt（iOS）/ dp（Android）计，两端相同。

## 1. 原则

- **纸与墨**：暖白纸面作底，墨色文字，强调色（用户在设置里选的五套之一）只用在
  主要操作、选中状态和进度上。每屏只有一个实心强调按钮。
- **有层次**：底色 → 纸卡 → 浮起元素三级；浅色模式用柔和的染色阴影，深色模式不用
  阴影，改用 1pt 的白色 6% 描边。
- **少而准的动效**：高频操作（翻页、显示/隐藏阅读栏）只做约 0.2 秒的淡入或小位移，
  不回弹；回弹和庆祝动效只留给少见的时刻（掌握一个词、完成复习、读完一本书）。
  所有动画都可中断，系统“减弱动态效果”打开时一律换成 0.18 秒交叉淡化。
- **结构不变**：页面骨架、无障碍标识（accessibilityIdentifier / testTag）、一屏约束
  （翻译页、设置首页恰好一屏）全部保留，只改视觉和动效。

## 2. 颜色

| 令牌 | 浅色 | 深色 | 用途 |
| --- | --- | --- | --- |
| canvas | #F4F1EA | #101215 | 页面底色（暖纸 / 墨夜） |
| paper | #FFFDF8 | #181B20 | 卡片 |
| raisedPaper | #FFFFFF | #20242A | 浮起元素、输入框内底 |
| mutedSurface | #EBE6DB | #0B0D10 | 分段控件轨道、搜索框、占位 |
| line | #E3DCCD | #2B3038 | 发丝线、卡片描边 |
| vermilion 朱 | #C8513A | #F08A6E | 待复习、提醒点、删除前的强调 |
| gold 金 | #B07F28 | #E5BC62 | 连续天数、成就 |
| jade 青 | #3C8A66 | #72CC9E | 已掌握、成功 |

强调色（设置 › 主题色，存储键不变）：

| 主题 | 浅色 | 深色 |
| --- | --- | --- |
| 海蓝 ocean（默认） | #2C5D8F | #8DBCE8 |
| 森林 forest | #2E6B53 | #7FC8A6 |
| 紫藤 wisteria | #5B4C9C | #B7A9F0 |
| 琥珀 amber | #94601C | #EBB56A |
| 莓红 berry | #9A3A62 | #EE91B6 |

- accentFill：强调色 12%（浅）/ 22%（深）。
- onAccent：浅色白字；深色 #0B0F14。
- 次要文字：系统 secondary（iOS）/ onSurface 50%（Android `plainSecondary`）。
- **画布光晕**：页面顶部叠一层强调色 6%（深色 10%）的径向光晕，中心在顶部偏左
  （x 20%），半径为屏宽 1.1 倍，静态不动，只为让纸色不显得平。
- **墨色主卡（hero）渐变**：从强调色到“墨”色，135° 方向。墨色浅色模式 #1B2433，
  深色模式 #0B0E13；左上叠一层白色 16% 的径向高光（半径为卡片宽的 0.9 倍）。
- **无封面书的封面**：按书名稳定散列到 8 组配色（上 → 下渐变）：
  藍 #2C4A73→#172A45、臙脂 #8C3B4A→#4E1F2B、松葉 #2F5D4E→#18352C、
  柿 #B45E3A→#6E3420、鈍色 #4B5563→#262C35、藤 #6A5A9E→#3A2F5E、
  黄土 #A5793A→#5E4320、青磁 #4E8A86→#284B49。
  散列：书名 UTF-8 字节逐个 `h = h * 31 + b`（32 位无符号回绕），取 `h % 8`。

## 3. 字体

系统字体（中文为苹方 / Noto Sans CJK）。层级：

| 角色 | 规格 |
| --- | --- |
| 页面大标题 | 34 Bold（系统大标题） |
| 主卡标题 | 22 Bold，最多 2 行 |
| 区块标题 | 20 Bold |
| 卡片标题 | 17 Semibold |
| 正文 | 17 Regular |
| 辅助 | 15 Regular，次要色 |
| 说明 | 13 Regular，次要色 |
| 眉题（eyebrow） | 12 Semibold，强调色，字距 +0.6 |
| 数字 | 圆体（iOS `.rounded`；Android 用默认字体加 `tnum`）、等宽数字、Bold |

大数字（统计、复习数）用 28 Bold 圆体；数字变化时做数字滚动过渡（iOS
`.contentTransition(.numericText())`，Android `AnimatedContent` 上下滑入）。

## 4. 间距、圆角、阴影

- 间距 4 的倍数：4 / 8 / 12 / 16 / 20 / 24 / 32。页面左右边距 20，卡片内边距 16。
- 圆角：封面 6、小控件 10、输入/按钮 14、卡片 20、主卡 26、底部面板 28。
  全部用连续曲率（iOS `.continuous`）。
- 阴影（只在浅色模式）：
  - e1 卡片：黑 6%，半径 8，y 4
  - e2 主卡/封面：黑 12%，半径 16，y 8
  - e3 浮层：黑 18%，半径 24，y 12
  - 封面另加贴地阴影：黑 18%，半径 2，y 1
- 深色模式：不画阴影，卡片加 1pt 白色 6% 描边。

## 5. 动效

| 名称 | iOS | Android | 用途 |
| --- | --- | --- | --- |
| press | `.spring(duration: 0.16, bounce: 0)`，缩放 0.97 | `spring(dampingRatio = 1f, stiffness = 1500f)` | 按下 |
| release | `.spring(duration: 0.30, bounce: 0.25)` | `spring(0.6f, 600f)` | 松开回弹 |
| stateChange | `.spring(duration: 0.32, bounce: 0.12)` | `spring(0.85f, 500f)` | 状态切换 |
| selection | `.snappy(duration: 0.24, extraBounce: 0.08)` | `spring(0.8f, 700f)` | 分段指示条滑动 |
| reveal | `.spring(duration: 0.50, bounce: 0.14)`，每项延迟 0.04 秒，最多 0.3 秒 | `spring(0.86f, 260f)`，同样的延迟 | 首次出现：透明度 0→1、y 12→0、缩放 0.98→1 |
| hero | `.spring(duration: 0.42, bounce: 0.16)` | `spring(0.8f, 380f)` | 主卡、封面进入阅读器 |
| chrome | `.easeOut(duration: 0.20)` | `tween(200, FastOutSlowIn)` | 阅读栏显隐（不回弹） |
| reduced | `.easeInOut(duration: 0.18)` | `tween(180)` | 减弱动态效果时替代以上全部 |

- 阶梯出现只在页面**第一次**出现时做，滚动回来不再做。
- 进度条、环形进度从 0 生长到当前值（stateChange）。
- 触感：切换分段/标签 = 选择（selection）；收藏、加入生词本、复习完成 = 成功
  （success）；删除确认 = 警告（warning）。滚动列表时不触发。
- 图标：收藏/书签切换时 SF Symbol `.bounce` 一次（Android 缩放 1→1.2→1）。

## 6. 公共组件

两端同名（iOS 在 `Jerreader/App/JerreaderDesignSystem.swift`，Android 在
`shared/.../ui/JerreaderDesignSystem.kt`）：

- `JerreaderIconTile`：圆角 10 的方块，底色为色调 14%，符号为色调本色。
- `JerreaderStatValue`：图标 + 大数字（数字滚动）+ 标签。
- `JerreaderProgressBar`：胶囊进度条，高 4，轨道为色调 14%。
- `JerreaderProgressRing`：环形进度，线宽 6，圆头。
- `JerreaderPillPicker`：分段选择器，选中项是滑动的强调色胶囊，带选择触感。
- `JerreaderPrimaryButtonStyle` / `JerreaderPrimaryButton`：强调色实心胶囊，高 50，
  轻微由上到下 8% 亮度渐变，浅色模式 e2 染色阴影。
- `JerreaderTintedButtonStyle`：强调色 12% 底，强调色字。
- 卡片修饰 `jerreaderPaperCard`（沿用名字）：paper 底 + line 描边 + e1。
- 主卡背景 `JerreaderHeroBackground`。
- 封面修饰 `jerreaderBookCover`：左侧书脊暗影（宽 8%，黑 22%→0）+ 书脊高光线
  （白 12%，1pt）+ 左上光泽（白 10%→0）+ e2 + 贴地阴影。
- 无封面书封面 `JerreaderGeneratedCover`：上述配色 + 书名（白，Bold）+ 细线 + 作者。
- 眉题 `JerreaderEyebrow`。
- 动效辅助：`jerreaderBounce(trigger:)`（图标弹一下；iOS 的图标是自绘图片，所以
  用缩放关键帧而不是 symbolEffect）、`jerreaderReveal(order:)`（首次出现的错峰渐入）、
  `JerreaderPressButtonStyle` / Android `Modifier.jerreaderPressScale`（按下 0.97）。
- Android 另有 `Modifier.jerreaderCanvasBackground()`、`Modifier.jerreaderHeroBackground()`、
  `Modifier.jerreaderElevation()`、`Modifier.jerreaderPaperCard()`，对应 iOS 的
  `JerreaderCanvasBackground`、`JerreaderHeroBackground`、`jerreaderElevation`、`jerreaderPaperCard`。

## 7. 页面

### 书架

1. 导航大标题“书架”，副标题行显示今天的日期（“11月19日 星期三”，说明字号）。
2. **继续阅读主卡**（有读过的书时显示，取最近打开的一本）：墨色主卡渐变，圆角 26，
   内边距 18；左侧封面 84×120（`jerreaderBookCover`），右侧依次：眉题“继续阅读”
   （白 72%），书名（主卡标题，白），作者（15，白 75%），进度条（白 25% 轨道、白色
   填充）+“37%”与“2 小时前”（13，白 75%），右下“继续”胶囊按钮（白底，强调色字，
   高 34）。整卡可点，按下缩放 0.98。
3. **阅读统计**：一张纸卡里三列 `JerreaderStatValue`：累计阅读（金）、已开始
   （强调色）、平均进度（青），数字滚动。
4. **所有书籍**：区块标题 + 数量/排序。三列网格，列间距 16，行间距 24。
   每本：封面（2:3，`jerreaderBookCover`）→ 若有进度，封面下沿 3pt 强调色进度条
   → 书名（15 Semibold，2 行）→ 作者（13 次要）→ 格式小标签。读完的书右上角
   青色对勾徽章。网格项阶梯出现。
5. 空书架：72pt 图标块（强调色渐变方块里放书架符号）+ 标题“把下一本书放进来”
   （22 Bold）+ 说明 + 主按钮“导入书籍”+ 次按钮“从备份恢复”。
6. 打开书：iOS 18 用封面到阅读器的缩放转场（`matchedTransitionSource` +
   `.navigationTransition(.zoom)`），关闭时缩回封面。Android 阅读器是单独的
   Activity，用 `ActivityOptionsCompat.makeScaleUpAnimation` 从点下的封面（主卡封面、
   网格封面或菜单里的“开始阅读”）所在区域放大打开；关闭时是系统默认动画。

### 阅读器

1. 顶栏：系统材质背景（iOS `.bar` / Android canvas 92% + 模糊替代为纯色）
   + 底部发丝线；关闭按钮、书名（17 Semibold）与章节（12 次要）；右侧图标按钮
   36pt 圆形热区；“AI”是强调色 12% 底的胶囊（sparkles 图标 + AI）；“格式”换成
   “Aa”图标按钮（无障碍标签仍为“格式”）。
2. 底栏：保持 1.7.0 的高度（带进度时约 78pt），用户 11-17 要求“上下栏变小、常驻”。
   一行进度：章节名（左，说明字号 Semibold，最宽 104pt）· 自绘进度滑杆（强调色
   填充，轨道 line 色，高 4，拇指 22pt 白底 e1）· 百分比（右，说明字号等宽数字，
   最小宽 34pt），行高至少 32；下面一行导航按钮（44pt 热区），中间翻译按钮为 40pt
   强调色实心圆，e2 阴影。拖动滑杆时章节名换成目标位置（强调色）。
3. 阅读栏显隐：顶栏从上、底栏从下各移入 12pt 并淡入（chrome 曲线）；减弱动态
   效果时只淡入淡出。
4. 阅读设置面板：分组卡片；“阅读主题”一行圆形色板（44pt，选中加强调色 2pt 外圈和
   对勾，切换用 stateChange）；字号用“A − 数值 + A”，数值数字滚动；分段控件统一
   换成 `JerreaderPillPicker` 的外观（系统分段控件也可保留，但颜色跟随令牌）。
5. 译文卡：纸卡 + 顶部眉题（语言方向）+ 正文 17 + 底部操作行；出现时 reveal。

### 学习

1. 顶部四个分区（翻译 / 生词本 / 练习与工具 / 历史）用 `JerreaderPillPicker`：
   选中胶囊滑动（selection 曲线）+ 选择触感 + 选中图标 bounce。
2. 翻译（一屏）：语言条做成一张纸卡（源语言 · 交换按钮 · 目标语言），交换按钮
   旋转 180° 动画；输入卡与结果卡用 paper 底、圆角 20；“翻译”按钮用主按钮样式。
   保持原有高度分配，一屏约束不变。
3. 生词本：搜索框用 mutedSurface 底，圆角 14；词条卡：词（22 Bold）、读音
   （15 次要）、释义（17）、语言标签与词性；收藏星标切换 bounce + 成功触感；
   列表阶梯出现。
4. 练习与工具：**今日练习主卡**（墨色主卡渐变）：左侧环形进度（已复习/今日目标，
   白色），右侧两列大数字（收藏词语、原句收藏，白），底部白底“开始复习”胶囊按钮；
   “AI 困惑地图”纸卡；工具列表每行一个彩色 `JerreaderIconTile`（原句练习卡=强调色、
   语法知识库=朱、阅读成长报告=青）。
5. 历史：按日期分组，组标题为眉题样式；每条左侧 8pt 圆点（按类型着色）。
6. 复习：顶部进度条随题目推进生长；卡片“显示答案”时内容交叉淡化并轻微放大
   （stateChange）；答对/答错给成功/警告触感；完成时显示青色对勾 bounce。
7. 阅读成长：柱状图从 0 生长（stateChange，每根延迟 0.03 秒），数字滚动。

## 8. 参考的设计 skill

- heyimjames/ios-design-skills「the-final-5-percent」（MIT）：弹簧、按压、阴影、空状态数值。
- AvdLee/SwiftUI-Agent-Skill（MIT）、twostraws/SwiftUI-Agent-Skill（MIT）、
  Dimillian/Skills（MIT）：动画作用域、转场、减弱动态效果、性能。
- emilkowalski/skills「apple-design」（MIT）：可中断弹簧、频率与时长取舍。
- chrisbanes/skills「compose-animations」（Apache-2.0）、android/skills（Apache-2.0）、
  aldefy/compose-skill（MIT）：Compose 动画 API 选择、弹簧常数、延迟读取。
- anthropics/skills「frontend-design」（Apache-2.0）：先定少量令牌、克制、一个编排好的时刻。
