# 第三方词典数据

## JMdict common-word subset

Jerreader 1.4 随 App 提供一份由 JMdict 常用词条生成的离线日语词典索引。它在中文维基词典不可访问时提供读音、词性和英文释义，不替代联网中文释义。

- 原始项目：Electronic Dictionary Research and Development Group（EDRDG）的 [JMdict/EDICT Dictionary Project](https://www.edrdg.org/wiki/JMdict-EDICT_Dictionary_Project.html)
- 转换输入：[jmdict-simplified](https://github.com/scriptin/jmdict-simplified) 的 `jmdict-eng-common` 发行文件
- 本次词典日期：2026-07-20
- 许可：[Creative Commons Attribution-ShareAlike 4.0](https://creativecommons.org/licenses/by-sa/4.0/)

仓库中的 `sharedResources/dictionaries/jmdict-common.tsv` 是上述数据的派生文件，仅该词典数据及其派生内容继续受 CC BY-SA 4.0 约束。Jerreader 的其他源代码许可证不因捆绑该独立数据文件而改变。

重新生成：下载对应版本的 `jmdict-eng-common-*.json.zip`，解压后运行：

```bash
ruby scripts/generate-jmdict-common.rb <输入 JSON> sharedResources/dictionaries/jmdict-common.tsv
```
