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
    // CONFIG
    // =========================================================

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to userAgent,
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

        try {

            val url = request.data

            println("VN2 MAIN URL = $url")

            val document = app.get(
                url,
                headers = headers,
                referer = mainUrl
            ).document

            val results = ArrayList<SearchResponse>()

            val elements = document.select(
                "div.Form2, div.boxtk"
            )

            for (element in elements) {

                val result = element.toSearchResult()

                if (result != null) {
                    results.add(result)
                }
            }

            println(
                "VN2 MAIN RESULTS = ${results.size}"
            )

            return newHomePageResponse(
                request.name,
                results
            )

        } catch (e: Exception) {

            println(
                "VN2 MAIN ERROR = ${e.message}"
            )

            return newHomePageResponse(
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

        val slug = query.toSlug()

        val url =
            "$mainUrl/tim-kiem/$slug"

        println(
            "VN2 SEARCH = $url"
        )

        try {

            val document = app.get(
                url,
                headers = headers,
                referer = mainUrl
            ).document

            val results =
                ArrayList<SearchResponse>()

            val elements = document.select(
                "div.Form2, div.boxtk"
            )

            for (element in elements) {

                val result =
                    element.toSearchResult()

                if (result != null) {
                    results.add(result)
                }
            }

            println(
                "VN2 SEARCH RESULTS = ${results.size}"
            )

            return results.distinctBy {
                it.url
            }

        } catch (e: Exception) {

            println(
                "VN2 SEARCH ERROR = ${e.message}"
            )

            return emptyList()
        }
    }

    // =========================================================
    // SEARCH RESULT
    // =========================================================

    private fun Element.toSearchResult(): SearchResponse? {

        val linkElement =
            selectFirst("a")

        if (linkElement == null) {
            return null
        }

        val href =
            linkElement.attr("href").trim()

        if (href.isBlank()) {
            return null
        }

        val url =
            fixUrl(href)

        // -----------------------------------------------------
        // TITLE
        // -----------------------------------------------------

        var title = ""

        val nameElement =
            selectFirst("p.nametk a")

        if (nameElement != null) {
            title =
                nameElement.text().trim()
        }

        if (title.isBlank()) {
            title =
                linkElement.attr("title").trim()
        }

        if (title.isBlank()) {
            title =
                linkElement.text().trim()
        }

        if (title.isBlank()) {
            return null
        }

        // -----------------------------------------------------
        // POSTER
        // -----------------------------------------------------

        var poster: String? = null

        val img =
            selectFirst(
                "img.c10, div.boxtk_img img, img"
            )

        if (img != null) {

            var imageUrl =
                img.attr("data-src").trim()

            if (imageUrl.isBlank()) {
                imageUrl =
                    img.attr("data-original").trim()
            }

            if (imageUrl.isBlank()) {
                imageUrl =
                    img.attr("src").trim()
            }

            if (imageUrl.isNotBlank()) {
                poster =
                    fixUrl(imageUrl)
            }
        }

        // -----------------------------------------------------
        // RESPONSE
        // -----------------------------------------------------

        return newMovieSearchResponse(
            title,
            url,
            TvType.Movie
        ) {
            posterUrl = poster
        }
    }

    // =========================================================
    // LOAD MOVIE
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        println(
            "VN2 LOAD = $url"
        )

        try {

            val response =
                app.get(
                    url,
                    headers = headers,
                    referer = mainUrl
                )

            val document =
                response.document

            // -------------------------------------------------
            // TITLE
            // -------------------------------------------------

            var title = ""

            val h1 =
                document.selectFirst("h1")

            if (h1 != null) {
                title =
                    h1.text().trim()
            }

            if (title.isBlank()) {

                val boxTitle =
                    document.selectFirst(
                        ".box_film_title"
                    )

                if (boxTitle != null) {
                    title =
                        boxTitle.text().trim()
                }
            }

            if (title.isBlank()) {

                val titleElement =
                    document.selectFirst(".title")

                if (titleElement != null) {
                    title =
                        titleElement.text().trim()
                }
            }

            if (title.isBlank()) {
                title = "Không tên"
            }

            // -------------------------------------------------
            // POSTER
            // -------------------------------------------------

            var poster: String? = null

            val posterElement =
                document.selectFirst(
                    "img.c10, " +
                            ".info-film img, " +
                            ".box_film img, " +
                            ".film-info img, " +
                            "img.poster, " +
                            "img.avatar"
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
                    poster =
                        fixUrl(posterValue)
                }
            }

            // -------------------------------------------------
            // PLOT
            // -------------------------------------------------

            var plot: String? = null

            val plotElement =
                document.selectFirst(
                    "div.wiew_info p, " +
                            "div.info-film, " +
                            ".description, " +
                            ".desc"
                )

            if (plotElement != null) {

                val plotValue =
                    plotElement.text().trim()

                if (plotValue.isNotBlank()) {
                    plot = plotValue
                }
            }

            // -------------------------------------------------
            // EPISODES
            // -------------------------------------------------

            val episodes =
                ArrayList<Episode>()

            val episodeElements =
                document.select(
                    "div.num_film a, " +
                            ".list-episode a, " +
                            ".episodes a, " +
                            ".episode a, " +
                            "a[href*='/tap-']"
                )

            for (element in episodeElements) {

                val href =
                    element.attr("href").trim()

                if (href.isBlank()) {
                    continue
                }

                val episodeUrl =
                    fixUrl(href)

                var episodeName =
                    element.text().trim()

                if (episodeName.isBlank()) {

                    episodeName =
                        getEpisodeName(
                            episodeUrl
                        )
                }

                episodes.add(
                    newEpisode(
                        episodeUrl
                    ) {
                        name =
                            episodeName.ifBlank {
                                "Tập"
                            }
                    }
                )
            }

            val uniqueEpisodes =
                episodes.distinctBy {
                    it.data
                }

            // -------------------------------------------------
            // NO EPISODE
            // -------------------------------------------------

            if (uniqueEpisodes.isEmpty()) {

                var playUrl: String? = null

                val playElement =
                    document.selectFirst(
                        "div.playphim a, " +
                                "a.btn-play, " +
                                ".play-btn a"
                    )

                if (playElement != null) {

                    val href =
                        playElement
                            .attr("href")
                            .trim()

                    if (href.isNotBlank()) {
                        playUrl =
                            fixUrl(href)
                    }
                }

                val finalUrl =
                    if (playUrl != null) {
                        playUrl
                    } else {
                        url
                    }

                val fullEpisode =
                    newEpisode(
                        finalUrl
                    ) {
                        name = "Full"
                    }

                return newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    listOf(fullEpisode)
                ) {
                    posterUrl = poster
                    this.plot = plot
                }
            }

            // -------------------------------------------------
            // RETURN SERIES
            // -------------------------------------------------

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                uniqueEpisodes
            ) {
                posterUrl = poster
                this.plot = plot
            }

        } catch (e: Exception) {

            println(
                "VN2 LOAD ERROR = ${e.message}"
            )

            return null
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

        println("================================")
        println("VN2 LOAD LINKS")
        println("DATA = $data")
        println("================================")

        try {

            // -------------------------------------------------
            // 1. GET EPISODE PAGE
            // -------------------------------------------------

            val episodeResponse =
                app.get(
                    data,
                    headers = headers,
                    referer = mainUrl
                )

            val html =
                episodeResponse.text

            println(
                "VN2 EPISODE HTML = ${html.length}"
            )

            // -------------------------------------------------
            // 2. DIRECT VIDEO FROM EPISODE
            // -------------------------------------------------

            var found = false

            val directSd =
                findVariable(
                    html,
                    "link_video_sd"
                )

            val directHd =
                findVariable(
                    html,
                    "link_video_hd"
                )

            if (!directSd.isNullOrBlank()) {

                addVideoLink(
                    directSd,
                    "CloudCDN SD",
                    data,
                    callback
                )

                found = true
            }

            if (
                !directHd.isNullOrBlank() &&
                directHd != directSd
            ) {

                addVideoLink(
                    directHd,
                    "CloudCDN HD",
                    data,
                    callback
                )

                found = true
            }

            if (found) {

                println(
                    "VN2 DIRECT LINK FOUND"
                )

                return true
            }

            // -------------------------------------------------
            // 3. FIND IFRAME
            // -------------------------------------------------

            val iframeElements =
                episodeResponse.document
                    .select("iframe")

            for (iframe in iframeElements) {

                var iframeUrl =
                    iframe.attr("src").trim()

                if (iframeUrl.isBlank()) {

                    iframeUrl =
                        iframe
                            .attr("data-src")
                            .trim()
                }

                if (iframeUrl.isBlank()) {
                    continue
                }

                if (iframeUrl.startsWith("//")) {
                    iframeUrl =
                        "https:$iframeUrl"
                }

                if (iframeUrl.startsWith("/")) {
                    iframeUrl =
                        mainUrl + iframeUrl
                }

                println(
                    "VN2 IFRAME = $iframeUrl"
                )

                // -------------------------------------------------
                // 4. VN2DATA PLAY.PHP
                // -------------------------------------------------

                if (
                    iframeUrl.contains(
                        "vn2data",
                        ignoreCase = true
                    )
                ) {

                    val result =
                        loadVn2Data(
                            iframeUrl,
                            data,
                            subtitleCallback,
                            callback
                        )

                    if (result) {
                        found = true
                    }
                }

                // -------------------------------------------------
                // 5. OTHER EXTRACTOR
                // -------------------------------------------------

                else if (
                    iframeUrl.startsWith("http")
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
                            "VN2 EXTRACTOR ERROR = ${e.message}"
                        )
                    }
                }
            }

            // -------------------------------------------------
            // 6. HTML FALLBACK
            // -------------------------------------------------

            if (!found) {

                val mp4 =
                    findMp4Urls(html)

                for (videoUrl in mp4) {

                    addVideoLink(
                        videoUrl,
                        "CloudCDN",
                        data,
                        callback
                    )

                    found = true
                }
            }

            if (!found) {

                val m3u8 =
                    findM3u8Urls(html)

                for (videoUrl in m3u8) {

                    addVideoLink(
                        videoUrl,
                        "CloudCDN HLS",
                        data,
                        callback
                    )

                    found = true
                }
            }

            println(
                "VN2 FINAL_FOUND = $found"
            )

            return found

        } catch (e: Exception) {

            println(
                "VN2 LOADLINK ERROR = ${e.message}"
            )

            e.printStackTrace()

            return false
        }
    }

    // =========================================================
    // LOAD VN2DATA
    // =========================================================

    private suspend fun loadVn2Data(
        playUrl: String,
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        println(
            "VN2DATA URL = $playUrl"
        )

        try {

            val response =
                app.get(
                    playUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Accept" to "*/*",
                        "Referer" to episodeUrl
                    ),
                    referer = episodeUrl
                )

            val html =
                response.text

            println(
                "VN2DATA HTML = ${html.length}"
            )

            // -------------------------------------------------
            // LINK VIDEO SD
            // -------------------------------------------------

            val sd =
                findVariable(
                    html,
                    "link_video_sd"
                )

            // -------------------------------------------------
            // LINK VIDEO HD
            // -------------------------------------------------

            val hd =
                findVariable(
                    html,
                    "link_video_hd"
                )

            // -------------------------------------------------
            // PHP CONTENT EMBED
            // -------------------------------------------------

            val phpEmbed =
                findVariable(
                    html,
                    "php_content_embed"
                )

            println(
                "VN2DATA SD = $sd"
            )

            println(
                "VN2DATA HD = $hd"
            )

            println(
                "VN2DATA EMBED = $phpEmbed"
            )

            var found = false

            // -------------------------------------------------
            // SD
            // -------------------------------------------------

            if (!sd.isNullOrBlank()) {

                addVideoLink(
                    sd,
                    "CloudCDN SD",
                    playUrl,
                    callback
                )

                found = true
            }

            // -------------------------------------------------
            // HD
            // -------------------------------------------------

            if (
                !hd.isNullOrBlank() &&
                hd != sd
            ) {

                addVideoLink(
                    hd,
                    "CloudCDN HD",
                    playUrl,
                    callback
                )

                found = true
            }

            // -------------------------------------------------
            // MP4 FALLBACK
            // -------------------------------------------------

            if (!found) {

                val mp4s =
                    findMp4Urls(html)

                println(
                    "VN2DATA MP4 COUNT = ${mp4s.size}"
                )

                for (url in mp4s) {

                    addVideoLink(
                        url,
                        "CloudCDN",
                        playUrl,
                        callback
                    )

                    found = true
                }
            }

            // -------------------------------------------------
            // M3U8 FALLBACK
            // -------------------------------------------------

            if (!found) {

                val m3u8s =
                    findM3u8Urls(html)

                println(
                    "VN2DATA M3U8 COUNT = ${m3u8s.size}"
                )

                for (url in m3u8s) {

                    addVideoLink(
                        url,
                        "CloudCDN HLS",
                        playUrl,
                        callback
                    )

                    found = true
                }
            }

            // -------------------------------------------------
            // PLAY2
            // -------------------------------------------------

            if (
                !found &&
                !phpEmbed.isNullOrBlank() &&
                phpEmbed.startsWith("http")
            ) {

                println(
                    "VN2DATA TRY PLAY2"
                )

                try {

                    val play2Response =
                        app.get(
                            phpEmbed,
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Accept" to "*/*",
                                "Referer" to playUrl
                            ),
                            referer = playUrl
                        )

                    val play2Html =
                        play2Response.text

                    val play2Sd =
                        findVariable(
                            play2Html,
                            "link_video_sd"
                        )

                    val play2Hd =
                        findVariable(
                            play2Html,
                            "link_video_hd"
                        )

                    println(
                        "VN2DATA PLAY2 SD = $play2Sd"
                    )

                    println(
                        "VN2DATA PLAY2 HD = $play2Hd"
                    )

                    if (!play2Sd.isNullOrBlank()) {

                        addVideoLink(
                            play2Sd,
                            "CloudCDN SD",
                            phpEmbed,
                            callback
                        )

                        found = true
                    }

                    if (
                        !play2Hd.isNullOrBlank() &&
                        play2Hd != play2Sd
                    ) {

                        addVideoLink(
                            play2Hd,
                            "CloudCDN HD",
                            phpEmbed,
                            callback
                        )

                        found = true
                    }

                    if (!found) {

                        val play2Mp4 =
                            findMp4Urls(
                                play2Html
                            )

                        for (url in play2Mp4) {

                            addVideoLink(
                                url,
                                "CloudCDN",
                                phpEmbed,
                                callback
                            )

                            found = true
                        }
                    }

                } catch (e: Exception) {

                    println(
                        "VN2DATA PLAY2 ERROR = ${e.message}"
                    )
                }
            }

            return found

        } catch (e: Exception) {

            println(
                "VN2DATA ERROR = ${e.message}"
            )

            return false
        }
    }

    // =========================================================
    // ADD VIDEO LINK
    // =========================================================

    /*
     * QUAN TRỌNG:
     *
     * Phải là suspend fun.
     *
     * Vì CloudStream SDK của project bạn báo:
     *
     * newExtractorLink(...) = suspend
     */

    private suspend fun addVideoLink(
        url: String,
        serverName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {

        if (url.isBlank()) {
            return
        }

        println(
            "VN2 ADD LINK [$serverName] = $url"
        )

        callback.invoke(
            newExtractorLink(
                source = name,
                name = serverName,
                url = url
            ) {

                this.referer =
                    referer

                /*
                 * Không dùng:
                 *
                 * Qualities.FHD
                 *
                 * vì SDK của bạn báo FHD không tồn tại.
                 */

                this.quality =
                    Qualities.Unknown.value
            }
        )
    }

    // =========================================================
    // FIND JS VARIABLE
    // =========================================================

    private fun findVariable(
        html: String,
        variable: String
    ): String? {

        val pattern =
            """(?:var\s+|let\s+|const\s+)?$variable\s*=\s*["']([^"']*)["']"""

        val regex =
            Regex(
                pattern,
                RegexOption.IGNORE_CASE
            )

        val match =
            regex.find(html)

        if (match == null) {
            return null
        }

        var value =
            match.groupValues[1].trim()

        if (value.isBlank()) {
            return null
        }

        value =
            value.replace(
                "\\/",
                "/"
            )

        value =
            value.replace(
                "&amp;",
                "&"
            )

        if (value.startsWith("//")) {
            value =
                "https:$value"
        }

        return value
    }

    // =========================================================
    // FIND MP4
    // =========================================================

    private fun findMp4Urls(
        html: String
    ): List<String> {

        val result =
            LinkedHashSet<String>()

        val regex =
            Regex(
                """https?://[^"'\\\s<>]+\.mp4(?:\?[^"'\\\s<>]*)?""",
                RegexOption.IGNORE_CASE
            )

        for (match in regex.findAll(html)) {

            var url =
                match.value

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
    // FIND M3U8
    // =========================================================

    private fun findM3u8Urls(
        html: String
    ): List<String> {

        val result =
            LinkedHashSet<String>()

        val regex =
            Regex(
                """https?://[^"'\\\s<>]+\.m3u8(?:\?[^"'\\\s<>]*)?""",
                RegexOption.IGNORE_CASE
            )

        for (match in regex.findAll(html)) {

            var url =
                match.value

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
    // EPISODE NAME
    // =========================================================

    private fun getEpisodeName(
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

        return "Tập"
    }

    // =========================================================
    // VIETNAMESE SLUG
    // =========================================================

    private fun String.toSlug(): String {

        var text =
            trim().lowercase()

        text = text.replace(
            Regex(
                "[áàảãạăắằẳẵặâấầẩẫậ]"
            ),
            "a"
        )

        text = text.replace(
            Regex(
                "[éèẻẽẹêếềểễệ]"
            ),
            "e"
        )

        text = text.replace(
            Regex(
                "[íìỉĩị]"
            ),
            "i"
        )

        text = text.replace(
            Regex(
                "[óòỏõọôốồổỗộơớờởỡợ]"
            ),
            "o"
        )

        text = text.replace(
            Regex(
                "[úùủũụưứừửữự]"
            ),
            "u"
        )

        text = text.replace(
            Regex(
                "[ýỳỷỹỵ]"
            ),
            "y"
        )

        text =
            text.replace(
                "đ",
                "d"
            )

        text =
            text.replace(
                Regex("[^a-z0-9]+"),
                "-"
            )

        text =
            text.replace(
                Regex("-+"),
                "-"
            )

        return text.trim('-')
    }
}