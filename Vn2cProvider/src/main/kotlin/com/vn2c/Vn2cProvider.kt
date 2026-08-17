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

    // =========================================================
    // HTTP
    // =========================================================

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"

    private val requestHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    // =========================================================
    // MAIN PAGE
    // =========================================================

    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi-hay-nhieu-yeu-thich/phimmoi.aspx" to "Phim Mới",
        "$mainUrl/danh-muc/trung-quoc-7" to "Phim Trung Quốc",
        "$mainUrl/danh-muc/han-quoc-10" to "Phim Hàn Quốc",
        "$mainUrl/danh-muc/thai-lan-8" to "Phim Thái Lan",
        "$mainUrl/the-loai2/hoat-hinh-anime-29" to "Hoạt Hình Anime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url: String

        if (page <= 1) {
            url = request.data
        } else {
            url = request.data + "?page=" + page
        }

        println("VN2 MAIN URL = $url")

        return try {

            val response = app.get(
                url,
                headers = requestHeaders,
                referer = mainUrl
            )

            val document = response.document

            val results = ArrayList<SearchResponse>()

            val elements = document.select("a[href*='/xem/']")

            for (element in elements) {

                val result = element.toSearchResult()

                if (result != null) {
                    results.add(result)
                }
            }

            val uniqueResults = results.distinctBy { it.url }

            println(
                "VN2 MAIN RESULTS = ${uniqueResults.size}"
            )

            newHomePageResponse(
                request.name,
                uniqueResults
            )

        } catch (e: Exception) {

            println(
                "VN2 MAIN ERROR = ${e.message}"
            )

            newHomePageResponse(
                request.name,
                emptyList()
            )
        }
    }

    // =========================================================
    // SEARCH
    // =========================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        if (query.isBlank()) {
            return emptyList()
        }

        println("VN2 SEARCH QUERY = $query")

        val slug = makeSlug(query)

        println("VN2 SEARCH SLUG = $slug")

        /*
         * Endpoint search chính.
         */
        val searchUrl = "$mainUrl/tim-kiem/$slug"

        println("VN2 SEARCH URL = $searchUrl")

        try {

            val response = app.get(
                searchUrl,
                headers = requestHeaders,
                referer = mainUrl
            )

            val document = response.document

            val results = ArrayList<SearchResponse>()

            val elements = document.select(
                "a[href*='/xem/']"
            )

            for (element in elements) {

                val result = element.toSearchResult()

                if (result != null) {
                    results.add(result)
                }
            }

            val uniqueResults = results.distinctBy {
                it.url
            }

            println(
                "VN2 SEARCH RESULTS = ${uniqueResults.size}"
            )

            if (uniqueResults.isNotEmpty()) {
                return uniqueResults
            }

        } catch (e: Exception) {

            println(
                "VN2 SEARCH ERROR = ${e.message}"
            )
        }

        /*
         * Fallback:
         * thử endpoint search dạng query parameter.
         */
        try {

            val fallbackUrl =
                "$mainUrl/tim-kiem?keyword=$slug"

            println(
                "VN2 SEARCH FALLBACK URL = $fallbackUrl"
            )

            val response = app.get(
                fallbackUrl,
                headers = requestHeaders,
                referer = mainUrl
            )

            val document = response.document

            val results = ArrayList<SearchResponse>()

            val elements = document.select(
                "a[href*='/xem/']"
            )

            for (element in elements) {

                val result = element.toSearchResult()

                if (result != null) {
                    results.add(result)
                }
            }

            val uniqueResults = results.distinctBy {
                it.url
            }

            println(
                "VN2 SEARCH FALLBACK RESULTS = ${uniqueResults.size}"
            )

            if (uniqueResults.isNotEmpty()) {
                return uniqueResults
            }

        } catch (e: Exception) {

            println(
                "VN2 SEARCH FALLBACK ERROR = ${e.message}"
            )
        }

        /*
         * Fallback cuối:
         * lấy danh sách phim mới và lọc theo tên.
         */
        try {

            val latestUrl =
                "$mainUrl/phim-moi-hay-nhieu-yeu-thich/phimmoi.aspx"

            val response = app.get(
                latestUrl,
                headers = requestHeaders,
                referer = mainUrl
            )

            val document = response.document

            val results = ArrayList<SearchResponse>()

            val elements = document.select(
                "a[href*='/xem/']"
            )

            for (element in elements) {

                val result = element.toSearchResult()

                if (result != null) {

                    if (
                        result.name.contains(
                            query,
                            ignoreCase = true
                        )
                    ) {
                        results.add(result)
                    }
                }
            }

            val uniqueResults = results.distinctBy {
                it.url
            }

            println(
                "VN2 SEARCH LOCAL RESULTS = ${uniqueResults.size}"
            )

            return uniqueResults

        } catch (e: Exception) {

            println(
                "VN2 SEARCH LOCAL ERROR = ${e.message}"
            )

            return emptyList()
        }
    }

    // =========================================================
    // SEARCH RESULT PARSER
    // =========================================================

    private fun Element.toSearchResult(): SearchResponse? {

        val href = attr("href").trim()

        if (href.isBlank()) {
            return null
        }

        if (!href.contains("/xem/")) {
            return null
        }

        val absoluteUrl = makeAbsoluteUrl(href)

        // -----------------------------------------------------
        // TITLE
        // -----------------------------------------------------

        var title: String? = null

        val titleAttr = attr("title").trim()

        if (titleAttr.isNotBlank()) {
            title = titleAttr
        }

        if (title == null) {

            val dataTitle = attr("data-title").trim()

            if (dataTitle.isNotBlank()) {
                title = dataTitle
            }
        }

        if (title == null) {

            val img: Element? = selectFirst("img")

            if (img != null) {

                val alt = img.attr("alt").trim()

                if (alt.isNotBlank()) {
                    title = alt
                }
            }
        }

        if (title == null) {

            val titleElement: Element? =
                selectFirst(".title")

            if (titleElement != null) {

                val value = titleElement.text().trim()

                if (value.isNotBlank()) {
                    title = value
                }
            }
        }

        if (title == null) {

            val nameElement: Element? =
                selectFirst(".name")

            if (nameElement != null) {

                val value = nameElement.text().trim()

                if (value.isNotBlank()) {
                    title = value
                }
            }
        }

        if (title == null) {

            val nameTkElement: Element? =
                selectFirst(".nametk")

            if (nameTkElement != null) {

                val value = nameTkElement.text().trim()

                if (value.isNotBlank()) {
                    title = value
                }
            }
        }

        if (title == null) {

            val textValue = text().trim()

            if (textValue.isNotBlank()) {
                title = textValue
            }
        }

        if (title.isNullOrBlank()) {
            return null
        }

        if (title.length < 2) {
            return null
        }

        val lowerTitle = title.lowercase()

        if (lowerTitle == "xem thêm") {
            return null
        }

        if (lowerTitle == "xem phim") {
            return null
        }

        if (lowerTitle == "next") {
            return null
        }

        if (lowerTitle == "previous") {
            return null
        }

        // -----------------------------------------------------
        // POSTER
        // -----------------------------------------------------

        var poster: String? = null

        val posterElement: Element? =
            selectFirst("img")

        if (posterElement != null) {

            var posterValue =
                posterElement.attr("data-src").trim()

            if (posterValue.isBlank()) {

                posterValue =
                    posterElement
                        .attr("data-original")
                        .trim()
            }

            if (posterValue.isBlank()) {

                posterValue =
                    posterElement
                        .attr("src")
                        .trim()
            }

            if (posterValue.isNotBlank()) {
                poster = makeAbsoluteUrl(posterValue)
            }
        }

        return newMovieSearchResponse(
            title,
            absoluteUrl,
            TvType.TvSeries
        ) {

            if (poster != null) {
                posterUrl = poster
            }
        }
    }

    // =========================================================
    // LOAD MOVIE / SERIES
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        println("VN2 LOAD URL = $url")

        return try {

            val response = app.get(
                url,
                headers = requestHeaders,
                referer = mainUrl
            )

            val document = response.document

            // -------------------------------------------------
            // TITLE
            // -------------------------------------------------

            var title: String? = null

            val h1: Element? =
                document.selectFirst("h1")

            if (h1 != null) {

                val value = h1.text().trim()

                if (value.isNotBlank()) {
                    title = value
                }
            }

            if (title == null) {

                val boxTitle: Element? =
                    document.selectFirst(
                        ".box_film_title"
                    )

                if (boxTitle != null) {

                    val value =
                        boxTitle.text().trim()

                    if (value.isNotBlank()) {
                        title = value
                    }
                }
            }

            if (title == null) {

                val titleElement: Element? =
                    document.selectFirst(".title")

                if (titleElement != null) {

                    val value =
                        titleElement.text().trim()

                    if (value.isNotBlank()) {
                        title = value
                    }
                }
            }

            if (title == null) {

                val htmlTitle: Element? =
                    document.selectFirst("title")

                if (htmlTitle != null) {

                    val value =
                        htmlTitle.text().trim()

                    if (value.isNotBlank()) {
                        title = value
                    }
                }
            }

            if (title.isNullOrBlank()) {
                title = "Không tên"
            }

            // -------------------------------------------------
            // POSTER
            // -------------------------------------------------

            var poster: String? = null

            val posterElement: Element? =
                document.selectFirst(
                    "img.avatar, " +
                            "img.poster, " +
                            "img.c10, " +
                            ".info-film img, " +
                            ".film-info img"
                )

            if (posterElement != null) {

                var posterValue =
                    posterElement
                        .attr("data-src")
                        .trim()

                if (posterValue.isBlank()) {

                    posterValue =
                        posterElement
                            .attr("data-original")
                            .trim()
                }

                if (posterValue.isBlank()) {

                    posterValue =
                        posterElement
                            .attr("src")
                            .trim()
                }

                if (posterValue.isNotBlank()) {
                    poster = makeAbsoluteUrl(
                        posterValue
                    )
                }
            }

            // -------------------------------------------------
            // DESCRIPTION
            // -------------------------------------------------

            var plot: String? = null

            val descriptionElement: Element? =
                document.selectFirst(
                    ".wiew_info p, " +
                            ".info-film, " +
                            ".description, " +
                            ".content-film, " +
                            ".desc"
                )

            if (descriptionElement != null) {

                val value =
                    descriptionElement.text().trim()

                if (value.isNotBlank()) {
                    plot = value
                }
            }

            // -------------------------------------------------
            // EPISODES
            // -------------------------------------------------

            val episodes =
                ArrayList<Episode>()

            /*
             * Tìm link tập.
             */
            val episodeElements =
                document.select(
                    "a[href*='/xem/']"
                )

            for (element in episodeElements) {

                val href =
                    element.attr("href").trim()

                if (href.isBlank()) {
                    continue
                }

                val episodeUrl =
                    makeAbsoluteUrl(href)

                /*
                 * Không thêm chính URL trang phim
                 * thành episode.
                 */
                if (
                    episodeUrl.removeSuffix("/") ==
                    url.removeSuffix("/")
                ) {
                    continue
                }

                var episodeName =
                    element.text().trim()

                if (episodeName.isBlank()) {
                    episodeName = "Tập"
                }

                episodes.add(
                    newEpisode(
                        episodeUrl
                    ) {
                        name = episodeName
                    }
                )
            }

            // -------------------------------------------------
            // OLD EPISODE SELECTORS
            // -------------------------------------------------

            if (episodes.isEmpty()) {

                val oldEpisodeElements =
                    document.select(
                        ".num_film a, " +
                                ".list-episode a, " +
                                ".episode a, " +
                                ".episodes a"
                    )

                for (element in oldEpisodeElements) {

                    val href =
                        element.attr("href").trim()

                    if (href.isBlank()) {
                        continue
                    }

                    val episodeUrl =
                        makeAbsoluteUrl(href)

                    var episodeName =
                        element.text().trim()

                    if (episodeName.isBlank()) {
                        episodeName = "Tập"
                    }

                    episodes.add(
                        newEpisode(
                            episodeUrl
                        ) {
                            name = episodeName
                        }
                    )
                }
            }

            // -------------------------------------------------
            // PLAY BUTTON
            // -------------------------------------------------

            if (episodes.isEmpty()) {

                val playElement: Element? =
                    document.selectFirst(
                        "a.btn-play, " +
                                "div.playphim a, " +
                                ".play-btn a, " +
                                "a[href*='play']"
                    )

                if (playElement != null) {

                    val playHref =
                        playElement
                            .attr("href")
                            .trim()

                    if (playHref.isNotBlank()) {

                        episodes.add(
                            newEpisode(
                                makeAbsoluteUrl(playHref)
                            ) {
                                name = "Full"
                            }
                        )
                    }
                }
            }

            // -------------------------------------------------
            // FINAL FALLBACK
            // -------------------------------------------------

            if (episodes.isEmpty()) {

                episodes.add(
                    newEpisode(url) {
                        name = "Full"
                    }
                )
            }

            val uniqueEpisodes =
                episodes.distinctBy {
                    it.data
                }

            println(
                "VN2 TITLE = $title"
            )

            println(
                "VN2 EPISODES = ${uniqueEpisodes.size}"
            )

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                uniqueEpisodes
            ) {

                if (poster != null) {
                    posterUrl = poster
                }

                if (plot != null) {
                    this.plot = plot
                }
            }

        } catch (e: Exception) {

            println(
                "VN2 LOAD ERROR = ${e.message}"
            )

            null
        }
    }

    // =========================================================
    // LOAD LINKS
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        println("====================================")
        println("VN2 LOAD LINKS")
        println("DATA = $data")
        println("====================================")

        var found = false

        try {

            val response = app.get(
                data,
                headers = requestHeaders + mapOf(
                    "Referer" to mainUrl
                ),
                referer = mainUrl
            )

            val document = response.document
            val html = response.text

            println(
                "VN2 HTML LENGTH = ${html.length}"
            )

            // =================================================
            // 1. VIDEO SOURCE
            // =================================================

            val videoSources =
                document.select(
                    "video source, video"
                )

            for (element in videoSources) {

                var videoUrl =
                    element.attr("src").trim()

                if (videoUrl.isBlank()) {

                    videoUrl =
                        element.attr("data-src").trim()
                }

                if (
                    videoUrl.isNotBlank() &&
                    videoUrl.startsWith("http") &&
                    isVideoUrl(videoUrl)
                ) {

                    println(
                        "VN2 DIRECT VIDEO = $videoUrl"
                    )

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "VN2 Video",
                            url = videoUrl
                        ) {
                            referer = data
                            quality =
                                Qualities.Unknown.value
                        }
                    )

                    found = true
                }
            }

            // =================================================
            // 2. IFRAME
            // =================================================

            val iframeUrls =
                LinkedHashSet<String>()

            val iframeElements =
                document.select("iframe")

            for (iframe in iframeElements) {

                var src =
                    iframe.attr("src").trim()

                if (src.isBlank()) {

                    src =
                        iframe.attr("data-src").trim()
                }

                if (src.isNotBlank()) {

                    iframeUrls.add(
                        makeAbsoluteUrl(src)
                    )
                }
            }

            // =================================================
            // 3. IFRAME FROM RAW HTML
            // =================================================

            val iframeRegex = Regex(
                """<iframe[^>]+(?:src|data-src)\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )

            val iframeMatches =
                iframeRegex.findAll(html)

            for (match in iframeMatches) {

                val src =
                    match.groupValues[1].trim()

                if (src.isNotBlank()) {

                    iframeUrls.add(
                        makeAbsoluteUrl(src)
                    )
                }
            }

            println(
                "VN2 IFRAME COUNT = ${iframeUrls.size}"
            )

            for (iframeUrl in iframeUrls) {

                println(
                    "VN2 IFRAME = $iframeUrl"
                )
            }

            // =================================================
            // 4. PROCESS IFRAME
            // =================================================

            for (iframeUrl in iframeUrls) {

                try {

                    /*
                     * Nếu là host/extractor khác,
                     * cho CloudStream xử lý.
                     */
                    if (!isVn2Player(iframeUrl)) {

                        try {

                            loadExtractor(
                                iframeUrl,
                                data,
                                subtitleCallback,
                                callback
                            )

                            found = true

                            println(
                                "VN2 EXTRACTOR OK = $iframeUrl"
                            )

                        } catch (e: Exception) {

                            println(
                                "VN2 EXTRACTOR ERROR = " +
                                        e.message
                            )
                        }

                        continue
                    }

                    // -----------------------------------------
                    // LOAD PLAYER
                    // -----------------------------------------

                    val iframeResponse =
                        app.get(
                            iframeUrl,
                            headers = requestHeaders + mapOf(
                                "Referer" to data
                            ),
                            referer = data
                        )

                    val iframeHtml =
                        iframeResponse.text

                    println(
                        "VN2 PLAYER HTML LENGTH = " +
                                iframeHtml.length
                    )

                    // -----------------------------------------
                    // M3U8
                    // -----------------------------------------

                    val m3u8Regex = Regex(
                        """https?://[^"'\\\s<>]+\.m3u8(?:\?[^"'\\\s<>]*)?""",
                        RegexOption.IGNORE_CASE
                    )

                    val m3u8Matches =
                        m3u8Regex.findAll(iframeHtml)

                    for (match in m3u8Matches) {

                        val videoUrl =
                            match.value.trim()

                        println(
                            "VN2 M3U8 = $videoUrl"
                        )

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "VN2 HLS",
                                url = videoUrl
                            ) {
                                referer = iframeUrl
                                quality =
                                    Qualities.Unknown.value
                            }
                        )

                        found = true
                    }

                    // -----------------------------------------
                    // MP4
                    // -----------------------------------------

                    val mp4Regex = Regex(
                        """https?://[^"'\\\s<>]+\.mp4(?:\?[^"'\\\s<>]*)?""",
                        RegexOption.IGNORE_CASE
                    )

                    val mp4Matches =
                        mp4Regex.findAll(iframeHtml)

                    for (match in mp4Matches) {

                        val videoUrl =
                            match.value.trim()

                        println(
                            "VN2 MP4 = $videoUrl"
                        )

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "VN2 MP4",
                                url = videoUrl
                            ) {
                                referer = iframeUrl
                                quality =
                                    Qualities.Unknown.value
                            }
                        )

                        found = true
                    }

                    // -----------------------------------------
                    // php_content_embed
                    // -----------------------------------------

                    val phpRegex = Regex(
                        """php_content_embed\s*=\s*["']([^"']+)["']""",
                        RegexOption.IGNORE_CASE
                    )

                    val phpMatches =
                        phpRegex.findAll(iframeHtml)

                    for (match in phpMatches) {

                        var embedUrl =
                            match.groupValues[1].trim()

                        if (
                            embedUrl.startsWith("//")
                        ) {
                            embedUrl =
                                "https:$embedUrl"
                        }

                        if (
                            embedUrl.startsWith("/")
                        ) {
                            embedUrl =
                                makeAbsoluteUrl(embedUrl)
                        }

                        if (
                            embedUrl.startsWith("http")
                        ) {

                            println(
                                "VN2 PHP EMBED = $embedUrl"
                            )

                            try {

                                loadExtractor(
                                    embedUrl,
                                    iframeUrl,
                                    subtitleCallback,
                                    callback
                                )

                                found = true

                            } catch (e: Exception) {

                                println(
                                    "VN2 PHP ERROR = " +
                                            e.message
                                )
                            }
                        }
                    }

                    // -----------------------------------------
                    // NESTED IFRAMES
                    // -----------------------------------------

                    val nestedDocument =
                        iframeResponse.document

                    val nestedIframes =
                        nestedDocument.select("iframe")

                    for (nested in nestedIframes) {

                        var nestedUrl =
                            nested.attr("src").trim()

                        if (nestedUrl.isBlank()) {

                            nestedUrl =
                                nested
                                    .attr("data-src")
                                    .trim()
                        }

                        if (
                            nestedUrl.isBlank()
                        ) {
                            continue
                        }

                        nestedUrl =
                            makeAbsoluteUrl(
                                nestedUrl
                            )

                        println(
                            "VN2 NESTED IFRAME = " +
                                    nestedUrl
                        )

                        try {

                            loadExtractor(
                                nestedUrl,
                                iframeUrl,
                                subtitleCallback,
                                callback
                            )

                            found = true

                        } catch (e: Exception) {

                            println(
                                "VN2 NESTED ERROR = " +
                                        e.message
                            )
                        }
                    }

                } catch (e: Exception) {

                    println(
                        "VN2 IFRAME ERROR = " +
                                e.message
                    )
                }
            }

            // =================================================
            // 5. DIRECT M3U8 / MP4 IN MAIN HTML
            // =================================================

            if (!found) {

                println(
                    "VN2: NO LINK FROM IFRAME"
                )

                val directRegex = Regex(
                    """https?://[^"'\\\s<>]+(?:\.m3u8|\.mp4)(?:\?[^"'\\\s<>]*)?""",
                    RegexOption.IGNORE_CASE
                )

                val directMatches =
                    directRegex.findAll(html)

                for (match in directMatches) {

                    val videoUrl =
                        match.value.trim()

                    println(
                        "VN2 DIRECT STREAM = $videoUrl"
                    )

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "VN2 Direct",
                            url = videoUrl
                        ) {
                            referer = data
                            quality =
                                Qualities.Unknown.value
                        }
                    )

                    found = true
                }
            }

        } catch (e: Exception) {

            println(
                "VN2 LOAD LINKS ERROR = ${e.message}"
            )
        }

        println(
            "VN2 FINAL FOUND = $found"
        )

        println("====================================")

        return found
    }

    // =========================================================
    // URL HELPERS
    // =========================================================

    private fun makeAbsoluteUrl(
        input: String
    ): String {

        var url = input.trim()

        if (url.isBlank()) {
            return url
        }

        if (url.startsWith("//")) {
            return "https:$url"
        }

        if (url.startsWith("http://")) {
            return url
        }

        if (url.startsWith("https://")) {
            return url
        }

        if (url.startsWith("/")) {
            return mainUrl + url
        }

        return url
    }

    private fun isVideoUrl(
        url: String
    ): Boolean {

        val value =
            url.lowercase()

        return value.contains(".m3u8") ||
                value.contains(".mp4") ||
                value.contains(".m4v") ||
                value.contains(".webm")
    }

    private fun isVn2Player(
        url: String
    ): Boolean {

        val value =
            url.lowercase()

        return value.contains("vn2data") ||
                value.contains("cloudcdn") ||
                value.contains("play.php") ||
                value.contains("phim.php") ||
                value.contains("/player") ||
                value.contains("player.")
    }

    // =========================================================
    // SEARCH SLUG
    // =========================================================

    private fun makeSlug(
        input: String
    ): String {

        var value =
            input.trim().lowercase()

        value = value.replace(
            Regex("[áàảãạăắằẳẵặâấầẩẫậ]"),
            "a"
        )

        value = value.replace(
            Regex("[éèẻẽẹêếềểễệ]"),
            "e"
        )

        value = value.replace(
            Regex("[íìỉĩị]"),
            "i"
        )

        value = value.replace(
            Regex("[óòỏõọôốồổỗộơớờởỡợ]"),
            "o"
        )

        value = value.replace(
            Regex("[úùủũụưứừửữự]"),
            "u"
        )

        value = value.replace(
            Regex("[ýỳỷỹỵ]"),
            "y"
        )

        value = value.replace(
            "đ",
            "d"
        )

        value = value.replace(
            Regex("[^a-z0-9]+"),
            "-"
        )

        value = value.replace(
            Regex("-+"),
            "-"
        )

        return value.trim('-')
    }
}