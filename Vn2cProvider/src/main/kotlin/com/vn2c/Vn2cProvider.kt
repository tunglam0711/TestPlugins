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
        "$mainUrl/phim-moi-vn2-phim-2vn-phim/new" to "Phim Mới",
        "$mainUrl/danh-muc/trung-quoc-7" to "Phim Trung Quốc",
        "$mainUrl/danh-muc/han-quoc-10" to "Phim Hàn Quốc",
        "$mainUrl/danh-muc/thai-lan-8" to "Phim Thái Lan",
        "$mainUrl/the-loai2/hoat-hinh-anime-29" to "Hoạt Hình Anime"
    )

    // =========================================================
    // MAIN PAGE
    // =========================================================

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

            val results =
                ArrayList<SearchResponse>()

            val elements =
                document.select("a[href*='/xem/']")

            for (element in elements) {

                val result =
                    element.toSearchResult()

                if (result != null) {
                    results.add(result)
                }
            }

            val uniqueResults =
                results.distinctBy { it.url }

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

        val slug =
            makeSlug(query)

        println(
            "VN2 SEARCH = $query"
        )

        println(
            "VN2 SLUG = $slug"
        )

        val searchUrls = listOf(
            "$mainUrl/tim-kiem/$slug",
            "$mainUrl/tim-kiem?keyword=$slug"
        )

        for (searchUrl in searchUrls) {

            try {

                println(
                    "VN2 SEARCH URL = $searchUrl"
                )

                val document =
                    app.get(
                        searchUrl,
                        headers = defaultHeaders,
                        referer = mainUrl
                    ).document

                val results =
                    ArrayList<SearchResponse>()

                val elements =
                    document.select(
                        "a[href*='/xem/']"
                    )

                for (element in elements) {

                    val result =
                        element.toSearchResult()

                    if (result != null) {
                        results.add(result)
                    }
                }

                val uniqueResults =
                    results.distinctBy { it.url }

                println(
                    "VN2 SEARCH RESULTS = " +
                            uniqueResults.size
                )

                if (uniqueResults.isNotEmpty()) {
                    return uniqueResults
                }

            } catch (e: Exception) {

                println(
                    "VN2 SEARCH ERROR = " +
                            e.message
                )
            }
        }

        return emptyList()
    }

    // =========================================================
    // SEARCH RESULT
    // =========================================================

    private fun Element.toSearchResult():
            SearchResponse? {

        val href =
            attr("href").trim()

        if (href.isBlank()) {
            return null
        }

        if (!href.contains("/xem/")) {
            return null
        }

        val absoluteUrl =
            makeAbsoluteUrl(href)

        // -----------------------------------------------------
        // TITLE
        // -----------------------------------------------------

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

            val nameElement: Element? =
                selectFirst(".nametk")

            if (nameElement != null) {

                val value =
                    nameElement.text().trim()

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

            val textValue =
                text().trim()

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

        val lowerTitle =
            title.lowercase()

        if (lowerTitle == "xem thêm") {
            return null
        }

        if (lowerTitle == "xem phim") {
            return null
        }

        // -----------------------------------------------------
        // POSTER
        // -----------------------------------------------------

        var poster: String? = null

        val image: Element? =
            selectFirst("img")

        if (image != null) {

            var posterValue =
                image
                    .attr("data-src")
                    .trim()

            if (posterValue.isBlank()) {

                posterValue =
                    image
                        .attr("data-original")
                        .trim()
            }

            if (posterValue.isBlank()) {

                posterValue =
                    image
                        .attr("src")
                        .trim()
            }

            if (posterValue.isNotBlank()) {

                poster =
                    makeAbsoluteUrl(
                        posterValue
                    )
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

        println(
            "VN2 LOAD = $url"
        )

        return try {

            val response =
                app.get(
                    url,
                    headers = defaultHeaders,
                    referer = mainUrl
                )

            val document =
                response.document

            // =================================================
            // TITLE
            // =================================================

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
                    document.selectFirst(
                        ".title"
                    )

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
                    document.selectFirst(
                        "title"
                    )

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

            // =================================================
            // POSTER
            // =================================================

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

            // =================================================
            // DESCRIPTION
            // =================================================

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

            val selectors = listOf(
                "a[href*='/tap-']",
                ".num_film a",
                ".list-episode a",
                ".episode a",
                ".episodes a"
            )

            for (selector in selectors) {

                val elements =
                    document.select(selector)

                for (element in elements) {

                    val href =
                        element
                            .attr("href")
                            .trim()

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

            // =================================================
            // PLAY BUTTON
            // =================================================

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
                                makeAbsoluteUrl(
                                    playUrl
                                )
                            ) {
                                name = "Full"
                            }
                        )
                    }
                }
            }

            // =================================================
            // FALLBACK
            // =================================================

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
                "VN2 EPISODES = " +
                        uniqueEpisodes.size
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
                "VN2 LOAD ERROR = " +
                        e.message
            )

            null
        }
    }

    // =========================================================
    // LOAD LINKS / DEBUG
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var found = false

        val debug =
            StringBuilder()

        try {

            println(
                "================================"
            )

            println(
                "VN2 LOAD LINKS"
            )

            println(
                "DATA = $data"
            )

            println(
                "================================"
            )

            // =================================================
            // LOAD EPISODE
            // =================================================

            val response =
                app.get(
                    data,
                    headers = defaultHeaders,
                    referer = mainUrl
                )

            val html =
                response.text

            val document =
                response.document

            debug.append(
                "URL=$data\n"
            )

            debug.append(
                "HTML=${html.length}\n"
            )

            // =================================================
            // DIRECT CLOUDCDN
            // =================================================

            val cloudcdnUrls =
                findCloudCdnUrls(html)

            debug.append(
                "CLOUDCDN=${cloudcdnUrls.size}\n"
            )

            println(
                "VN2 CLOUDCDN = " +
                        cloudcdnUrls.size
            )

            for (videoUrl in cloudcdnUrls) {

                println(
                    "VN2 CLOUDCDN URL = " +
                            videoUrl
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
            // DIRECT MP4
            // =================================================

            val mp4Urls =
                findMp4Urls(html)

            debug.append(
                "MP4=${mp4Urls.size}\n"
            )

            println(
                "VN2 MP4 = " +
                        mp4Urls.size
            )

            for (videoUrl in mp4Urls) {

                if (
                    !cloudcdnUrls.contains(
                        videoUrl
                    )
                ) {

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name =
                                detectServerName(
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
            // DIRECT M3U8
            // =================================================

            val m3u8Urls =
                findM3u8Urls(html)

            debug.append(
                "M3U8=${m3u8Urls.size}\n"
            )

            println(
                "VN2 M3U8 = " +
                        m3u8Urls.size
            )

            for (videoUrl in m3u8Urls) {

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "HLS Server",
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
            // VIDEO TAG
            // =================================================

            val videoElements =
                document.select(
                    "video source, video"
                )

            debug.append(
                "VIDEO_TAG=" +
                        videoElements.size +
                        "\n"
            )

            for (element in videoElements) {

                var videoUrl =
                    element
                        .attr("src")
                        .trim()

                if (videoUrl.isBlank()) {

                    videoUrl =
                        element
                            .attr("data-src")
                            .trim()
                }

                if (videoUrl.isBlank()) {
                    continue
                }

                if (
                    !videoUrl.startsWith(
                        "http"
                    )
                ) {

                    videoUrl =
                        makeAbsoluteUrl(
                            videoUrl
                        )
                }

                if (
                    isVideoUrl(
                        videoUrl
                    )
                ) {

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name =
                                detectServerName(
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
            // IFRAME
            // =================================================

            val iframeUrls =
                LinkedHashSet<String>()

            val iframeElements =
                document.select(
                    "iframe"
                )

            for (iframe in iframeElements) {

                var iframeUrl =
                    iframe
                        .attr("src")
                        .trim()

                if (iframeUrl.isBlank()) {

                    iframeUrl =
                        iframe
                            .attr("data-src")
                            .trim()
                }

                if (
                    iframeUrl.isNotBlank()
                ) {

                    iframeUrls.add(
                        makeAbsoluteUrl(
                            iframeUrl
                        )
                    )
                }
            }

            debug.append(
                "IFRAME=" +
                        iframeUrls.size +
                        "\n"
            )

            // =================================================
            // IMPORTANT:
            // RAW HTML IFRAME
            // =================================================

            val iframeRegex =
                Regex(
                    """<iframe[^>]+(?:src|data-src)\s*=\s*["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                )

            for (
            match in
            iframeRegex.findAll(html)
            ) {

                val iframeUrl =
                    match
                        .groupValues[1]
                        .trim()

                if (
                    iframeUrl.isNotBlank()
                ) {

                    iframeUrls.add(
                        makeAbsoluteUrl(
                            iframeUrl
                        )
                    )
                }
            }

            // Re-update count after raw HTML scan
            debug.append(
                "IFRAME_FINAL=" +
                        iframeUrls.size +
                        "\n"
            )

            for (iframeUrl in iframeUrls) {

                debug.append(
                    "IF=$iframeUrl\n"
                )
            }

            // =================================================
            // KEYWORD DEBUG
            // =================================================

            val keywords = listOf(
                "cloudcdn",
                "cloudcdnvn",
                "m3u8",
                ".mp4",
                "iframe",
                "player",
                "source",
                "file",
                "video",
                "stream",
                "ajax",
                "api",
                "play.php",
                "phim.php",
                "vn2data"
            )

            for (keyword in keywords) {

                val count =
                    keywordRegexCount(
                        html,
                        keyword
                    )

                if (count > 0) {

                    debug.append(
                        "$keyword=$count\n"
                    )
                }
            }

            // =================================================
            // IMPORTANT DEBUG SNIPPETS
            // =================================================

            /*
             * Đây là phần quan trọng nhất.
             *
             * Trang đang có:
             *
             * vn2data = 27
             * player = 10
             * api = 1
             *
             * nên ta lấy những dòng HTML/JS chứa
             * các keyword này.
             */

            debug.append(
                "\n===== VN2DATA =====\n"
            )

            debug.append(
                findHtmlLines(
                    html,
                    "vn2data"
                )
            )

            debug.append(
                "\n===== PLAYER =====\n"
            )

            debug.append(
                findHtmlLines(
                    html,
                    "player"
                )
            )

            debug.append(
                "\n===== API =====\n"
            )

            debug.append(
                findHtmlLines(
                    html,
                    "api"
                )
            )

            debug.append(
                "\n===== AJAX =====\n"
            )

            debug.append(
                findHtmlLines(
                    html,
                    "ajax"
                )
            )

            // =================================================
            // PROCESS IFRAME
            // =================================================

            for (iframeUrl in iframeUrls) {

                try {

                    println(
                        "VN2 PROCESS IFRAME = " +
                                iframeUrl
                    )

                    if (
                        !isVn2Player(
                            iframeUrl
                        )
                    ) {

                        try {

                            loadExtractor(
                                iframeUrl,
                                data,
                                subtitleCallback,
                                callback
                            )

                            found = true

                        } catch (e: Exception) {

                            println(
                                "VN2 EXTRACTOR ERROR = " +
                                        e.message
                            )
                        }

                        continue
                    }

                    // =================================================
                    // LOAD PLAYER
                    // =================================================

                    val playerResponse =
                        app.get(
                            iframeUrl,
                            headers =
                                defaultHeaders +
                                        mapOf(
                                            "Referer" to data
                                        ),
                            referer = data
                        )

                    val playerHtml =
                        playerResponse.text

                    debug.append(
                        "PLAYER_HTML=" +
                                playerHtml.length +
                                "\n"
                    )

                    // =================================================
                    // PLAYER CLOUDCDN
                    // =================================================

                    val playerCloudUrls =
                        findCloudCdnUrls(
                            playerHtml
                        )

                    debug.append(
                        "PLAYER_CLOUDCDN=" +
                                playerCloudUrls.size +
                                "\n"
                    )

                    for (
                    videoUrl in
                    playerCloudUrls
                    ) {

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "CloudCDN",
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
                    // PLAYER MP4
                    // =================================================

                    val playerMp4Urls =
                        findMp4Urls(
                            playerHtml
                        )

                    debug.append(
                        "PLAYER_MP4=" +
                                playerMp4Urls.size +
                                "\n"
                    )

                    for (
                    videoUrl in
                    playerMp4Urls
                    ) {

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name =
                                    detectServerName(
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
                    // PLAYER M3U8
                    // =================================================

                    val playerM3u8Urls =
                        findM3u8Urls(
                            playerHtml
                        )

                    debug.append(
                        "PLAYER_M3U8=" +
                                playerM3u8Urls.size +
                                "\n"
                    )

                    for (
                    videoUrl in
                    playerM3u8Urls
                    ) {

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "HLS Server",
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

                    val phpRegex =
                        Regex(
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
                            match
                                .groupValues[1]
                                .trim()

                        if (
                            embedUrl.startsWith(
                                "//"
                            )
                        ) {

                            embedUrl =
                                "https:$embedUrl"
                        }

                        if (
                            embedUrl.startsWith(
                                "/"
                            )
                        ) {

                            embedUrl =
                                makeAbsoluteUrl(
                                    embedUrl
                                )
                        }

                        if (
                            embedUrl.startsWith(
                                "http"
                            )
                        ) {

                            debug.append(
                                "PHP_EMBED=" +
                                        embedUrl +
                                        "\n"
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

                    // =================================================
                    // NESTED IFRAME
                    // =================================================

                    val nestedIframes =
                        playerResponse
                            .document
                            .select(
                                "iframe"
                            )

                    debug.append(
                        "NESTED_IFRAME=" +
                                nestedIframes.size +
                                "\n"
                    )

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
                                    .attr(
                                        "data-src"
                                    )
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

                        debug.append(
                            "NESTED=" +
                                    nestedUrl +
                                    "\n"
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

                    debug.append(
                        "SERVER_ERROR=" +
                                e.message +
                                "\n"
                    )
                }
            }

            // =================================================
            // FINAL RESULT
            // =================================================

            if (!found) {

                debug.append(
                    "\nFINAL_FOUND=false\n"
                )

                /*
                 * DEBUG BUILD:
                 *
                 * Provider Test sẽ hiển thị
                 * thông tin JavaScript ở đây.
                 */

                throw Exception(
                    "VN2 DEBUG\n" +
                            debug.toString()
                )
            }

            debug.append(
                "\nFINAL_FOUND=true\n"
            )

            println(
                "VN2 LINKS FOUND = true"
            )

            return true

        } catch (e: Exception) {

            if (
                e.message != null &&
                e.message!!.startsWith(
                    "VN2 DEBUG"
                )
            ) {
                throw e
            }

            throw Exception(
                "VN2 LOADLINK ERROR\n" +
                        e.message
            )
        }
    }

    // =========================================================
    // FIND CLOUDCDN URL
    // =========================================================

    private fun findCloudCdnUrls(
        html: String
    ): List<String> {

        val results =
            LinkedHashSet<String>()

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
                match.value.trim()

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

            results.add(url)
        }

        return results.toList()
    }

    // =========================================================
    // FIND MP4
    // =========================================================

    private fun findMp4Urls(
        html: String
    ): List<String> {

        val results =
            LinkedHashSet<String>()

        val regex =
            Regex(
                """https?://[^"'\\\s<>]+\.mp4(?:\?[^"'\\\s<>]*)?""",
                RegexOption.IGNORE_CASE
            )

        for (
        match in
        regex.findAll(html)
        ) {

            var url =
                match.value.trim()

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

            results.add(url)
        }

        return results.toList()
    }

    // =========================================================
    // FIND M3U8
    // =========================================================

    private fun findM3u8Urls(
        html: String
    ): List<String> {

        val results =
            LinkedHashSet<String>()

        val regex =
            Regex(
                """https?://[^"'\\\s<>]+\.m3u8(?:\?[^"'\\\s<>]*)?""",
                RegexOption.IGNORE_CASE
            )

        for (
        match in
        regex.findAll(html)
        ) {

            var url =
                match.value.trim()

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

            results.add(url)
        }

        return results.toList()
    }

    // =========================================================
    // FIND HTML LINES
    // =========================================================

    private fun findHtmlLines(
        html: String,
        keyword: String
    ): String {

        val result =
            StringBuilder()

        /*
         * HTML thường không xuống dòng đúng chỗ
         * nên trước tiên thử split theo:
         *
         * <script
         * </script>
         * ;
         * {
         * }
         */

        val chunks =
            html
                .replace(
                    "<script",
                    "\n<script",
                    ignoreCase = true
                )
                .replace(
                    "</script>",
                    "\n</script>\n",
                    ignoreCase = true
                )
                .replace(
                    ";",
                    ";\n"
                )

        val lines =
            chunks.split("\n")

        var count = 0

        for (line in lines) {

            if (
                line.contains(
                    keyword,
                    ignoreCase = true
                )
            ) {

                val clean =
                    line.trim()

                if (clean.isNotBlank()) {

                    result.append(
                        clean
                    )

                    result.append(
                        "\n"
                    )

                    count++
                }

                /*
                 * Không để popup quá dài.
                 */
                if (count >= 10) {
                    break
                }
            }
        }

        if (count == 0) {
            result.append(
                "NOT FOUND\n"
            )
        }

        return result.toString()
    }

    // =========================================================
    // KEYWORD COUNT
    // =========================================================

    private fun keywordRegexCount(
        html: String,
        keyword: String
    ): Int {

        return Regex(
            Regex.escape(keyword),
            RegexOption.IGNORE_CASE
        )
            .findAll(html)
            .count()
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
    // VIDEO URL CHECK
    // =========================================================

    private fun isVideoUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase()

        return lower.contains(
            ".mp4"
        ) ||
                lower.contains(
                    ".m3u8"
                ) ||
                lower.contains(
                    ".m4v"
                ) ||
                lower.contains(
                    ".webm"
                ) ||
                lower.contains(
                    "cloudcdnvn.com"
                )
    }

    // =========================================================
    // VN2 PLAYER CHECK
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
    // ABSOLUTE URL
    // =========================================================

    private fun makeAbsoluteUrl(
        input: String
    ): String {

        val value =
            input.trim()

        if (value.isBlank()) {
            return value
        }

        if (
            value.startsWith("//")
        ) {
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

        if (
            value.startsWith("/")
        ) {
            return mainUrl + value
        }

        return value
    }

    // =========================================================
    // VIETNAMESE SLUG
    // =========================================================

    private fun makeSlug(
        input: String
    ): String {

        var value =
            input
                .trim()
                .lowercase()

        value =
            value.replace(
                Regex(
                    "[áàảãạăắằẳẵặâấầẩẫậ]"
                ),
                "a"
            )

        value =
            value.replace(
                Regex(
                    "[éèẻẽẹêếềểễệ]"
                ),
                "e"
            )

        value =
            value.replace(
                Regex(
                    "[íìỉĩị]"
                ),
                "i"
            )

        value =
            value.replace(
                Regex(
                    "[óòỏõọôốồổỗộơớờởỡợ]"
                ),
                "o"
            )

        value =
            value.replace(
                Regex(
                    "[úùủũụưứừửữự]"
                ),
                "u"
            )

        value =
            value.replace(
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
                Regex(
                    "[^a-z0-9]+"
                ),
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