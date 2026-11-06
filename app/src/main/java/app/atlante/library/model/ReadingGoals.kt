package app.atlante.library.model

import android.content.SharedPreferences
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

/**
 * Reading goals: minutes and pages per day, books per month and per year. Stored in the app preferences with
 * the keys already used before ("dailyGoal", "monthGoal", "yearGoal"), so existing goals are kept.
 * Progress always comes from recorded data: reading sessions and real completion dates.
 */
class ReadingGoals(private val prefs: SharedPreferences) {
    init {
        val history=runCatching { JSONObject(prefs.getString("monthGoalHistory","{}")!!) }.getOrDefault(JSONObject())
        val now=YearMonth.now().toString()
        if(!history.has(now)) remember(YearMonth.now(),if(monthOn)monthBooks else 0)
    }
    var dailyMinutes: Int get() = prefs.getInt("dailyMinutesGoal", DEFAULT_MINUTES); set(v) = put("dailyMinutesGoal", v.coerceIn(1, 1440))
    var dailyMinutesOn: Boolean get() = prefs.getBoolean("dailyMinutesOn", false); set(v) = prefs.edit().putBoolean("dailyMinutesOn", v).apply()
    var dailyPages: Int get() = prefs.getInt("dailyGoal", DEFAULT_PAGES); set(v) = put("dailyGoal", v.coerceIn(1, 2000))
    var dailyPagesOn: Boolean get() = prefs.getBoolean("dailyPagesOn", true); set(v) = prefs.edit().putBoolean("dailyPagesOn", v).apply()
    var monthBooks: Int get() = prefs.getInt("monthGoal", DEFAULT_MONTH); set(v) { put("monthGoal", v.coerceIn(1, 100)); remember(YearMonth.now(), if(monthOn)v.coerceIn(1, 100) else 0) }
    var monthOn: Boolean get() = prefs.getBoolean("monthGoalOn", true); set(v) { prefs.edit().putBoolean("monthGoalOn", v).apply(); remember(YearMonth.now(),if(v)monthBooks else 0) }
    var yearBooks: Int get() = prefs.getInt("yearGoal", DEFAULT_YEAR); set(v) = put("yearGoal", v.coerceIn(1, 1000))
    var yearOn: Boolean get() = prefs.getBoolean("yearGoalOn", true); set(v) = prefs.edit().putBoolean("yearGoalOn", v).apply()

    private fun put(key: String, v: Int) = prefs.edit().putInt(key, v).apply()

    /** The monthly goal valid in each month, so past months are judged with the goal they had. */
    private fun remember(month: YearMonth, goal: Int) {
        val history = runCatching { JSONObject(prefs.getString("monthGoalHistory", "{}")!!) }.getOrDefault(JSONObject())
        history.put(month.toString(), goal); prefs.edit().putString("monthGoalHistory", history.toString()).apply()
    }

    fun monthGoalFor(month: YearMonth): Int {
        val history = runCatching { JSONObject(prefs.getString("monthGoalHistory", "{}")!!) }.getOrDefault(JSONObject())
        val known = history.keys().asSequence().mapNotNull { k -> runCatching { YearMonth.parse(k) }.getOrNull() }.filter { it <= month }.maxOrNull()
        return known?.let { history.optInt(it.toString(), 0) } ?: 0
    }

    fun resetDailyMinutes() { dailyMinutes = DEFAULT_MINUTES }
    fun resetDailyPages() { dailyPages = DEFAULT_PAGES }
    fun resetMonth() { monthBooks = DEFAULT_MONTH }
    fun resetYear() { yearBooks = DEFAULT_YEAR }

    data class Progress(val done: Long, val goal: Int) {
        val percent: Int get() = if (goal <= 0) 0 else minOf(100, Math.round(done * 100.0 / goal).toInt())
        val fraction: Float get() = if (goal <= 0) 0f else minOf(1f, done.toFloat() / goal)
        val reached: Boolean get() = goal > 0 && done >= goal
    }

    fun minutesToday(books: List<Book>, day: LocalDate = LocalDate.now()) = Progress(ReadingJournal.totals(books, day)[1] / 60, dailyMinutes)
    fun pagesToday(books: List<Book>, day: LocalDate = LocalDate.now()) = Progress(ReadingJournal.totals(books, day)[0], dailyPages)
    fun month(books: List<Book>, month: YearMonth = YearMonth.now()) = Progress(ReadingJournal.completed(books, month.atDay(1), true).toLong(), monthGoalFor(month))
    fun year(books: List<Book>, day: LocalDate = LocalDate.now()) = Progress(ReadingJournal.completed(books, day, false).toLong(), yearBooks)

    /** Previous months, newest first: books completed (real dates, each completion once) against that month's goal. */
    fun history(books: List<Book>, months: Int = 12, now: YearMonth = YearMonth.now()) =
        (1..months).map { now.minusMonths(it.toLong()) }.map { it to month(books, it) }

    companion object {
        const val DEFAULT_MINUTES = 30; const val DEFAULT_PAGES = 20; const val DEFAULT_MONTH = 2; const val DEFAULT_YEAR = 24
    }
}
