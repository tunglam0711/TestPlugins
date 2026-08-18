package com.vn2c

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Vn2cProvider : MainAPI() {

    override var mainUrl = "https://www.vn2c.my"
    override var name = "PhimVN2"
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
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/151.0.0.0 Safari/537.36"

    private val itemSelector = "div.Form2, div.boxtk"
    private val posterSelector = "img.c10, div.boxtk_img img, img"
    private val episodeSelector = "div.num_film a"
    private val descriptionSelector = "div.wiew_info p, div.info-film, .content-film"

    private fun headers(referer: String): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Connection" to "keep-alive"
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
            "áàảãạăắằẳẵặâấầẩẫậ" to "a",
            "éèẻẽẹêếềểễệ" to "e",
            "íìỉĩị" to "i",
            "óòỏõọôốồổỗộơớờởỡợ" to "o",
            "úùủũụưứừửữự" to "u",
            "ýỳỷỹỵ" to "y",
            "đ" to "d"
        )

        for ((characters, replacement) in groups) {
            for (character in characters) {
                text = text.replace(character.toString(), replacement)
            }
        }

        return text
            .replace(Regex("[^a-z0-9]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return try {
            val pageUrl = if (page <= 1) {
                request.data
            } else {
                "${request.data}?page=$page"
            }

            val document = app.get(
                pageUrl,
                headers = headers(mainUrl)
            ).document

            val results = document
                .select(itemSelector)
                .mapNotNull { it.toSearchResult() }

            newHomePageResponse(
                request.name,
                results,
                hasNext = results.isNotEmpty()
            )
        } catch (e: Exception) {
            println("VN2 MAIN ERROR: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val slug = query.toSlug()
            if (slug.isBlank()) return emptyList()

            val searchUrl = "$mainUrl/tim-kiem/$slug"

            val document = app.get(
                searchUrl,
                headers = headers(mainUrl)
            ).document

            document
                .select(itemSelector)
                .mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            println("VN2 SEARCH ERROR: ${e.message}")
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
            val dataSrc = posterElement.attr("data-src").trim()
            val dataOriginal = posterElement.attr("data-original").trim()
            val src = posterElement.attr("src").trim()

            poster = when {
                dataSrc.isNotBlank() -> dataSrc
                dataOriginal.isNotBlank() -> dataOriginal
                src.isNotBlank() -> src
                else -> null
            }
        }

        return newMovieSearchResponse(
            title,
            fixUrl(href),
            TvType.TvSeries
        ) {
            posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val response = app.get(url, headers = headers(mainUrl))
            val document = response.document

            val title = document
                .selectFirst("h1, .box_film_title, .title, h2")
                ?.text()
                ?.trim()
                ?.ifBlank { "Không tên" }
                ?: "Không tên"

            val posterElement = document.selectFirst(posterSelector)
            var poster: String? = null

            if (posterElement != null) {
                val dataSrc = posterElement.attr("data-src").trim()
                val dataOriginal = posterElement.attr("data-original").trim()
                val src = posterElement.attr("src").trim()

                poster = when {
                    dataSrc.isNotBlank() -> dataSrc
                    dataOriginal.isNotBlank() -> dataOriginal
                    src.isNotBlank() -> src
                    else -> null
                }
            }

            val plot = document
                .selectFirst(descriptionSelector)
                ?.text()
                ?.trim()

            val episodes = mutableListOf<Episode>()

            document.select(episodeSelector).forEachIndexed { index, element ->
                val episodeUrl = element.attr("href").trim()
                if (episodeUrl.isBlank()) return@forEachIndexed

                val episodeName = element.text().trim()
                    .ifBlank { "Tập ${index + 1}" }

                episodes.add(
                    newEpisode(fixUrl(episodeUrl)) {
                        name = episodeName
                    }
                )
            }

            /*
             * Một số trang VN2 không dùng div.num_film.
             * Fallback: lấy các link tập có dạng tap-/sub1/sub2.
             */
            if (episodes.isEmpty()) {
                val fallback = document
                    .select("a[href]")
                    .mapNotNull { element ->
                        val href = element.attr("href").trim()
                        if (
                            href.contains("-tap-", true) ||
                            href.contains("-sub1-", true) ||
                            href.contains("-sub2-", true)
                        ) {
                            href to element.text().trim()
                        } else {
                            null
                        }
                    }
                    .distinctBy { it.first }

                fallback.forEachIndexed { index, pair ->
                    episodes.add(
                        newEpisode(fixUrl(pair.first)) {
                            name = pair.second.ifBlank { "Tập ${index + 1}" }
                        }
                    )
                }
            }

            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode(url) {
                        name = "Full"
                    }
                )
            }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = fixUrlNull(poster)
                this.plot = plot
            }
        } catch (e: Exception) {
            println("VN2 LOAD ERROR: ${e.message}")
            null
        }
    }

    /*
     * ============================================================
     * LOAD LINKS
     * ============================================================
     *
     * Luồng:
     *
     * 1. data = URL trang tập phim.
     * 2. Mở trang tập phim.
     * 3. Tìm iframe / play.php.
     * 4. Mở play.php.
     * 5. Đọc link_video_sd / link_video_hd.
     * 6. Nếu không có thì quét MP4/M3U8 trực tiếp.
     *
     * Đây là phần quan trọng nhất để sửa lỗi "No links found".
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = data.trim()

        if (episodeUrl.isBlank()) {
            throw IllegalStateException("VN2 DEBUG\nSTEP=0\nDATA=EMPTY")
        }

        val foundLinks = mutableSetOf<String>()
        var playerCount = 0
        var directCount = 0
        var generatedCount = 0
        val debugPlayers = mutableListOf<String>()
        val debugVideos = mutableListOf<String>()

        suspend fun add(url: String, server: String, referer: String) {
            val clean = cleanUrl(url)
            if (!isVideoUrl(clean)) return
            if (!foundLinks.add(clean)) return

            addVideoLink(
                url = clean,
                serverName = server,
                referer = referer,
                callback = callback
            )

            debugVideos.add("$server=$clean")
        }

        try {
            // ---------------------------------------------------------
            // STEP 1: nếu data đã là play.php thì đọc thẳng player
            // ---------------------------------------------------------
            if (episodeUrl.contains("/play/js_fix/", true)) {
                val playerLinks = fetchPlayerLinks(
                    playerUrl = episodeUrl,
                    serverName = "Server 1",
                    referer = episodeUrl.substringBefore("/play/")
                )

                playerCount++
                debugPlayers.add(episodeUrl)

                for (item in playerLinks) {
                    add(item.url, item.name, item.referer)
                }

                if (foundLinks.isNotEmpty()) {
                    return true
                }
            }

            // ---------------------------------------------------------
            // STEP 2: mở trang tập
            // ---------------------------------------------------------
            val episodeResponse = app.get(
                episodeUrl,
                headers = headers(mainUrl)
            )

            val html = episodeResponse.text

            // ---------------------------------------------------------
            // STEP 3: tìm play.php / play2.php
            // ---------------------------------------------------------
            val playerUrls = extractPlayerUrls(
                html = html,
                pageUrl = episodeUrl
            )

            playerCount = playerUrls.size
            debugPlayers.addAll(playerUrls)

            playerUrls.forEachIndexed { index, playerUrl ->
                val serverName = if (playerUrl.contains("play2.php", true)) {
                    "Server 2"
                } else {
                    "Server ${index + 1}"
                }

                val playerLinks = fetchPlayerLinks(
                    playerUrl = playerUrl,
                    serverName = serverName,
                    referer = episodeUrl
                )

                for (item in playerLinks) {
                    add(item.url, item.name, item.referer)
                }
            }

            if (foundLinks.isNotEmpty()) {
                return true
            }

            // ---------------------------------------------------------
            // STEP 4: fallback tìm MP4/M3U8 ngay trong trang tập
            // ---------------------------------------------------------
            val directUrls = extractVideoUrls(html)
            directCount = directUrls.size

            directUrls.forEachIndexed { index, videoUrl ->
                add(
                    videoUrl,
                    "Direct Server ${index + 1}",
                    episodeUrl
                )
            }

            if (foundLinks.isNotEmpty()) {
                return true
            }

            // ---------------------------------------------------------
            // STEP 5: fallback dựng play.php từ HTML/JS VN2
            // ---------------------------------------------------------
            val generatedPlayers = buildGeneratedPlayerUrls(
                html = html,
                episodeUrl = episodeUrl
            )

            generatedCount = generatedPlayers.size

            generatedPlayers.forEachIndexed { index, playerUrl ->
                if (!debugPlayers.contains(playerUrl)) {
                    debugPlayers.add(playerUrl)
                }

                val playerLinks = fetchPlayerLinks(
                    playerUrl = playerUrl,
                    serverName = "Generated Server ${index + 1}",
                    referer = episodeUrl
                )

                for (item in playerLinks) {
                    add(item.url, item.name, item.referer)
                }
            }

            if (foundLinks.isNotEmpty()) {
                return true
            }

            // ---------------------------------------------------------
            // Không tìm thấy link -> THROW để Provider Test hiện log
            // ---------------------------------------------------------
            val playerInfo = if (debugPlayers.isEmpty()) {
                "NONE"
            } else {
                debugPlayers.joinToString("\n")
            }

            val htmlPreview = html
                .replace(Regex("\\s+"), " ")
                .take(1000)

            throw IllegalStateException(
                "VN2 DEBUG\n" +
                        "STEP=FINAL\n" +
                        "URL=$episodeUrl\n" +
                        "HTML=${html.length}\n" +
                        "PLAYER_COUNT=$playerCount\n" +
                        "DIRECT_COUNT=$directCount\n" +
                        "GENERATED_COUNT=$generatedCount\n" +
                        "FOUND=0\n" +
                        "PLAYER_URLS=$playerInfo\n" +
                        "HTML_PREVIEW=$htmlPreview"
            )
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "VN2 DEBUG\n" +
                        "STEP=EXCEPTION\n" +
                        "URL=$episodeUrl\n" +
                        "ERROR=${e.javaClass.simpleName}: ${e.message ?: "unknown"}\n" +
                        "PLAYER_COUNT=$playerCount\n" +
                        "DIRECT_COUNT=$directCount\n" +
                        "GENERATED_COUNT=$generatedCount\n" +
                        "FOUND=${foundLinks.size}\n" +
                        "VIDEOS=${debugVideos.joinToString(" | ")}"
            )
        }
    }

    /*
     * Kết quả của một lần đọc play.php.
     */
    private data class VideoResult(
        val url: String,
        val name: String,
        val referer: String
    )

    /*
     * Đọc play.php.
     *
     * VN2 trả về JS kiểu:
     *
     * var link_video_sd = "https://...mp4";
     * var link_video_hd = "";
     */
    private suspend fun fetchPlayerLinks(
        playerUrl: String,
        serverName: String,
        referer: String
    ): List<VideoResult> {
        val result = mutableListOf<VideoResult>()

        try {
            println("VN2 PLAYER REQUEST = $playerUrl")

            val response = app.get(
                playerUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to referer,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache"
                )
            )

            val html = response.text

            println("VN2 PLAYER HTML LENGTH = ${html.length}")

            val sd = extractJsVariable(html, "link_video_sd")
            val hd = extractJsVariable(html, "link_video_hd")

            println("VN2 SD = $sd")
            println("VN2 HD = $hd")

            if (!sd.isNullOrBlank()) {
                result.add(
                    VideoResult(
                        sd,
                        "$serverName - SD",
                        "https://vn2data.com/"
                    )
                )
            }

            if (!hd.isNullOrBlank() && hd != sd) {
                result.add(
                    VideoResult(
                        hd,
                        "$serverName - FHD",
                        "https://vn2data.com/"
                    )
                )
            }

            /*
             * Nếu biến JS không có, quét toàn bộ response.
             */
            if (result.isEmpty()) {
                extractVideoUrls(html).forEach { url ->
                    result.add(
                        VideoResult(
                            url,
                            serverName,
                            "https://vn2data.com/"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            println("VN2 PLAYER ERROR: ${e.message}")
        }

        return result.distinctBy { it.url }
    }

    private suspend fun addVideoLink(
        url: String,
        serverName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (
            !url.startsWith("http://", true) &&
            !url.startsWith("https://", true)
        ) {
            return
        }

        val quality = when {
            serverName.contains("1080", true) -> 1080
            serverName.contains("FHD", true) -> 1080
            serverName.contains("720", true) -> 720
            serverName.contains("HD", true) -> 720
            serverName.contains("SD", true) -> 480
            else -> 0
        }

        val linkType =
            if (url.contains(".m3u8", true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

        try {
            callback(
                newExtractorLink(
                    source = name,
                    name = serverName,
                    url = url,
                    type = linkType
                ) {
                    this.referer = referer
                    this.quality = quality
                }
            )
        } catch (e: Exception) {
            println("VN2 ADD LINK ERROR: ${e.message}")
        }
    }

    /*
     * Tìm play.php / play2.php.
     */
    private fun extractPlayerUrls(
        html: String,
        pageUrl: String
    ): List<String> {
        val result = mutableListOf<String>()

        val patterns = listOf(
            Regex(
                """https?://[^"'<>\\s]+/play/js_fix/9x/play(?:2)?\.php\?link=[^"'<>\\s]+""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """(?:src|href)\s*=\s*["']([^"']*play/js_fix/9x/play(?:2)?\.php\?link=[^"']+)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """["']([^"']*play/js_fix/9x/play(?:2)?\.php\?link=[^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
        )

        for (regex in patterns) {
            for (match in regex.findAll(html)) {
                val value = match.groupValues.lastOrNull()?.trim()
                    ?: continue

                val fixed = absoluteUrl(
                    cleanUrl(value),
                    pageUrl
                )

                if (
                    fixed.contains("/play/js_fix/", true) &&
                    fixed.contains("play.php", true)
                ) {
                    result.add(fixed)
                }
            }
        }

        /*
         * HTML có thể escape dấu / thành \/.
         */
        val escaped = Regex(
            """https?:\\?/\\?/[^"'<>\\s]+/play/js_fix/9x/play(?:2)?\.php\?link=[^"'<>\\s]+""",
            RegexOption.IGNORE_CASE
        )

        for (match in escaped.findAll(html)) {
            result.add(
                absoluteUrl(
                    cleanUrl(match.value),
                    pageUrl
                )
            )
        }

        return result
            .map { it.replace("&amp;", "&") }
            .distinct()
    }

    /*
     * VN2 có thể tạo play.php từ JavaScript.
     *
     * Hàm này đọc những biến quan trọng nếu chúng xuất hiện
     * trong HTML. Không tìm thấy thì trả emptyList().
     */
    private fun buildGeneratedPlayerUrls(
        html: String,
        episodeUrl: String
    ): List<String> {
        val result = mutableListOf<String>()

        val nameTap = jsVar(html, "name_tapphim") ?: return emptyList()
        val nameId = jsVar(html, "name_fixid") ?: return emptyList()
        val totalView = jsVar(html, "totalview") ?: return emptyList()
        val channelLink = jsVar(html, "channel_link") ?: return emptyList()

        /*
         * numchecks:
         * Trang VN2 thường truyền 1 trên desktop/Chrome.
         * Trên mobile, nếu HTML đã có biến thì ưu tiên biến đó.
         */
        val numChecks = jsVar(html, "numchecks")
            ?: jsVar(html, "numcheck")
            ?: "1"

        val channelFix = jsVar(html, "channel_fix")
            ?: channelLink

        val nameFixLoad = jsVar(html, "name_fixload").orEmpty()
        val nameFixFull2 = jsVar(html, "name_fixfull2").orEmpty()
        val channelFixIframe = jsVar(html, "channel_fix_iframe").orEmpty()
        val nameFixFull = jsVar(html, "name_fixfull").orEmpty()
        val channelUrlFix = jsVar(html, "channel_url_fix").orEmpty()
        val channelStream3 = jsVar(html, "channel_stream_3").orEmpty()
        val channelStream5 = jsVar(html, "channel_stream_5").orEmpty()
        val channelStream9 = jsVar(html, "channel_stream_9").orEmpty()
        val channel10 = jsVar(html, "channel10").orEmpty()
        val nameNumGet = jsVar(html, "name_numget").orEmpty()
        val nameTitle = jsVar(html, "name_title").orEmpty()

        val numServer = when {
            episodeUrl.contains("-sub1-", true) -> {
                if (nameFixFull.contains("fix-to111", true)) "111" else "333"
            }

            episodeUrl.contains("-sub2-", true) -> {
                if (nameFixFull.contains("fix-to444", true)) "444" else "333"
            }

            episodeUrl.contains("-tm2", true) -> {
                if (nameFixFull.contains("fix-to222", true)) "222" else "111"
            }

            else -> "111"
        }

        val idPlay =
            "${cleanText(nameTap)}sv$numServer" +
                    "id${cleanText(nameId)}" +
                    "numview${cleanText(totalView)}" +
                    "chankey${cleanText(numChecks)}" +
                    channelFix

        /*
         * Dựa theo channel_videoembed của VN2:
         *
         * /js_fix/9x/play.php?link=
         *   idplay
         *   vn2fix1...
         */
        val contentLink1 = channelFix
        val contentLink2 = channelUrlFix
        val fixLoad3 =
            if (nameFixFull.contains("fullhd", true)) {
                nameFixFull
            } else {
                channelStream3
            }

        val fixLoad4 = when {
            channelStream9.contains(".m3u8", true) -> channelStream9
            channel10.contains("fbsv", true) -> channel10
            else -> ""
        }

        val domain = "https://vn2data.com"

        val generated =
            domain +
                    "/play/js_fix/9x/play.php?link=" +
                    idPlay +
                    "vn2fix1" + contentLink1 +
                    "vn2fix2" + contentLink2 +
                    "vn2fix3" + channelFixIframe +
                    "vn2fixload" + fixLoad3 +
                    "vn2fixurl4" + fixLoad4 +
                    "vn2fixurl5" + "" +
                    "6O548l721190" +
                    "cookiecat" + channelStream5 +
                    "vn2myname" + nameFixLoad +
                    "folderfix" + nameNumGet +
                    "catnumget" + nameFixFull2 +
                    episodeUrl

        if (generated.contains("play.php?link=", true)) {
            result.add(generated)
        }

        /*
         * Một số bản dùng play2.php làm fallback.
         */
        if (nameTitle.isNotBlank()) {
            val play2 = generated.replace(
                "/play.php?",
                "/play2.php?"
            )
            result.add(play2)
        }

        return result.distinct()
    }

    private fun jsVar(
        html: String,
        variable: String
    ): String? {
        val regex = Regex(
            """(?:var|let|const)?\s*$variable\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        )

        return regex.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractJsVariable(
        html: String,
        variable: String
    ): String? {
        val regex = Regex(
            """(?:var\s+)?$variable\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        )

        return regex.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf {
                it.startsWith("http://", true) ||
                        it.startsWith("https://", true)
            }
    }

    private fun extractVideoUrls(
        html: String
    ): List<String> {
        val results = mutableListOf<String>()

        val normalRegex = Regex(
            """https?://[^"'\\s<>]+(?:\.mp4|\.m3u8)(?:\?[^"'\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        normalRegex.findAll(html).forEach { match ->
            val url = cleanUrl(match.value)
            if (isVideoUrl(url)) {
                results.add(url)
            }
        }

        val escapedRegex = Regex(
            """https?:\\?/\\?/[^"'\\s<>]+(?:\.mp4|\.m3u8)(?:\?[^"'\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        escapedRegex.findAll(html).forEach { match ->
            val url = cleanUrl(match.value)
            if (isVideoUrl(url)) {
                results.add(url)
            }
        }

        return results.distinct()
    }

    private fun isVideoUrl(url: String): Boolean {
        return url.contains(".mp4", true) ||
                url.contains(".m3u8", true)
    }

    private fun cleanUrl(value: String): String {
        return value
            .trim()
            .trim('"', '\'')
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\\"", "\"")
            .removeSuffix(";")
    }

    private fun cleanText(value: String): String {
        return value
            .replace("\r", "")
            .replace("\n", "")
            .trim()
    }

    private fun absoluteUrl(
        value: String,
        baseUrl: String
    ): String {
        val url = cleanUrl(value)

        if (url.startsWith("http://", true) ||
            url.startsWith("https://", true)
        ) {
            return url
        }

        return try {
            if (url.startsWith("//")) {
                "https:$url"
            } else if (url.startsWith("/")) {
                val base = baseUrl.substringBefore("://") +
                        "://" +
                        baseUrl.substringAfter("://").substringBefore("/")
                "$base$url"
            } else {
                baseUrl.substringBeforeLast("/") + "/" + url
            }
        } catch (_: Exception) {
            url
        }
    }
}
