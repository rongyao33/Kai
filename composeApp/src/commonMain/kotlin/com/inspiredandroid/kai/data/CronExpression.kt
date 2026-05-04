package com.inspiredandroid.kai.data

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
private val whitespaceRegex = Regex("\\s+")

class CronExpression(expression: String) {

    private val minutes: Set<Int>
    private val hours: Set<Int>
    private val daysOfMonth: Set<Int>
    private val months: Set<Int>
    private val daysOfWeek: Set<Int>
    private val dayOfMonthIsStar: Boolean
    private val dayOfWeekIsStar: Boolean

    init {
        val parts = expression.trim().split(whitespaceRegex)
        require(parts.size == 5) { "Cron expression must have 5 fields, got ${parts.size}: $expression" }
        minutes = parseField(parts[0], 0, 59)
        hours = parseField(parts[1], 0, 23)
        dayOfMonthIsStar = parts[2] == "*"
        daysOfMonth = parseField(parts[2], 1, 31)
        months = parseField(parts[3], 1, 12)
        dayOfWeekIsStar = parts[4] == "*"
        daysOfWeek = parseField(parts[4], 0, 6)
    }

    fun nextAfter(after: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): Instant? {
        val afterKx = Instant.fromEpochMilliseconds(after.toEpochMilliseconds())
        var dt = afterKx.toLocalDateTime(timeZone)
        dt = LocalDateTime(dt.date, LocalTime(dt.hour, dt.minute, 0, 0))
        dt = advanceMinute(dt, timeZone)

        val maxIterations = 525960
        var iterations = 0

        while (iterations < maxIterations) {
            iterations++

            if (dt.date.month.ordinal + 1 !in months) {
                dt = nextMonth(dt) ?: return null
                continue
            }

            val dayMatches = if (dayOfMonthIsStar && dayOfWeekIsStar) {
                true
            } else if (dayOfMonthIsStar) {
                toCronDayOfWeek(dt) in daysOfWeek
            } else if (dayOfWeekIsStar) {
                dt.date.day in daysOfMonth
            } else {
                dt.date.day in daysOfMonth || toCronDayOfWeek(dt) in daysOfWeek
            }

            if (!dayMatches) {
                dt = nextDay(dt, timeZone)
                continue
            }

            if (dt.hour !in hours) {
                dt = nextHour(dt, timeZone)
                continue
            }

            if (dt.minute !in minutes) {
                dt = advanceMinute(dt, timeZone)
                continue
            }

            return Instant.fromEpochMilliseconds(dt.toInstant(timeZone).toEpochMilliseconds())
        }
        return null
    }

    private fun advanceMinute(dt: LocalDateTime, tz: TimeZone): LocalDateTime {
        val instant = dt.toInstant(tz)
        val next = Instant.fromEpochMilliseconds(instant.toEpochMilliseconds() + 60_000L)
        return next.toLocalDateTime(tz)
    }

    private fun nextHour(dt: LocalDateTime, tz: TimeZone): LocalDateTime {
        val nextHour = (dt.hour + 1).coerceAtMost(23)
        if (nextHour <= dt.hour) {
            return nextDay(dt, tz)
        }
        return LocalDateTime(dt.date, LocalTime(nextHour, 0, 0, 0))
    }

    private fun nextDay(dt: LocalDateTime, tz: TimeZone): LocalDateTime {
        val nextDate = dt.date.plus(1, DateTimeUnit.DAY)
        return LocalDateTime(nextDate, LocalTime(0, 0, 0, 0))
    }

    private fun nextMonth(dt: LocalDateTime): LocalDateTime? {
        var year = dt.year
        var month = dt.date.month.ordinal + 2
        if (month > 12) {
            month = 1
            year++
        }
        if (year > dt.year + 2) return null
        return LocalDateTime(LocalDate(year, month, 1), LocalTime(0, 0, 0, 0))
    }

    private fun toCronDayOfWeek(dt: LocalDateTime): Int = when (dt.dayOfWeek) {
        DayOfWeek.SUNDAY -> 0
        DayOfWeek.MONDAY -> 1
        DayOfWeek.TUESDAY -> 2
        DayOfWeek.WEDNESDAY -> 3
        DayOfWeek.THURSDAY -> 4
        DayOfWeek.FRIDAY -> 5
        DayOfWeek.SATURDAY -> 6
    }

    companion object {
        private fun parseField(field: String, min: Int, max: Int): Set<Int> {
            val result = mutableSetOf<Int>()
            for (part in field.split(",")) {
                when {
                    part == "*" -> result.addAll(min..max)

                    part.startsWith("*/") -> {
                        val step = part.substringAfter("*/").toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid step in cron field: $part")
                        var i = min
                        while (i <= max) {
                            result.add(i)
                            i += step
                        }
                    }

                    part.contains("-") -> {
                        val dashParts = part.split("-")
                        require(dashParts.size == 2) { "Invalid range in cron field: $part" }
                        val start = dashParts[0].toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid range start in cron field: $part")
                        val end = dashParts[1].toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid range end in cron field: $part")
                        if (start > end) {
                            throw IllegalArgumentException("Reverse range not supported in cron field: $part (start=$start > end=$end)")
                        }
                        result.addAll(start.coerceIn(min, max)..end.coerceIn(min, max))
                    }

                    else -> {
                        val value = part.toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid cron field value: $part")
                        if (value in min..max) result.add(value)
                    }
                }
            }
            return result
        }
    }
}
