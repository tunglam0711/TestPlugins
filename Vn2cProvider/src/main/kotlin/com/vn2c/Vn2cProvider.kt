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

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Connection" to "keep-alive"
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

        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data}?page=$page"
        }

        return try {

            val document = app.get(
                url,
                headers = defaultHeaders,
                referer = mainUrl
            ).document

            val results = ArrayList<SearchResponse>()

            document
                .select("a[href*='/xem/']")
                .forEach { element ->

                    val result = element.toSearchResult()

                    if (result != null) {
                        results.add(result)
                    }
                }

            newHomePageResponse(
                request.name,
                results.distinctBy { it.url }
            )

        } catch (e: Exception) {

            println(
                "VN2 MAIN ERROR: ${e.message}"
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

        val slug = makeSlug(query)

        val searchUrls = listOf(
            "$mainUrl/tim-kiem/$slug",
            "$mainUrl/tim-kiem?keyword=$slug"
        )

        for (searchUrl in searchUrls) {

            try {

                val document = app.get(
                    searchUrl,
                    headers = defaultHeaders,
                    referer = mainUrl
                ).document

                val results = ArrayList<SearchResponse>()

                document
                    .select("a[href*='/xem/']")
                    .forEach { element ->

                        val result =
                            element.toSearchResult()

                        if (result != null) {
                            results.add(result)
                        }
                    }

                val unique =
                    results.distinctBy { it.url }

                if (unique.isNotEmpty()) {
                    return unique
                }

            } catch (e: Exception) {

                println(
                    "VN2 SEARCH ERROR: ${e.message}"
                )
            }
        }

        return emptyList()
    }

    // =========================================================
    // SEARCH RESULT
    // =========================================================

    private fun Element.toSearchResult(): SearchResponse? {

        val href = attr("href").trim()

        if (href.isBlank()) {
            return null
        }

        if (!href.contains("/xem/")) {
            return null
        }

        val absoluteUrl =
            makeAbsoluteUrl(href)

        var title: String? = null

        val titleAttr =
            attr("title").trim()

        if (titleAttr.isNotBlank()) {
            title = titleAttr
        }

        if (title == null) {

            val dataTitle =
                attr("data-title").trim()

            if (dataTitle.isNotBlank()) {
                title = dataTitle
            }
        }

        if (title == null) {

            val image: Element? =
                selectFirst("img")

            if (image != null) {

                val alt =
                    image.attr("alt").trim()

                if (alt.isNotBlank()) {
                    title = alt
                }
            }
        }

        if (title == null) {

            val titleElement: Element? =
                selectFirst(".title")

            if (titleElement != null) {

                val value =
                    titleElement.text().trim()

                if (value.isNotBlank()) {
                    title = value
                }
            }
        }

        if (title == null) {

            val nameElement: Element? =
                selectFirst(".name")

            if (nameElement != null) {

                val value =
                    nameElement.text().trim()

                if (value.isNotBlank()) {
                    title = value
                }
            }
        }

        if (title == null) {

            val textValue =
                text().trim()

            if (textValue.isNotBlank()) {
                title = textValue
            }
        }

        if (title.isNullOrBlank()) {
            return null
        }

        var poster: String? = null

        val image: Element? =
            selectFirst("img")

        if (image != null) {

            var imageUrl =
                image.attr("data-src").trim()

            if (imageUrl.isBlank()) {
                imageUrl =
                    image.attr("data-original").trim()
            }

            if (imageUrl.isBlank()) {
                imageUrl =
                    image.attr("src").trim()
            }

            if (imageUrl.isNotBlank()) {
                poster =
                    makeAbsoluteUrl(imageUrl)
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
    // LOAD
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        println("VN2 LOAD = $url")

        return try {

            val response = app.get(
                url,
                headers = defaultHeaders,
                referer = mainUrl
            )

            val document =
                response.document

            // -------------------------------------------------
            // TITLE
            // -------------------------------------------------

            var title: String? = null

            val h1: Element? =
                document.selectFirst("h1")

            if (h1 != null) {

                val value =
                    h1.text().trim()

                if (value.isNotBlank()) {
                    title = value
                }
            }

            if (title == null) {

                val element: Element? =
                    document.selectFirst(
                        ".box_film_title"
                    )

                if (element != null) {

                    val value =
                        element.text().trim()

                    if (value.isNotBlank()) {
                        title = value
                    }
                }
            }

            if (title == null) {

                val element: Element? =
                    document.selectFirst(".title")

                if (element != null) {

                    val value =
                        element.text().trim()

                    if (value.isNotBlank()) {
                        title = value
                    }
                }
            }

            if (title == null) {
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

                var value =
                    posterElement
                        .attr("data-src")
                        .trim()

                if (value.isBlank()) {

                    value =
                        posterElement
                            .attr("data-original")
                            .trim()
                }

                if (value.isBlank()) {

                    value =
                        posterElement
                            .attr("src")
                            .trim()
                }

                if (value.isNotBlank()) {
                    poster =
                        makeAbsoluteUrl(value)
                }
            }

            // -------------------------------------------------
            // PLOT
            // -------------------------------------------------

            var plot: String? = null

            val plotElement: Element? =
                document.selectFirst(
                    ".wiew_info p, " +
                            ".info-film, " +
                            ".description, " +
                            ".content-film, " +
                            ".desc"
                )

            if (plotElement != null) {

                val value =
                    plotElement.text().trim()

                if (value.isNotBlank()) {
                    plot = value
                }
            }

            // =================================================
            // EPISODES
            // =================================================

            val episodes =
                ArrayList<Episode>()

            /*
             * Tìm tập phim.
             *
             * VN2 có thể có:
             *
             * /xem/...-tap-1
             * /xem/...-tap-2
             * ...
             *
             * hoặc link tập trong class riêng.
             */

            val episodeSelectors = listOf(
                "a[href*='/tap-']",
                ".num_film a",
                ".list-episode a",
                ".episode a",
                ".episodes a"
            )

            for (selector in episodeSelectors) {

                val elements =
                    document.select(selector)

                for (element in elements) {

                    val href =
                        element.attr("href").trim()

                    if (href.isBlank()) {
                        continue
                    }

                    val episodeUrl =
                        makeAbsoluteUrl(href)

                    if (
                        episodeUrl.removeSuffix("/") ==
                        url.removeSuffix("/")
                    ) {
                        continue
                    }

                    var episodeName =
                        element.text().trim()

                    if (episodeName.isBlank()) {
                        episodeName =
                            extractEpisodeNumber(
                                episodeUrl
                            )
                    }

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

                if (episodes.isNotEmpty()) {
                    break
                }
            }

            /*
             * Nếu trang phim chỉ có một link player,
             * coi đó là Full.
             */
            if (episodes.isEmpty()) {

                val playElement: Element? =
                    document.selectFirst(
                        "a.btn-play, " +
                                "div.playphim a, " +
                                ".play-btn a"
                    )

                if (playElement != null) {

                    val playUrl =
                        playElement
                            .attr("href")
                            .trim()

                    if (playUrl.isNotBlank()) {

                        episodes.add(
                            newEpisode(
                                makeAbsoluteUrl(playUrl)
                            ) {
                                name = "Full"
                            }
                        )
                    }
                }
            }

            /*
             * Cuối cùng dùng URL hiện tại.
             */
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

            return newTvSeriesLoadResponse(
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

        println("------------------------------------")
        println("VN2 LOAD LINKS")
        println("EPISODE = $data")
        println("------------------------------------")

        var found = false

        try {

            val response = app.get(
                data,
                headers = defaultHeaders,
                referer = mainUrl
            )

            val document =
                response.document

            val html =
                response.text

            println(
                "VN2 EPISODE HTML = ${html.length}"
            )

            // =================================================
            // SERVER 1: DIRECT MP4 IN HTML
            // =================================================

            val cloudUrls =
                findCloudCdnUrls(html)

            println(
                "VN2 CLOUDCDN FOUND = ${cloudUrls.size}"
            )

            for (videoUrl in cloudUrls) {

                println(
                    "VN2 CLOUDCDN = $videoUrl"
                )

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "CloudCDN",
                        url = videoUrl
                    ) {
                        referer = data
                        quality =
                            Qualities.Unknown.value
                    }
                )

                found = true
            }

            // =================================================
            // SERVER 2: VIDEO SOURCE
            // =================================================

            val videoElements =
                document.select(
                    "video source, video"
                )

            for (element in videoElements) {

                var videoUrl =
                    element.attr("src").trim()

                if (videoUrl.isBlank()) {

                    videoUrl =
                        element
                            .attr("data-src")
                            .trim()
                }

                if (
                    videoUrl.isNotBlank() &&
                    videoUrl.startsWith("http") &&
                    isVideoUrl(videoUrl)
                ) {

                    println(
                        "VN2 VIDEO = $videoUrl"
                    )

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = detectServerName(
                                videoUrl
                            ),
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
            // SERVER 3: IFRAME
            // =================================================

            val iframeUrls =
                LinkedHashSet<String>()

            val iframeElements =
                document.select("iframe")

            for (iframe in iframeElements) {

                var iframeUrl =
                    iframe.attr("src").trim()

                if (iframeUrl.isBlank()) {

                    iframeUrl =
                        iframe
                            .attr("data-src")
                            .trim()
                }

                if (iframeUrl.isNotBlank()) {

                    iframeUrls.add(
                        makeAbsoluteUrl(
                            iframeUrl
                        )
                    )
                }
            }

            // =================================================
            // SERVER 4: RAW HTML IFRAME
            // =================================================

            val iframeRegex = Regex(
                """<iframe[^>]+(?:src|data-src)\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )

            for (match in iframeRegex.findAll(html)) {

                val iframeUrl =
                    match.groupValues[1].trim()

                if (iframeUrl.isNotBlank()) {

                    iframeUrls.add(
                        makeAbsoluteUrl(
                            iframeUrl
                        )
                    )
                }
            }

            println(
                "VN2 SERVERS/IFRAMES = ${iframeUrls.size}"
            )

            // =================================================
            // PROCESS SERVERS
            // =================================================

            for (iframeUrl in iframeUrls) {

                try {

                    println(
                        "VN2 SERVER = $iframeUrl"
                    )

                    /*
                     * CloudStream extractor khác.
                     */
                    if (!isVn2Player(iframeUrl)) {

                        loadExtractor(
                            iframeUrl,
                            data,
                            subtitleCallback,
                            callback
                        )

                        found = true

                        continue
                    }

                    // -------------------------------------------------
                    // VN2 PLAYER
                    // -------------------------------------------------

                    val playerResponse =
                        app.get(
                            iframeUrl,
                            headers = defaultHeaders,
                            referer = data
                        )

                    val playerHtml =
                        playerResponse.text

                    println(
                        "VN2 PLAYER HTML = " +
                                playerHtml.length
                    )

                    // =================================================
                    // CLOUDCDN MP4
                    // =================================================

                    val playerCloudUrls =
                        findCloudCdnUrls(
                            playerHtml
                        )

                    for (
                    videoUrl in
                    playerCloudUrls
                    ) {

                        println(
                            "VN2 PLAYER CLOUDCDN = " +
                                    videoUrl
                        )

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "CloudCDN",
                                url = videoUrl
                            ) {

                                /*
                                 * Rất quan trọng:
                                 * URL CloudCDN có thể kiểm tra
                                 * Referer.
                                 */
                                referer =
                                    iframeUrl

                                quality =
                                    Qualities.Unknown.value
                            }
                        )

                        found = true
                    }

                    // =================================================
                    // M3U8
                    // =================================================

                    val m3u8Regex = Regex(
                        """https?://[^"'\\\s<>]+\.m3u8(?:\?[^"'\\\s<>]*)?""",
                        RegexOption.IGNORE_CASE
                    )

                    for (
                    match in
                    m3u8Regex.findAll(
                        playerHtml
                    )
                    ) {

                        val videoUrl =
                            match.value

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "CloudCDN HLS",
                                url = videoUrl
                            ) {

                                referer =
                                    iframeUrl

                                quality =
                                    Qualities.Unknown.value
                            }
                        )

                        found = true
                    }

                    // =================================================
                    // MP4
                    // =================================================

                    val mp4Regex = Regex(
                        """https?://[^"'\\\s<>]+\.mp4(?:\?[^"'\\\s<>]*)?""",
                        RegexOption.IGNORE_CASE
                    )

                    for (
                    match in
                    mp4Regex.findAll(
                        playerHtml
                    )
                    ) {

                        val videoUrl =
                            match.value

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = detectServerName(
                                    videoUrl
                                ),
                                url = videoUrl
                            ) {

                                referer =
                                    iframeUrl

                                quality =
                                    Qualities.Unknown.value
                            }
                        )

                        found = true
                    }

                    // =================================================
                    // PHP EMBED
                    // =================================================

                    val phpRegex = Regex(
                        """php_content_embed\s*=\s*["']([^"']+)["']""",
                        RegexOption.IGNORE_CASE
                    )

                    for (
                    match in
                    phpRegex.findAll(
                        playerHtml
                    )
                    ) {

                        var embedUrl =
                            match.groupValues[1]
                                .trim()

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
                                makeAbsoluteUrl(
                                    embedUrl
                                )
                        }

                        if (
                            embedUrl.startsWith("http")
                        ) {

                            try {

                                loadExtractor(
                                    embedUrl,
                                    iframeUrl,
                                    subtitleCallback,
                                    callback
                                )

                                found = true

                            } catch (
                                e: Exception
                            ) {

                                println(
                                    "VN2 EMBED ERROR = " +
                                            e.message
                                )
                            }
                        }
                    }

                    // =================================================
                    // NESTED IFRAME
                    // =================================================

                    val nestedIframes =
                        playerResponse
                            .document
                            .select("iframe")

                    for (
                    nested in
                    nestedIframes
                    ) {

                        var nestedUrl =
                            nested
                                .attr("src")
                                .trim()

                        if (
                            nestedUrl.isBlank()
                        ) {

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

                        try {

                            loadExtractor(
                                nestedUrl,
                                iframeUrl,
                                subtitleCallback,
                                callback
                            )

                            found = true

                        } catch (
                            e: Exception
                        ) {

                            println(
                                "VN2 NESTED ERROR = " +
                                        e.message
                            )
                        }
                    }

                } catch (
                    e: Exception
                ) {

                    println(
                        "VN2 SERVER ERROR = " +
                                e.message
                    )
                }
            }

            // =================================================
            // FALLBACK: SEARCH ALL MP4 IN MAIN HTML
            // =================================================

            if (!found) {

                println(
                    "VN2: FALLBACK MP4 SEARCH"
                )

                val allMp4Regex =
                    Regex(
                        """https?://[^"'\\\s<>]+\.mp4(?:\?[^"'\\\s<>]*)?""",
                        RegexOption.IGNORE_CASE
                    )

                for (
                match in
                allMp4Regex.findAll(
                    html
                )
                ) {

                    val videoUrl =
                        match.value

                    println(
                        "VN2 FALLBACK MP4 = " +
                                videoUrl
                    )

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = detectServerName(
                                videoUrl
                            ),
                            url = videoUrl
                        ) {

                            referer =
                                data

                            quality =
                                Qualities.Unknown.value
                        }
                    )

                    found = true
                }
            }

        } catch (
            e: Exception
        ) {

            println(
                "VN2 LOAD LINKS ERROR = " +
                        e.message
            )
        }

        println(
            "VN2 LINKS FOUND = $found"
        )

        println("------------------------------------")

        return found
    }

    // =========================================================
    // FIND CLOUDCDN
    // =========================================================

    private fun findCloudCdnUrls(
        html: String
    ): List<String> {

        val result =
            LinkedHashSet<String>()

        /*
         * Bắt URL dạng:
         *
         * https://ns27.cloudcdnvn.com/....../1.mp4?t=...
         */

        val regex =
            Regex(
                """https?://[^"'\\\s<>]+cloudcdnvn\.com[^"'\\\s<>]+\.mp4(?:\?[^"'\\\s<>]*)?""",
                RegexOption.IGNORE_CASE
            )

        for (
        match in
        regex.findAll(html)
        ) {

            var url =
                match.value

            /*
             * HTML/JS đôi khi escape &.
             */
            url =
                url.replace(
                    "\\/",
                    "/"
                )

            url =
                url.replace(
                    "&amp;",
                    "&"
                )

            result.add(url)
        }

        return result.toList()
    }

    // =========================================================
    // SERVER NAME
    // =========================================================

    private fun detectServerName(
        url: String
    ): String {

        val lower =
            url.lowercase()

        return when {

            lower.contains(
                "cloudcdnvn.com"
            ) -> "CloudCDN"

            lower.contains(
                ".m3u8"
            ) -> "HLS Server"

            lower.contains(
                ".mp4"
            ) -> "MP4 Server"

            else -> "Server"
        }
    }

    // =========================================================
    // VIDEO CHECK
    // =========================================================

    private fun isVideoUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase()

        return lower.contains(".mp4") ||
                lower.contains(".m3u8") ||
                lower.contains(".m4v") ||
                lower.contains(".webm") ||
                lower.contains("cloudcdnvn.com")
    }

    // =========================================================
    // PLAYER CHECK
    // =========================================================

    private fun isVn2Player(
        url: String
    ): Boolean {

        val lower =
            url.lowercase()

        return lower.contains(
            "vn2data"
        ) ||
                lower.contains(
                    "cloudcdnvn"
                ) ||
                lower.contains(
                    "cloudcdn"
                ) ||
                lower.contains(
                    "play.php"
                ) ||
                lower.contains(
                    "phim.php"
                ) ||
                lower.contains(
                    "/player"
                ) ||
                lower.contains(
                    "player."
                )
    }

    // =========================================================
    // EPISODE NUMBER
    // =========================================================

    private fun extractEpisodeNumber(
        url: String
    ): String {

        val regex =
            Regex(
                """(?:tap[-_ ]?|episode[-_ ]?|ep[-_ ]?)(\d+)""",
                RegexOption.IGNORE_CASE
            )

        val match =
            regex.find(url)

        if (match != null) {

            return "Tập " +
                    match.groupValues[1]
        }

        return ""
    }

    // =========================================================
    // URL
    // =========================================================

    private fun makeAbsoluteUrl(
        input: String
    ): String {

        val value =
            input.trim()

        if (value.isBlank()) {
            return value
        }

        if (value.startsWith("//")) {
            return "https:$value"
        }

        if (
            value.startsWith(
                "http://"
            )
        ) {
            return value
        }

        if (
            value.startsWith(
                "https://"
            )
        ) {
            return value
        }

        if (value.startsWith("/")) {
            return mainUrl + value
        }

        return value
    }

    // =========================================================
    // SLUG
    // =========================================================

    private fun makeSlug(
        input: String
    ): String {

        var value =
            input.trim().lowercase()

        value = value.replace(
            Regex(
                "[áàảãạăắằẳẵặâấầẩẫậ]"
            ),
            "a"
        )

        value = value.replace(
            Regex(
                "[éèẻẽẹêếềểễệ]"
            ),
            "e"
        )

        value = value.replace(
            Regex(
                "[íìỉĩị]"
            ),
            "i"
        )

        value = value.replace(
            Regex(
                "[óòỏõọôốồổỗộơớờởỡợ]"
            ),
            "o"
        )

        value = value.replace(
            Regex(
                "[úùủũụưứừửữự]"
            ),
            "u"
        )

        value = value.replace(
            Regex(
                "[ýỳỷỹỵ]"
            ),
            "y"
        )

        value =
            value.replace(
                "đ",
                "d"
            )

        value =
            value.replace(
                Regex("[^a-z0-9]+"),
                "-"
            )

        value =
            value.replace(
                Regex("-+"),
                "-"
            )

        return value.trim('-')
    }
}