package com.jerreader.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerreader.android.AppGraph
import com.jerreader.android.learning.ReadingGrowthSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@Composable
fun ReadingGrowthScreen(graph: AppGraph) {
    val books by remember(graph) { graph.database.bookDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val words by remember(graph) { graph.learningRecords.observeWords() }.collectAsStateWithLifecycle(emptyList())
    val preferences by graph.appSettings.preferences.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    var weeksAgo by remember { mutableIntStateOf(0) }
    var bookMenu by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val start = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(weeksAgo.toLong())
    val activities = remember(books, selected) { books.filter { selected == null || it.id == selected }.map { it.activityJson } }
    val reviews = remember(words) { words.map { it.reviewJson } }
    val request = GrowthRequest(activities, reviews, start, selected, zone, preferences.growthEnabled)
    val prepared by produceState<Pair<GrowthRequest, ReadingGrowthSnapshot>?>(null, request) {
        if (request.enabled) value = request to withContext(Dispatchers.Default) {
            ReadingGrowthSnapshot.create(request.activities, request.reviews, request.start, request.book, request.zone,
                checkpoint = { ensureActive() })
        }
    }
    val report = prepared?.takeIf { it.first == request }?.second
    LearningPage {
      item {
       LearningCard {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("记录并显示成长报告", modifier = Modifier.weight(1f))
            LearningSwitch(preferences.growthEnabled, "记录并显示成长报告", onCheckedChange = graph.appSettings::updateGrowthEnabled)
        }
        Text("按本机日期记录。关闭后暂停采集并隐藏报告，既有记录保留。历史只从新版开始累计。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
       }
      }
        if (preferences.growthEnabled) {
          item {
           LearningCard {
            Box {
                // A menu `Picker` on iOS: 34 points tall, its label set 12 points in.
                LearningButton(onClick = { bookMenu = true }, compact = true) {
                    Text(books.firstOrNull { it.id == selected }?.title ?: "全部书籍", Modifier.padding(start = 12.4.dp));
                    com.jerreader.shared.ui.JerreaderGlyph(com.jerreader.shared.ui.JerreaderIcon.CHEVRON_UP_DOWN, MaterialTheme.colorScheme.primary, Modifier.padding(start = 6.dp).size(12.dp)) }
                DropdownMenu(bookMenu, { bookMenu = false }) {
                    DropdownMenuItem(text = { Text("全部书籍") }, onClick = { selected = null; bookMenu = false })
                    books.forEach { book -> DropdownMenuItem(text = { Text(book.title) }, onClick = { selected = book.id; bookMenu = false }) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LearningButton(enabled = weeksAgo < 103, onClick = { weeksAgo++ }) { Text("上一周") }
                LearningButton(enabled = weeksAgo > 0, onClick = { weeksAgo-- }) { Text("下一周") }
            }
            Text("$start 起的一周")
           }
          }
          item {
            if (report == null) {
                LearningCard { Text("正在汇总阅读记录…") }
            } else {
            val seconds = report.seconds
            val queries = report.queries
            LearningCard {
                GrowthMetric("阅读天数", "${report.readingDays} / 7")
                GrowthMetric("阅读时长", "${"%.1f".format(seconds / 60)} 分钟")
                GrowthMetric("书内查询", "$queries 次")
                GrowthMetric("每 10 分钟查询", if (seconds > 0) "${"%.1f".format(queries * 600 / seconds)} 次" else "暂无阅读时长")
                GrowthMetric("复习正确率", if (report.answerCount == 0) "本周未评分" else
                    "${report.knownAnswers * 100 / report.answerCount}%（${report.answerCount} 次）")
                Text("查询包含书内发起的翻译、查词及重试；复习正确率＝选择‘认识’的次数／全部评分。没有评分时不显示 0%。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                report.rows.forEach { (day, values) ->
                    val value = values.seconds
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$day · ${(value / 60).toInt()} 分钟", style = MaterialTheme.typography.labelMedium)
                        LearningProgress((value / report.maxSeconds).toFloat())
                    }
                }
            }
            }
          }
        }
    }
}

private data class GrowthRequest(val activities: List<String?>, val reviews: List<String?>,
    val start: LocalDate, val book: String?, val zone: ZoneId, val enabled: Boolean)

@Composable
private fun GrowthMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
