package com.vn2c

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Vn2cProvider : MainAPI() {

    // =========================================================
    // THÔNG TIN PROVIDER
    // =========================================================

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
    // SELECTOR
    // =========================================================

    private val itemSelector =
        "div.Form2, div.boxtk"

    private val posterSelector =
        "img.c10, div.boxtk_img img, img"

    private val episodeSelector =
        "div.num_film a"

    private val descriptionSelector =
        "div.wiew_info p, div.info-film, .content-film"

    // =========================================================
    // USER AGENT
    // =========================================================

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/151.0.0.0 Safari/537.36"

    private fun getHeaders(
        referer: String
    ): Map<String, String> {

        return mapOf(
            "User-Agent" to userAgent,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
            "Connection" to "keep-alive"
        )
    }

    // =========================================================
    // MAIN PAGE
    // =========================================================

    override val mainPage = mainPageOf(

        "$mainUrl/phim-moi-vn2-phim-2vn-phim/new" to
                "Phim Mới",

        "$mainUrl/danh-muc/trung-quoc-7" to
                "Phim Trung Quốc",

        "$mainUrl/danh-muc/han-quoc-10" to
                "Phim Hàn Quốc",

        "$mainUrl/danh-muc/thai-lan-8" to
                "Phim Thái Lan",

        "$mainUrl/the-loai2/hoat-hinh-anime-29" to
                "Hoạt Hình Anime"
    )

    // =========================================================
    // BỎ DẤU TIẾNG VIỆT
    // =========================================================

    private fun String.toSlug(): String {

        var text = this
            .trim()
            .lowercase()

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

                text = text.replace(
                    character.toString(),
                    replacement
                )
            }
        }

        text = text.replace(
            Regex("[^a-z0-9]+"),
            "-"
        )

        text = text.replace(
            Regex("-+"),
            "-"
        )

        return text.trim('-')
    }

    // =========================================================
    // MAIN PAGE
    // =========================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        return try {

            val pageUrl =
                if (page <= 1) {
                    request.data
                } else {
                    "${request.data}?page=$page"
                }

            val document =
                app.get(
                    pageUrl,
                    headers = getHeaders(mainUrl)
                ).document

            val results =
                document
                    .select(itemSelector)
                    .mapNotNull { element ->

                        element.toSearchResult()
                    }

            newHomePageResponse(
                request.name,
                results,
                hasNext = results.isNotEmpty()
            )

        } catch (_: Exception) {

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

        return try {

            val slug =
                query.toSlug()

            if (slug.isBlank()) {
                return emptyList()
            }

            val searchUrl =
                "$mainUrl/tim-kiem/$slug"

            val document =
                app.get(
                    searchUrl,
                    headers = getHeaders(mainUrl)
                ).document

            document
                .select(itemSelector)
                .mapNotNull { element ->

                    element.toSearchResult()
                }

        } catch (_: Exception) {

            emptyList()
        }
    }

    // =========================================================
    // SEARCH RESULT
    // =========================================================

    private fun Element.toSearchResult(): SearchResponse? {

        val linkElement =
            selectFirst("a[href]")
                ?: return null

        var title =
            selectFirst("p.nametk a")
                ?.text()

        if (title.isNullOrBlank()) {

            title =
                linkElement.attr("title")
        }

        if (title.isNullOrBlank()) {

            title =
                linkElement.text()
        }

        if (title.isNullOrBlank()) {
            return null
        }

        val href =
            linkElement
                .attr("href")
                .trim()

        if (href.isBlank()) {
            return null
        }

        // -----------------------------------------------------
        // POSTER
        // -----------------------------------------------------

        var poster: String? = null

        val posterElement =
            selectFirst(posterSelector)

        if (posterElement != null) {

            val dataSrc =
                posterElement
                    .attr("data-src")
                    .trim()

            val dataOriginal =
                posterElement
                    .attr("data-original")
                    .trim()

            val src =
                posterElement
                    .attr("src")
                    .trim()

            poster =
                when {

                    dataSrc.isNotBlank() ->
                        dataSrc

                    dataOriginal.isNotBlank() ->
                        dataOriginal

                    src.isNotBlank() ->
                        src

                    else ->
                        null
                }
        }

        return newMovieSearchResponse(
            title.trim(),
            fixUrl(href),
            TvType.TvSeries
        ) {

            if (!poster.isNullOrBlank()) {

                posterUrl =
                    fixUrlNull(poster)
            }
        }
    }

    // =========================================================
    // LOAD
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        return try {

            val response =
                app.get(
                    url,
                    headers = getHeaders(mainUrl)
                )

            val document =
                response.document

            // -------------------------------------------------
            // TITLE
            // -------------------------------------------------

            val titleElement =
                document.selectFirst(
                    "h1, .box_film_title, .title, h2"
                )

            val title =
                if (titleElement != null) {

                    titleElement
                        .text()
                        .trim()
                        .ifBlank {
                            "Không tên"
                        }

                } else {

                    "Không tên"
                }

            // -------------------------------------------------
            // POSTER
            // -------------------------------------------------

            var poster: String? = null

            val posterElement =
                document.selectFirst(posterSelector)

            if (posterElement != null) {

                val dataSrc =
                    posterElement
                        .attr("data-src")
                        .trim()

                val dataOriginal =
                    posterElement
                        .attr("data-original")
                        .trim()

                val src =
                    posterElement
                        .attr("src")
                        .trim()

                poster =
                    when {

                        dataSrc.isNotBlank() ->
                            dataSrc

                        dataOriginal.isNotBlank() ->
                            dataOriginal

                        src.isNotBlank() ->
                            src

                        else ->
                            null
                    }
            }

            // -------------------------------------------------
            // DESCRIPTION
            // -------------------------------------------------

            var plot: String? = null

            val descriptionElement =
                document.selectFirst(
                    descriptionSelector
                )

            if (descriptionElement != null) {

                plot =
                    descriptionElement
                        .text()
                        .trim()
            }

            // -------------------------------------------------
            // EPISODES
            // -------------------------------------------------

            val episodes =
                mutableListOf<Episode>()

            val episodeElements =
                document.select(
                    episodeSelector
                )

            episodeElements.forEachIndexed {
                    index,
                    element ->

                val episodeUrl =
                    element
                        .attr("href")
                        .trim()

                if (episodeUrl.isBlank()) {
                    return@forEachIndexed
                }

                var episodeName =
                    element
                        .text()
                        .trim()

                if (episodeName.isBlank()) {

                    episodeName =
                        "Tập ${index + 1}"
                }

                episodes.add(
                    newEpisode(
                        data = fixUrl(episodeUrl)
                    ) {

                        name =
                            episodeName
                    }
                )
            }

            // -------------------------------------------------
            // NẾU KHÔNG CÓ TẬP
            // -------------------------------------------------

            if (episodes.isEmpty()) {

                episodes.add(
                    newEpisode(
                        data = url
                    ) {

                        name = "Full"
                    }
                )
            }

            // -------------------------------------------------
            // RETURN
            // -------------------------------------------------

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {

                posterUrl =
                    fixUrlNull(poster)

                this.plot =
                    plot
            }

        } catch (_: Exception) {

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

        var found = false

        try {

            // =================================================
            // 1. MỞ TRANG TẬP
            // =================================================

            val response =
                app.get(
                    data,
                    headers = getHeaders(mainUrl)
                )

            val html =
                response.text

            val document =
                response.document

            // =================================================
            // 2. TÌM IFRAME
            // =================================================

            val iframeUrls =
                mutableListOf<String>()

            val iframeElements =
                document.select("iframe")

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

                if (iframeUrl.isNotBlank()) {

                    iframeUrls.add(
                        fixUrl(iframeUrl)
                    )
                }
            }

            // =================================================
            // 3. TÌM IFRAME TRONG HTML
            // =================================================

            val iframeRegex =
                Regex(
                    """(?:src|data-src)\s*=\s*["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                )

            for (match in iframeRegex.findAll(html)) {

                val value =
                    match
                        .groupValues
                        .getOrNull(1)
                        ?.trim()

                if (!value.isNullOrBlank()) {

                    iframeUrls.add(
                        fixUrl(value)
                    )
                }
            }

            // =================================================
            // 4. TÌM VN2DATA TRONG HTML
            // =================================================

            val vn2dataRegex =
                Regex(
                    """https?://[^"'\s<>]+vn2data[^"'\s<>]+""",
                    RegexOption.IGNORE_CASE
                )

            for (match in vn2dataRegex.findAll(html)) {

                iframeUrls.add(
                    match.value
                )
            }

            // =================================================
            // 5. LOẠI LINK TRÙNG
            // =================================================

            val uniqueUrls =
                iframeUrls
                    .map { it.trim() }
                    .filter {
                        it.startsWith("http://") ||
                                it.startsWith("https://")
                    }
                    .distinct()

            // =================================================
            // 6. XỬ LÝ IFRAME
            // =================================================

            for (iframeUrl in uniqueUrls) {

                try {

                    val frameResponse =
                        app.get(
                            iframeUrl,
                            headers = getHeaders(data)
                        )

                    val frameHtml =
                        frameResponse.text

                    // =========================================
                    // DIRECT MP4 / M3U8
                    // =========================================

                    val directVideos =
                        extractVideoUrls(
                            frameHtml
                        )

                    for (videoUrl in directVideos) {

                        val serverName =
                            if (
                                videoUrl.contains(
                                    "cloudcdnvn",
                                    ignoreCase = true
                                )
                            ) {

                                "CloudCDN"

                            } else {

                                "VN2 Server"
                            }

                        addVideoLink(
                            videoUrl,
                            serverName,
                            iframeUrl,
                            callback
                        )

                        found = true
                    }

                    // =========================================
                    // link_video_sd
                    // =========================================

                    val sd =
                        extractJsVariable(
                            frameHtml,
                            "link_video_sd"
                        )

                    if (!sd.isNullOrBlank()) {

                        addVideoLink(
                            sd,
                            "CloudCDN SD",
                            iframeUrl,
                            callback
                        )

                        found = true
                    }

                    // =========================================
                    // link_video_hd
                    // =========================================

                    val hd =
                        extractJsVariable(
                            frameHtml,
                            "link_video_hd"
                        )

                    if (!hd.isNullOrBlank()) {

                        addVideoLink(
                            hd,
                            "CloudCDN HD",
                            iframeUrl,
                            callback
                        )

                        found = true
                    }

                    // =========================================
                    // php_content_embed
                    // =========================================

                    val phpEmbed =
                        extractJsVariable(
                            frameHtml,
                            "php_content_embed"
                        )

                    if (
                        !phpEmbed.isNullOrBlank() &&
                        !phpEmbed.contains(
                            "loi3.htm",
                            ignoreCase = true
                        )
                    ) {

                        val embedUrl =
                            fixUrl(phpEmbed)

                        // -------------------------------------
                        // MỞ PLAY2.PHP
                        // -------------------------------------

                        val embedResponse =
                            app.get(
                                embedUrl,
                                headers =
                                    getHeaders(iframeUrl)
                            )

                        val embedHtml =
                            embedResponse.text

                        // -------------------------------------
                        // VIDEO TRỰC TIẾP
                        // -------------------------------------

                        val embedVideos =
                            extractVideoUrls(
                                embedHtml
                            )

                        for (videoUrl in embedVideos) {

                            addVideoLink(
                                videoUrl,
                                "CloudCDN",
                                embedUrl,
                                callback
                            )

                            found = true
                        }

                        // -------------------------------------
                        // SD
                        // -------------------------------------

                        val embedSd =
                            extractJsVariable(
                                embedHtml,
                                "link_video_sd"
                            )

                        if (!embedSd.isNullOrBlank()) {

                            addVideoLink(
                                embedSd,
                                "CloudCDN SD",
                                embedUrl,
                                callback
                            )

                            found = true
                        }

                        // -------------------------------------
                        // HD
                        // -------------------------------------

                        val embedHd =
                            extractJsVariable(
                                embedHtml,
                                "link_video_hd"
                            )

                        if (!embedHd.isNullOrBlank()) {

                            addVideoLink(
                                embedHd,
                                "CloudCDN HD",
                                embedUrl,
                                callback
                            )

                            found = true
                        }
                    }

                } catch (_: Exception) {

                    // iframe lỗi -> thử iframe tiếp
                }
            }

            // =================================================
            // 7. TÌM PLAY.PHP TRỰC TIẾP
            // =================================================

            val playRegex =
                Regex(
                    """https?://[^"'\s<>]+/(?:play|play2)\.php[^"'\s<>]*""",
                    RegexOption.IGNORE_CASE
                )

            val playUrls =
                playRegex
                    .findAll(html)
                    .map {
                        it.value
                            .replace(
                                "&amp;",
                                "&"
                            )
                    }
                    .distinct()
                    .toList()

            for (playUrl in playUrls) {

                try {

                    val playResponse =
                        app.get(
                            playUrl,
                            headers = getHeaders(data)
                        )

                    val playHtml =
                        playResponse.text

                    // -----------------------------------------
                    // SD
                    // -----------------------------------------

                    val sd =
                        extractJsVariable(
                            playHtml,
                            "link_video_sd"
                        )

                    if (!sd.isNullOrBlank()) {

                        addVideoLink(
                            sd,
                            "CloudCDN SD",
                            playUrl,
                            callback
                        )

                        found = true
                    }

                    // -----------------------------------------
                    // HD
                    // -----------------------------------------

                    val hd =
                        extractJsVariable(
                            playHtml,
                            "link_video_hd"
                        )

                    if (!hd.isNullOrBlank()) {

                        addVideoLink(
                            hd,
                            "CloudCDN HD",
                            playUrl,
                            callback
                        )

                        found = true
                    }

                    // -----------------------------------------
                    // MP4 / M3U8
                    // -----------------------------------------

                    val videos =
                        extractVideoUrls(
                            playHtml
                        )

                    for (videoUrl in videos) {

                        addVideoLink(
                            videoUrl,
                            "CloudCDN",
                            playUrl,
                            callback
                        )

                        found = true
                    }

                } catch (_: Exception) {

                    // bỏ qua
                }
            }

        } catch (_: Exception) {

            return false
        }

        return found
    }

    // =========================================================
    // THÊM VIDEO LINK
    // =========================================================

    private suspend fun addVideoLink(
        url: String,
        serverName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {

        if (url.isBlank()) {
            return
        }

        if (
            !url.startsWith("http://") &&
            !url.startsWith("https://")
        ) {
            return
        }

        // -----------------------------------------------------
        // QUALITY
        // -----------------------------------------------------

        val quality =
            when {

                serverName.contains(
                    "1080",
                    ignoreCase = true
                ) -> 1080

                serverName.contains(
                    "FHD",
                    ignoreCase = true
                ) -> 1080

                serverName.contains(
                    "HD",
                    ignoreCase = true
                ) -> 720

                serverName.contains(
                    "SD",
                    ignoreCase = true
                ) -> 480

                else -> 0
            }

        // -----------------------------------------------------
        // TYPE
        // -----------------------------------------------------

        val linkType =
            if (
                url.contains(
                    ".m3u8",
                    ignoreCase = true
                )
            ) {

                ExtractorLinkType.M3U8

            } else {

                ExtractorLinkType.VIDEO
            }

        // -----------------------------------------------------
        // CALLBACK
        // -----------------------------------------------------

        try {

            callback(
                newExtractorLink(
                    name,
                    serverName,
                    url,
                    linkType
                ) {

                    this.referer =
                        referer

                    this.quality =
                        quality
                }
            )

        } catch (_: Exception) {

            // Không làm plugin crash
        }
    }

    // =========================================================
    // ĐỌC:
    //
    // var link_video_sd = "https://....mp4";
    //
    // =========================================================

    private fun extractJsVariable(
        html: String,
        variable: String
    ): String? {

        val regex =
            Regex(
                """(?:var\s+)?$variable\s*=\s*["']([^"']*)["']""",
                RegexOption.IGNORE_CASE
            )

        val match =
            regex.find(html)

        if (match == null) {
            return null
        }

        val value =
            match
                .groupValues
                .getOrNull(1)
                ?.trim()

        if (value.isNullOrBlank()) {
            return null
        }

        if (
            !value.startsWith("http://") &&
            !value.startsWith("https://")
        ) {
            return null
        }

        return value
    }

    // =========================================================
    // TÌM MP4 / M3U8
    // =========================================================

    private fun extractVideoUrls(
        html: String
    ): List<String> {

        val results =
            mutableListOf<String>()

        val regex =
            Regex(
                """https?://[^"'\s<>]+(?:\.mp4|\.m3u8)(?:\?[^"'\s<>]*)?""",
                RegexOption.IGNORE_CASE
            )

        for (match in regex.findAll(html)) {

            var url =
                match.value

            url =
                url
                    .replace(
                        "\\/",
                        "/"
                    )
                    .replace(
                        "&amp;",
                        "&"
                    )

            if (
                url.startsWith("http://") ||
                url.startsWith("https://")
            ) {

                if (
                    !url.endsWith(".js") &&
                    !url.endsWith(".css")
                ) {

                    results.add(url)
                }
            }
        }

        // -----------------------------------------------------
        // LINK BỊ ESCAPE TRONG JAVASCRIPT
        // -----------------------------------------------------

        val escapedRegex =
            Regex(
                """https?:\\?/\\?/[^"'\s<>]+(?:\.mp4|\.m3u8)[^"'\s<>]*""",
                RegexOption.IGNORE_CASE
            )

        for (match in escapedRegex.findAll(html)) {

            var url =
                match.value

            url =
                url
                    .replace(
                        "\\/",
                        "/"
                    )
                    .replace(
                        "&amp;",
                        "&"
                    )

            if (
                url.contains(
                    ".mp4",
                    ignoreCase = true
                ) ||
                url.contains(
                    ".m3u8",
                    ignoreCase = true
                )
            ) {

                if (
                    url.startsWith("http://") ||
                    url.startsWith("https://")
                ) {

                    results.add(url)
                }
            }
        }

        return results.distinct()
    }
}