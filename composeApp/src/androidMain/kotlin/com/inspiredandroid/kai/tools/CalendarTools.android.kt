package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema

object CalendarTools {

    fun getToolInfos(): List<ToolInfo> = listOf(
        ToolInfo(
            id = "calendar_create",
            name = "Calendar Create",
            description = "Create a new event in the device calendar",
        ),
    )

    fun getCalendarTools(calendarRepository: CalendarRepository?): List<Tool> {
        if (calendarRepository == null) return emptyList()
        return listOf(calendarCreateTool(calendarRepository))
    }

    private fun calendarCreateTool(calendarRepository: CalendarRepository) = object : Tool {
        override val schema = ToolSchema(
            name = "calendar_create",
            description = "Create a new event in the device calendar. Use this when user asks to schedule, add, or create a calendar event or meeting. Returns the created event details including the event ID and formatted start time.",
            parameters = mapOf(
                "title" to ParameterSchema(type = "string", description = "Title of the calendar event", required = true),
                "start_time" to ParameterSchema(
                    type = "string",
                    description = "Start time in ISO 8601 format (e.g., '2024-03-15T14:30:00' or '2024-03-15T14:30:00+08:00'). Use local time if no timezone is specified.",
                    required = true,
                ),
                "end_time" to ParameterSchema(
                    type = "string",
                    description = "End time in ISO 8601 format. If not provided, defaults to 1 hour after start time.",
                    required = false,
                ),
                "description" to ParameterSchema(type = "string", description = "Description or notes for the event", required = false),
                "location" to ParameterSchema(type = "string", description = "Location of the event", required = false),
                "all_day" to ParameterSchema(type = "boolean", description = "Whether this is an all-day event", required = false),
                "reminder_minutes" to ParameterSchema(
                    type = "integer",
                    description = "Minutes before the event to set a reminder. Common values: 5, 10, 15, 30, 60. Set to 0 to disable reminder.",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val title = args["title"]?.toString()
                ?: return mapOf("success" to false, "error" to "Missing title")

            val startTime = args["start_time"]?.toString()
                ?: return mapOf("success" to false, "error" to "Missing start_time")

            val endTime = args["end_time"]?.toString()
            val description = args["description"]?.toString()
            val location = args["location"]?.toString()
            val allDay = (args["all_day"] as? Boolean) ?: false
            val reminderMinutes = (args["reminder_minutes"] as? Int) ?: 15

            return when (val result = calendarRepository.createEvent(
                title = title,
                startTimeIso = startTime,
                endTimeIso = endTime,
                description = description,
                location = location,
                allDay = allDay,
                reminderMinutes = reminderMinutes,
            )) {
                is CalendarResult.Success -> mapOf(
                    "success" to true,
                    "event_id" to result.eventId,
                    "title" to result.title,
                    "start_time" to result.startTime,
                    "message" to "Event created: ${result.title} at ${result.startTime}",
                )
                is CalendarResult.Error -> mapOf(
                    "success" to false,
                    "error" to result.message,
                )
            }
        }
    }
}