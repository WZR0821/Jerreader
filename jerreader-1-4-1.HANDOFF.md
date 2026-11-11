# Jerreader 1.4.1（60）交付完成

2026-10-17：本轮修复、双端设备验收、视觉核对和打包已完成。没有正在运行的本任务构建／模拟器、排队阶段或待确认事项。保留全部未提交改动；没有提交、PR、reset、清理用户文件或停止其他项目。

## 最终交付（仅使用这些文件）

- iOS：`dist/iOS/Jerreader-1.4.1-build60-20261017-191110-unsigned.ipa`，13,828,956 字节，iOS18+、arm64、未签名 Release；SHA256 `191d3fa9c926fc2b62d790ad37a90d7804f19a7a4cadd04b28808ee43a3f8b62`。
- Android：`dist/Android/Jerreader-Android-1.4.1-build60-20261017-193013-release.apk`，144,092,470 字节，API23+、同上一版签名 Release；SHA256 `bd1af77acc3a7ea6a981b0a2429022daf6eab87782e4f776dc25e453a7f791bd`，37,268 类零重复、无 Debug 夹具。
- 两包各有 sha256 与 validation.json。旧18:29 APK 是粗轨道样式候选，不是最终交付，不得改名代替。
- 图集：`dist/QA/1.4.1-build60/index.html`，39组配对、118预览；iOS54命名原图、Android123份图／文本、Reader83附件；图像未修改，阶段/用例/摘要见三个evidence.json。
- 最终报告与后续维护：`docs/HOTFIX_1.4.1_2026-10-17.md`。`validation-summary.json`、`validation-records/` 和358文件`source-sha256.json`在同一QA目录。README/架构/维护入口/安卓构建说明已更新。

## 实际验证

- iOS最终全量 `184627-4gzjaa8h`：258项，255通过、3外部真实书夹具跳过，0失败，66.9s。含设置备份、完整阅读会话 bytes＋纳秒mtime不变；3,876字符长书普通7页/大字14页，反向对应页相等，41几何记录clipped0，83最新附件已刷新。
- iOS UI为12个独立用例最终通过：`180306-462y7a9j`保留6项（Learning5＋真实横竖切换/滑动固定）；`183000-nb4srqyg`保留5项（真实横竖点译含注音、长卡、Standalone、四边缘、竖侧窗）；`183938-zm8217_i`最后1项阅读控件/状态栏/书籍管理过。前两阶段整轮曾失败，不能说整轮通过；最终审计逐case去重12并验证每张图来源。
- iOS正式 `190132-0lm99e31`：显式Release generic、禁签名通过。公共package_unsigned_ipa.py校验架构、无签名/加密/测试文件、执行位、CRC与逐文件摘要。
- Android最终构建 `191112-lzp3mp3j`：6m32s，150单位结果（共享77未变缓存＋Android73实际执行）通过；双Lint0错误各28警告、Debug/测试/Release通过。
- Android最终设备 `191841-qr9lhq67`：22全过＝17功能（9备份）＋5UI，含千条学习记录。ReaderTap真实Readium点译/bytes+mtime、24真实Compose几何夹具、滑块35/75%保存、真实statusBars Insets过。最终Release原地升级/断网冷启动/书架渲染过，安装SHA与交付一致。
- 最终32MiB文件管线样本 before[1614,1493,1476]→after[381,371,360]ms，中位1493→371约75.2%。只是复制/指纹/校验微基准，不能当整本书Readium打开或真机对比。
- 63保护Python测试本轮真实通过；保护脚本此后未改。最后的静态、来源与摘要审计见QA记录。

## 核心实现与已定位问题

根UI缩放最高等效19/17，学习四标签固定13；Readium正文字号独立。离线资源短说明/下载卸载/必要进度，许可仅短外链但包内保留原许可。Apple语言包系统管理，Android中日包顺序处理。

上下栏背景透明度默认15%，正文保留稳定空间避免控件显隐重分页；新沉浸阅读默认隐藏阅读页状态栏，保留摄像头/刘海安全区。新四设置进入备份且旧字段缺省保留当前值。日语注音默认关，iOS本机分词，安卓保守已知词典读音/活用，未知歧义不猜；只画辅助卡片，不写原书、不重翻译。

修正高度PreferenceKey非中性默认值覆盖实际高度：default0/忽略非正无穷，卡片background GeometryReader读实际global frame。DEBUG诊断挂window不裁正文bounds，真实请求selectionFrame与真实DOM点击字同时验避让；dismissTranslation清诊断。竖边缘fixture的focus与source一致。安卓侧窗优先TOP_BANNER，手机144–220点，间距竖12/句18/段26；透明度新滑块复用进度的4dp细轨/28dp白圆柄，5%步进。

测试维护：安卓第4几何例误清缓存框（限高内容改变不一定重新回调）已修；完整AX树证明操作在命名节点父层，已按真实动作向上查找；iOS standalone固定来源避免跨用例污染；开关点实际右侧并等状态，书架批量整理按“书架更多操作”菜单验证。原高度/避让/稳定/文件不变断言未放宽。

## 后续边界

真实iOS27.2/个人证书安装、首次系统语言包下载、iCloud/SAF重装授权仍需真机补验，3外部真实书夹具跳过。系统栏、原生字体、文件选择器及Sheet/Dialog保留平台差异，未宣称每像素相同。短按完整词语仍按PRD记录的降级和正式目标推进。

用户版本规则已存AGENTS：小更新末位+1，大更新中间+1/末位0；下一个小更新通常为1.4.2/内部61。继续工作先读PRD/ARCH/AGENTS及当前报告。只走安全入口，自己的原生阶段串行。本次三项资源例外仅环境变量局部生效，默认保护未改，实际显示异常仍停止。

**正式IPA必须显式 `JERREADER_CONFIGURATION=Release scripts/safe_xcodebuild.sh build`。** 通用build默认Debug；185227误选Debug后仅中止了本任务已验证的supervisor并完整清理，不能用它打包。正确Release为190132。原始失败/取消记录仍保留，不自动重跑同命令。
