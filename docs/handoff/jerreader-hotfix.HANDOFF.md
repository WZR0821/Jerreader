# Jerreader 1.4.0（59）交付记录

> 2026-11-11 注：这是 1.4.0（59）的历史记录。当前交接见同目录的 `jerreader-1-4-1.HANDOFF.md`。

2026-10-17：用户要求的本轮修复、双端模拟器验收、视觉对照及打包已完成。本任务没有正在运行的构建、测试、模拟器或待确认事项。工作区保留全部未提交改动，本轮没有提交、PR、重置或清理用户文件。

## 最终交付

- iOS：dist/iOS/Jerreader-1.4.0-build59-20261017-032719-unsigned.ipa；13,816,767字节；SHA256 ff5256fdfe362eec3a763116e64214662c30431715ff4ab4d245454c490f7adc；iOS18+、arm64、unsigned Release，旁附sha256及validation.json。
- Android：dist/Android/Jerreader-Android-1.4.0-build59-20261017-034058-release.apk；144,076,067字节；SHA256 5ec095a111fd00c1c7cf0782db34a0707dbe28af111a78d7e0de4b112bf86e10；API23+、既有签名Release、37254类零重复、无Debug夹具，旁附sha256及validation.json。
- 图集：dist/QA/1.4.0-build59/index.html，37组同状态＋8张阅读器预览，82预览/165引用，原图无改图。来源与SHA在三个evidence.json；318文件source-sha256零漂移，validation-summary与validation-records保留阶段结果。
- 修复、阶段总结、后续维护：docs/HOTFIX_1.4.0_2026-10-16.md。README/ARCH/维护入口已更新；旧build58 handoff保留为历史。

## 实际验证

- iOS 015138-c6qhum_z：255过＋3外部真实书夹具跳过，0失败；含24备份、严格bytes/纳秒mtime不变。3876字符长书普通7页/大字14页，全正反遍历/对应页相等，41几何clipped=0，83附件在QA/Reader。
- iOS 031141-m57b9jry：9UI全过无跳过，词条详情/复制收藏返回、主二级页、模式往返与实际横竖滑动固定；44最新截图。001708三物理长按前次过，不能算最新9的新用例。
- iOS generic Release 032317-vrhtp28z通过；package_unsigned_ipa.py核验无签名/加密/夹具、单arm64MachO、执行位、CRC、逐文件SHA。
- Android 032717-zpt8sthp：6m6s；Android67单元实际执行通过，共享77为已通过的UP-TO-DATE；双Lint、Debug/androidTest/Release通过。025603阶段共享77实际通过。
- Android 030359-1469u300：完整21全过＝16功能（8备份、实际Readium点译/bytes+mtime/基础离线词典）＋5UI（千条返回保留筛选/固定搜索）。
- Android 033344-2ltsey40：最终详情按钮宽度修改后5UI全过，Release原地升级/断网启动/书架渲染通过。最终包SHA与实际安装一致，公共package_release_apk.py校验同旧签名、CRC、所有DEX唯一及6标记。
- 63保护Python测试通过，图标生成检查/语法检查/diff检查/证据SHA与源漂移检查通过。不要用旧20项声称覆盖千条列表；目前UI类5项，full总21。

## 核心改动

Readium保留内容/定位/排版权；WebKit body唯一原生分页容器、异步paging观察、RTL起始边缘与逻辑页。模式恢复不能reload loading/about:blank。POSIX timespec/utimensat保护纳秒mtime，不变不写。

学习四分区为翻译/生词本/练习与工具/历史，资源下载集中设置。Android词条详情只显示已存字段，打开不查词或联网；点按进入、长按管理、返回保留查询。共享矢量、透明色、字体层级、细线、封面草稿与取消；复制/收藏等分。贴边ReaderChrome及52/128正文保留区由实际点译验证覆盖，多章翻章按钮按两端相同条件显示。

备份外部目录优先，恢复沿用实际目录；同路径刷新授权、失败不覆盖旧配置；完整CRC/hash验证后合并，保留原书与较新进度，失败回滚。

## 已定位的失败与边界

024217千条用例曾失败于返回后旧AX树/未等待真实输入框，测试助手刷新等待后030359完整21和最终5UI通过，未放宽原断言。旧Android生成树出现重复2.class/2.dex，候选拒绝交付，已可逆隔离至.build；当前忽略build symlink到Library/Caches独立输出，未改系统同步，来源尚未确定。所有较早build59候选只作历史，不能替代上述最终包。

最新32MiB文件管线微基准030359：before[1673,1439,1483]ms，after[344,360,359]ms，中位1483→359约76%。不是全Readium导入/打开或真机新旧APK对比。

iOS27.2/个人证书重签启动、真实云盘重装授权和首次Apple/MLKit模型下载未覆盖；3外部EPUB夹具跳过。短按完整单词仍正式目标，降级长按或词语模式。Android历史例句持久化需未来schema/备份迁移。系统栏/原生字体/动态字号/系统picker/材料效果存在平台差异，未宣称每像素相同。

用户本任务的温控/负载/外部占用例外仅命令局部生效，默认保护未改；后续继续遵守AGENTS/PRD/ARCH、安全入口、独占串行和书籍不可变回归。后续优先补真机阅读/个人证书、外部目录重装恢复，再测完整文件性能与首次模型下载，详见报告维护七项。
