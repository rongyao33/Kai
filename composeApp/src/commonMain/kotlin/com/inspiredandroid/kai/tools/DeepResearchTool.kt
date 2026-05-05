package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_deep_research_description
import kai.composeapp.generated.resources.tool_deep_research_name

private const val TOOL_DESCRIPTION = """Perform deep research on a topic using multiple sources. This tool:

1. Searches the web for relevant information
2. Fetches and analyzes content from top results
3. Synthesizes findings into a comprehensive report

Use this when you need thorough, multi-source research on any topic. Returns:
- summary: Brief overview of findings
- key_points: List of important discoveries
- sources: List of source URLs used
- detailed_findings: In-depth analysis
- recommendations: Suggested next steps

Parameters:
- query: The research question or topic
- depth: "quick" (3 sources), "standard" (5 sources), or "deep" (10 sources)
- focus_areas: Optional specific aspects to focus on"""

@Serializable
private data class SearchResult(
    val title: String,
    val link: String,
    val snippet: String,
)

@Serializable
private data class ResearchReport(
    val query: String,
    val summary: String,
    val key_points: List<String>,
    val sources: List<String>,
    val detailed_findings: String,
    val recommendations: List<String>,
)

object DeepResearchTool : Tool {
    private val json = Json { ignoreUnknownKeys = true }

    override val timeout: Duration = 180.seconds

    private val researchClient = httpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
        }
    }

    override val schema = ToolSchema(
        name = "deep_research",
        description = TOOL_DESCRIPTION,
        parameters = mapOf(
            "query" to ParameterSchema("string", "The research question or topic to investigate", true),
            "depth" to ParameterSchema("string", "Research depth: 'quick' (3 sources), 'standard' (5 sources), or 'deep' (10 sources)", false),
            "focus_areas" to ParameterSchema("array", "Optional specific aspects to focus on", false),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val query = args["query"] as? String ?: return mapOf("error" to "Query is required")
        val depth = (args["depth"] as? String)?.lowercase() ?: "standard"
        @Suppress("UNCHECKED_CAST")
        val focusAreas = args["focus_areas"] as? List<String> ?: emptyList()

        val sourceCount = when (depth) {
            "quick" -> 3
            "deep" -> 10
            else -> 5
        }

        return try {
            val results = performResearch(query, sourceCount, focusAreas)
            mapOf(
                "success" to true,
                "query" to results.query,
                "summary" to results.summary,
                "key_points" to results.key_points,
                "sources" to results.sources,
                "detailed_findings" to results.detailed_findings,
                "recommendations" to results.recommendations,
            )
        } catch (e: Exception) {
            mapOf(
                "success" to false,
                "error" to (e.message ?: "Research failed"),
                "query" to query,
            )
        }
    }

    private suspend fun performResearch(query: String, sourceCount: Int, focusAreas: List<String>): ResearchReport {
        val searchResults = searchWeb(query, sourceCount)
        val sources = mutableListOf<String>()
        val contentBuilder = StringBuilder()

        for (result in searchResults) {
            sources.add(result.link)
            val content = fetchContent(result.link)
            if (content != null) {
                contentBuilder.append("## ${result.title}\n")
                contentBuilder.append("Source: ${result.link}\n")
                contentBuilder.append(content.take(2000)).append("\n\n")
            }
        }

        val summary = generateSummary(query, contentBuilder.toString(), focusAreas)
        val keyPoints = extractKeyPoints(contentBuilder.toString())
        val recommendations = generateRecommendations(query, contentBuilder.toString())

        return ResearchReport(
            query = query,
            summary = summary,
            key_points = keyPoints,
            sources = sources,
            detailed_findings = contentBuilder.toString(),
            recommendations = recommendations,
        )
    }

    private suspend fun searchWeb(query: String, limit: Int): List<SearchResult> {
        return try {
            val encodedQuery = encodeUrl(query)
            val response = researchClient.get("https://html.duckduckgo.com/html/?q=$encodedQuery") {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            val html = response.body<String>()
            parseSearchResults(html, limit)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeUrl(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~' -> sb.append(c)
                c == ' ' -> sb.append('+')
                else -> {
                    val bytes = c.toString().encodeToByteArray()
                    for (b in bytes) {
                        sb.append('%')
                        sb.append("%02X".format(b.toInt() and 0xFF))
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun parseSearchResults(html: String, limit: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val linkPattern = """<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>([^<]+)</a>""".toRegex()
        val snippetPattern = """<a[^>]*class="result__snippet"[^>]*>([^<]+)</a>""".toRegex()

        val linkMatches = linkPattern.findAll(html).take(limit)
        val snippetMatches = snippetPattern.findAll(html).take(limit).toList()

        linkMatches.forEachIndexed { index, match ->
            val url = match.groupValues[1]
            val title = match.groupValues[2].trim()
            val snippet = snippetMatches.getOrNull(index)?.groupValues?.get(1)?.trim() ?: ""

            if (url.startsWith("http") && !url.contains("duckduckgo.com")) {
                results.add(SearchResult(title = title, link = url, snippet = snippet))
            }
        }

        return results
    }

    private suspend fun fetchContent(url: String): String? {
        return try {
            val host = try { io.ktor.http.Url(url).host } catch (_: Exception) { null }
            if (host != null && isBlockedHost(host)) return null
            val response = researchClient.get(url) {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            val html = response.body<String>()
            extractTextFromHtml(html)
        } catch (e: Exception) {
            null
        }
    }

    private fun isBlockedHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        val blocked = listOf("localhost", "127.0.0.1", "0.0.0.0", "::1", "169.254.169.254")
        if (h in blocked) return true
        if (h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("fe80:")) return true
        if (h.startsWith("172.")) {
            val second = h.split(".").getOrNull(1)?.toIntOrNull() ?: 0
            if (second in 16..31) return true
        }
        if (h == "::ffff:127.0.0.1") return true
        if (h.startsWith("::ffff:")) {
            val ipv4 = h.substringAfter("::ffff:")
            if (ipv4 == "127.0.0.1" || ipv4.startsWith("10.") || ipv4.startsWith("192.168.")) return true
        }
        return false
    }

    private fun extractTextFromHtml(html: String): String {
        val text = html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return text.take(5000)
    }

    private fun generateSummary(query: String, content: String, focusAreas: List<String>): String {
        val wordCount = content.split("\\s+".toRegex()).size
        val focusNote = if (focusAreas.isNotEmpty()) {
            " Focus areas: ${focusAreas.joinToString(", ")}."
        } else {
            ""
        }
        return "Research on '$query' analyzed $wordCount words from multiple sources.$focusNote Key findings extracted and synthesized below."
    }

    private fun extractKeyPoints(content: String): List<String> {
        val sentences = content.split("[.!?]+".toRegex())
            .map { it.trim() }
            .filter { it.length > 50 && it.length < 300 }
            .distinct()
            .take(10)

        return sentences.map { sentence ->
            sentence.take(150) + if (sentence.length > 150) "..." else ""
        }
    }

    private fun generateRecommendations(query: String, content: String): List<String> {
        return listOf(
            "Explore specific aspects in more depth with follow-up queries",
            "Verify key claims with primary sources",
            "Consider alternative perspectives on controversial topics",
            "Check the recency of information for time-sensitive topics",
        )
    }

    val toolInfo = ToolInfo(
        id = "deep_research",
        name = "Deep Research",
        description = "Perform comprehensive multi-source research on any topic",
        nameRes = Res.string.tool_deep_research_name,
        descriptionRes = Res.string.tool_deep_research_description,
        isEnabled = true,
    )
}
