package com.vn2c

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Vn2cProvider : MainAPI() {

    override var mainUrl = "https://www.vn2c.my"
    override var name = "PhimVN2 (Bản Tối Thượng)"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val itemSelector = "div.Form2, div.boxtk"
    private val posterSelector = "img.c10, div.boxtk_img img, img"
    private val episodeSelector = "div.num_film a"
    private val descriptionSelector = "div.wiew_info p, div.info-film, .content-film"

    private fun headers(referer: String): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi-vn2-phim-2vn-phim/new" to "Phim Mới",
        "$mainUrl/danh-muc/trung-quoc-7" to "Phim Trung Quốc",
        "$mainUrl/danh-muc/han-quoc-10" to "Phim Hàn Quốc",
        "$mainUrl/danh-muc/thai-lan-8" to "Phim Thái Lan",
        "$mainUrl/the-loai2/hoat-hinh-anime-29" to "Hoạt Hình Anime"
    )

    private fun String.toSlug(): String {
        var text = trim().lowercase()
        val groups = listOf(
            "áàảãạăắằẳẵặâấầẩẫậ" to "a", "éèẻẽẹêếềểễệ" to "e", "íìỉĩị" to "i",
            "óòỏõọôốồổỗộơớờởỡợ" to "o", "úùủũụưứừửữự" to "u", "ýỳỷỹỵ" to "y", "đ" to "d"
        )
        for ((characters, replacement) in groups) {
            for (character in characters) {
                text = text.replace(character.toString(), replacement)
            }
        }
        return text.replace(Regex("[^a-z0-9]+"), "-").replace(Regex("-+"), "-").trim('-')
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val pageUrl = if (page <= 1) request.data else "${request.data}?page=$page"
            val document = app.get(pageUrl, headers = headers(mainUrl)).document
            val results = document.select(itemSelector).mapNotNull { it.toSearchResult() }
            newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val slug = query.toSlug()
            if (slug.isBlank()) return emptyList()
            val document = app.get("$mainUrl/tim-kiem/$slug", headers = headers(mainUrl)).document
            document.select(itemSelector).mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a[href]") ?: return null
        var title = selectFirst("p.nametk a")?.text()?.trim().orEmpty()
        if (title.isBlank()) title = linkElement.attr("title").trim()
        if (title.isBlank()) title = linkElement.text().trim()
        val href = linkElement.attr("href").trim()
        if (title.isBlank() || href.isBlank()) return null
        var poster: String? = null
        val posterElement = selectFirst(posterSelector)
        if (posterElement != null) {
            poster = posterElement.attr("data-src").ifBlank { posterElement.attr("data-original") }.ifBlank { posterElement.attr("src") }
        }
        return newMovieSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = app.get(url, headers = headers(mainUrl)).document
            val title = document.selectFirst("h1, .box_film_title, .title, h2")?.text()?.trim() ?: "Không tên"
            val posterElement = document.selectFirst(posterSelector)
            val poster = posterElement?.attr("data-src")?.ifBlank { posterElement.attr("data-original") }?.ifBlank { posterElement.attr("src") }
            val plot = document.selectFirst(descriptionSelector)?.text()?.trim()
            val episodes = mutableListOf<Episode>()

            document.select(episodeSelector).forEachIndexed { index, element ->
                val episodeUrl = element.attr("href").trim()
                if (episodeUrl.isNotBlank()) episodes.add(newEpisode(fixUrl(episodeUrl)) { name = element.text().trim().ifBlank { "Tập ${index + 1}" } })
            }

            if (episodes.isEmpty()) {
                document.select("a[href]").mapNotNull {
                    val href = it.attr("href").trim()
                    if (href.contains("-tap-", true) || href.contains("-sub1-", true) || href.contains("-sub2-", true)) href to it.text().trim() else null
                }.distinctBy { it.first }.forEachIndexed { index, pair ->
                    episodes.add(newEpisode(fixUrl(pair.first)) { name = pair.second.ifBlank { "Tập ${index + 1}" } })
                }
            }

            if (episodes.isEmpty()) episodes.add(newEpisode(url) { name = "Full" })

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = fixUrlNull(poster)
                this.plot = plot
            }
        } catch (e: Exception) { null }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val episodeUrl = data.trim()
        if (episodeUrl.isBlank()) throw IllegalStateException("VN2 DEBUG\nSTEP=0\nDATA=EMPTY")

        val foundLinks = mutableSetOf<String>()
        var playerCount = 0
        val debugPlayers = mutableListOf<String>()

        suspend fun add(url: String, server: String, referer: String) {
            val clean = cleanUrl(url)
            if (!isVideoUrl(clean)) return
            if (!foundLinks.add(clean)) return
            addVideoLink(clean, server, referer, callback)
            debugPlayers.add("FOUND: $server -> $clean")
        }

        try {
            val html = app.get(episodeUrl, headers = headers(mainUrl)).text

            // BƯỚC 1: QUÉT SẠCH MỌI IFRAME & PLAYER TRONG HTML
            val playerUrls = extractPlayerUrls(html, episodeUrl)
            playerCount = playerUrls.size

            playerUrls.forEachIndexed { index, playerUrl ->
                val serverName = "Server ${index + 1}"
                val playerLinks = fetchPlayerLinks(playerUrl, serverName, episodeUrl)
                for (item in playerLinks) add(item.url, item.name, item.referer)
            }

            // BƯỚC 2: QUÉT TRỰC TIẾP MP4/M3U8 TRONG HTML GỐC
            if (foundLinks.isEmpty()) {
                extractVideoUrls(html).forEachIndexed { index, videoUrl ->
                    add(videoUrl, "Direct ${index + 1}", episodeUrl)
                }
            }

            // NẾU TÌM THẤY BẤT KỲ LINK NÀO THÌ TRẢ VỀ THÀNH CÔNG
            if (foundLinks.isNotEmpty()) return true

            // LOG NẾU KHÔNG TÌM THẤY (Giúp bạn kiểm tra nếu vẫn lỗi)
            throw IllegalStateException(
                "VN2 DEBUG\n" +
                        "URL=$episodeUrl\n" +
                        "HTML=${html.length}\n" +
                        "PLAYER_COUNT=$playerCount\n" +
                        "EXTRACTED_PLAYERS=${playerUrls.joinToString(" | ")}\n"
            )
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("VN2 ERROR: ${e.message}")
        }
    }

    private data class VideoResult(val url: String, val name: String, val referer: String)

    private suspend fun fetchPlayerLinks(playerUrl: String, serverName: String, referer: String): List<VideoResult> {
        val result = mutableListOf<VideoResult>()

        // Nếu playerUrl đã là link CDN MP4 thì ăn luôn
        if (isVideoUrl(playerUrl)) {
            result.add(VideoResult(playerUrl, "$serverName VIP", referer))
            return result
        }

        try {
            val html = app.get(playerUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to referer)).text

            val sd = extractJsVariable(html, "link_video_sd")
            val hd = extractJsVariable(html, "link_video_hd")

            if (!sd.isNullOrBlank()) result.add(VideoResult(sd, "$serverName - SD", playerUrl))
            if (!hd.isNullOrBlank() && hd != sd) result.add(VideoResult(hd, "$serverName - FHD", playerUrl))

            if (result.isEmpty()) {
                extractVideoUrls(html).forEach { url ->
                    result.add(VideoResult(url, serverName, playerUrl))
                }
            }
        } catch (e: Exception) {}

        return result.distinctBy { it.url }
    }

    private suspend fun addVideoLink(url: String, serverName: String, referer: String, callback: (ExtractorLink) -> Unit) {
        if (!url.startsWith("http")) return

        // MẸO: Ép đuôi .mp4 để lừa Cloudstream không ném lỗi 3003
        var finalUrl = url
        if (!finalUrl.contains(".mp4", true) && !finalUrl.contains(".m3u8", true)) {
            finalUrl += "#.mp4"
        }

        val quality = when {
            serverName.contains("1080", true) || serverName.contains("FHD", true) -> 1080
            serverName.contains("720", true) || serverName.contains("HD", true) -> 720
            serverName.contains("SD", true) -> 480
            else -> 0
        }

        try {
            callback(
                newExtractorLink(
                    source = name,
                    name = serverName,
                    url = finalUrl,
                    type = if (finalUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = quality
                }
            )
        } catch (e: Exception) {}
    }

    // CỖ MÁY QUÉT MỌI ĐƯỜNG LINK NHÚNG (Bất chấp web đổi cấu trúc 9x hay 10x)
    private fun extractPlayerUrls(html: String, pageUrl: String): List<String> {
        val result = mutableListOf<String>()

        // 1. Quét thẳng thẻ iframe (cách cơ bản nhất)
        val iframeRegex = Regex("""<iframe[^>]+(?:src|data-src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        iframeRegex.findAll(html).forEach { match ->
            val url = cleanUrl(match.groupValues[1])
            if (url.isNotBlank()) result.add(absoluteUrl(url, pageUrl))
        }

        // 2. Quét mọi biến/chuỗi string chứa link play.php hoặc vn2data
        val stringRegex = Regex("""["'](https?://[^"']*(?:play(?:2)?\.php|vn2data|cloudcdnvn)[^"']*)["']""", RegexOption.IGNORE_CASE)
        stringRegex.findAll(html).forEach { match ->
            result.add(absoluteUrl(cleanUrl(match.groupValues[1]), pageUrl))
        }

        return result.map { it.replace("&amp;", "&") }
            .filter { it.contains("play", true) || it.contains("vn2data", true) || it.contains("cloudcdnvn", true) }
            .distinct()
    }

    // ĐÃ MỞ RỘNG BỘ LỌC ĐỂ NHẬN DIỆN CẢ SERVER CHỨA PHIM
    private fun extractVideoUrls(html: String): List<String> {
        val results = mutableListOf<String>()
        val regex = Regex("""https?://[^"'\\s<>]+(?:\.mp4|\.m3u8|cloudcdnvn|scontent|cdninstagram)[^"'\\s<>]*""", RegexOption.IGNORE_CASE)
        regex.findAll(html).forEach { match ->
            val url = cleanUrl(match.value)
            if (isVideoUrl(url)) results.add(url)
        }
        return results.distinct()
    }

    private fun isVideoUrl(url: String): Boolean {
        return url.contains(".mp4", true) || url.contains(".m3u8", true) ||
                url.contains("cloudcdnvn", true) || url.contains("scontent", true) ||
                url.contains("cdninstagram", true)
    }

    private fun extractJsVariable(html: String, variable: String): String? {
        val regex = Regex("""(?:var\s+)?$variable\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.startsWith("http") }
    }

    private fun cleanUrl(value: String): String {
        return value.trim().trim('"', '\'').replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").removeSuffix(";")
    }

    private fun absoluteUrl(value: String, baseUrl: String): String {
        val url = cleanUrl(value)
        if (url.startsWith("http")) return url
        return try {
            if (url.startsWith("//")) "https:$url"
            else if (url.startsWith("/")) baseUrl.substringBefore("://") + "://" + baseUrl.substringAfter("://").substringBefore("/") + url
            else baseUrl.substringBeforeLast("/") + "/" + url
        } catch (e: Exception) { url }
    }
}