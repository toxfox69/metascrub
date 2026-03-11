package com.energenai.metascrub

import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ScrapedPage(
    val url: String,
    val title: String,
    val depth: Int,
    val content: String,
    val links: List<String>,
    val meta: Map<String, String>,
    val error: String? = null
)

data class ScrubResult(
    val rootUrl: String,
    val pages: List<ScrapedPage>,
    val totalLinks: Int,
    val elapsedMs: Long
) {
    fun toText(): String = buildString {
        appendLine("=" .repeat(72))
        appendLine("METASCRUB — Web Content Extract")
        appendLine("Root: $rootUrl")
        appendLine("Pages scraped: ${pages.size} | Links found: $totalLinks | Time: ${elapsedMs}ms")
        appendLine("=".repeat(72))
        appendLine()

        pages.forEachIndexed { i, page ->
            if (page.error != null) {
                appendLine("--- [${i + 1}/${pages.size}] ERROR: ${page.url} ---")
                appendLine("Error: ${page.error}")
                appendLine()
                return@forEachIndexed
            }

            val depthMarker = if (page.depth == 0) "ROOT" else "DEPTH ${page.depth}"
            appendLine("--- [${i + 1}/${pages.size}] $depthMarker: ${page.title} ---")
            appendLine("URL: ${page.url}")

            if (page.meta.isNotEmpty()) {
                page.meta.forEach { (k, v) ->
                    if (v.isNotBlank()) appendLine("$k: $v")
                }
            }
            appendLine()
            appendLine(page.content)
            appendLine()
        }

        appendLine("=".repeat(72))
        appendLine("END OF SCRUB — ${pages.size} pages extracted")
        appendLine("=".repeat(72))
    }
}

class SpiderScrubber(
    private val maxDepth: Int = 2,
    private val maxPages: Int = 50,
    private val sameDomainOnly: Boolean = true,
    private val timeoutMs: Int = 15_000,
    private val onProgress: (scraped: Int, queued: Int, current: String) -> Unit = { _, _, _ -> }
) {
    private val visited = ConcurrentHashMap.newKeySet<String>()
    private val pagesScraped = AtomicInteger(0)
    private val allLinks = AtomicInteger(0)

    suspend fun scrub(url: String): ScrubResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val rootDomain = extractDomain(url)
        val pages = mutableListOf<ScrapedPage>()

        // BFS spider
        data class QueueItem(val url: String, val depth: Int)
        val queue = ArrayDeque<QueueItem>()
        queue.add(QueueItem(normalizeUrl(url), 0))

        while (queue.isNotEmpty() && pagesScraped.get() < maxPages) {
            val batch = mutableListOf<QueueItem>()
            // Take up to 5 items for parallel scraping
            repeat(minOf(5, queue.size, maxPages - pagesScraped.get())) {
                if (queue.isNotEmpty()) batch.add(queue.removeFirst())
            }

            val results = batch.mapNotNull { item ->
                if (!visited.add(item.url)) return@mapNotNull null
                async {
                    onProgress(pagesScraped.get(), queue.size + batch.size, item.url)
                    scrapePage(item.url, item.depth, rootDomain)
                }
            }.awaitAll()

            for (page in results) {
                pagesScraped.incrementAndGet()
                pages.add(page)

                // Queue discovered links for next depth
                if (page.depth < maxDepth && page.error == null) {
                    for (link in page.links) {
                        val norm = normalizeUrl(link)
                        if (norm !in visited &&
                            (!sameDomainOnly || extractDomain(norm) == rootDomain) &&
                            isScrapableUrl(norm)
                        ) {
                            queue.add(QueueItem(norm, page.depth + 1))
                        }
                    }
                }
            }
        }

        ScrubResult(
            rootUrl = url,
            pages = pages.sortedBy { it.depth },
            totalLinks = allLinks.get(),
            elapsedMs = System.currentTimeMillis() - startTime
        )
    }

    private fun scrapePage(url: String, depth: Int, rootDomain: String): ScrapedPage {
        return try {
            val doc: Document = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 MetaScrub/1.0")
                .timeout(timeoutMs)
                .followRedirects(true)
                .maxBodySize(5 * 1024 * 1024) // 5MB max
                .get()

            val title = doc.title().ifBlank { url }

            // Extract meta
            val meta = mutableMapOf<String, String>()
            doc.select("meta[name=description]").attr("content").let {
                if (it.isNotBlank()) meta["Description"] = it
            }
            doc.select("meta[name=author]").attr("content").let {
                if (it.isNotBlank()) meta["Author"] = it
            }
            doc.select("meta[property=og:description]").attr("content").let {
                if (it.isNotBlank() && !meta.containsKey("Description")) meta["Description"] = it
            }
            doc.select("meta[name=keywords]").attr("content").let {
                if (it.isNotBlank()) meta["Keywords"] = it
            }
            doc.select("time[datetime]").first()?.attr("datetime")?.let {
                if (it.isNotBlank()) meta["Date"] = it
            }

            // Remove noise elements
            doc.select("script, style, nav, footer, header, aside, iframe, noscript, .ads, .cookie-banner, #cookie-consent, .sidebar, .menu, .navigation").remove()

            // Extract clean text content
            val content = buildString {
                // Get headings and paragraphs with structure
                doc.body()?.let { body ->
                    for (element in body.select("h1, h2, h3, h4, h5, h6, p, li, td, th, pre, code, blockquote, figcaption, dt, dd")) {
                        val tag = element.tagName()
                        val text = element.ownText().trim()
                            .replace(Regex("\\s+"), " ")

                        if (text.isBlank()) continue

                        when (tag) {
                            "h1" -> appendLine("\n# $text\n")
                            "h2" -> appendLine("\n## $text\n")
                            "h3" -> appendLine("\n### $text\n")
                            "h4", "h5", "h6" -> appendLine("\n#### $text\n")
                            "li" -> appendLine("  - $text")
                            "pre", "code" -> {
                                appendLine("```")
                                appendLine(element.text())
                                appendLine("```")
                            }
                            "blockquote" -> appendLine("> $text")
                            "th" -> append("| $text ")
                            "td" -> append("| $text ")
                            else -> {
                                if (text.length > 2) appendLine(text)
                            }
                        }
                    }
                }
            }.trim()

            // Extract links
            val links = doc.select("a[href]")
                .map { it.absUrl("href") }
                .filter { it.startsWith("http") }
                .distinct()

            allLinks.addAndGet(links.size)

            ScrapedPage(
                url = url,
                title = title,
                depth = depth,
                content = if (content.isNotBlank()) content else doc.body()?.text()?.take(10_000) ?: "(empty page)",
                links = links,
                meta = meta
            )
        } catch (e: Exception) {
            ScrapedPage(
                url = url,
                title = url,
                depth = depth,
                content = "",
                links = emptyList(),
                meta = emptyMap(),
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun normalizeUrl(url: String): String {
        return url.trimEnd('/')
            .replace(Regex("#.*$"), "")
            .replace(Regex("\\?utm_.*$"), "")
    }

    private fun extractDomain(url: String): String {
        return try {
            URI(url).host?.removePrefix("www.") ?: ""
        } catch (_: Exception) { "" }
    }

    private fun isScrapableUrl(url: String): Boolean {
        val lower = url.lowercase()
        return !lower.endsWith(".pdf") &&
                !lower.endsWith(".jpg") &&
                !lower.endsWith(".jpeg") &&
                !lower.endsWith(".png") &&
                !lower.endsWith(".gif") &&
                !lower.endsWith(".svg") &&
                !lower.endsWith(".mp4") &&
                !lower.endsWith(".mp3") &&
                !lower.endsWith(".zip") &&
                !lower.endsWith(".exe") &&
                !lower.contains("javascript:") &&
                !lower.contains("mailto:") &&
                !lower.contains("tel:") &&
                !lower.contains("/login") &&
                !lower.contains("/signup") &&
                !lower.contains("/auth")
    }
}
