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
    // HEADER
    // =========================================================

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val requestHeaders = mapOf(
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

            val document = app.get(
                request.data,
                headers = requestHeaders,
                referer = mainUrl
            ).document

            val results = ArrayList<SearchResponse>()

            val elements = document.select(
                "div.Form2, div.boxtk"
            )

            for (element in elements) {

                val result = parseSearchResult(element)

                if (result != null) {
                    results.add(result)
                }
            }

            return newHomePageResponse(
                request.name,
                results.distinctBy { it.url }
            )

        } catch (e: Exception) {

            println(
                "VN2 MAIN ERROR: ${e.message}"
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

        val url = "$mainUrl/tim-kiem/$slug"

        println(
            "VN2 SEARCH URL = $url"
        )

        try {

            val document = app.get(
                url,
                headers = requestHeaders,
                referer = mainUrl
            ).document

            val results = ArrayList<SearchResponse>()

            val elements = document.select(
                "div.Form2, div.boxtk"
            )

            for (element in elements) {

                val result = parseSearchResult(element)

                if (result != null) {
                    results.add(result)
                }
            }

            return results.distinctBy {
                it.url
            }

        } catch (e: Exception) {

            println(
                "VN2 SEARCH ERROR: ${e.message}"
            )

            return emptyList()
        }
    }

    // =========================================================
    // SEARCH RESULT
    // =========================================================

    private fun parseSearchResult(
        element: Element
    ): SearchResponse? {

        val linkElement = element.selectFirst("a")

        if (linkElement == null) {
            return null
        }

        val href = linkElement
            .attr("href")
            .trim()

        if (href.isBlank()) {
            return null
        }

        val url = fixUrl(href)

        // -----------------------------------------------------
        // TITLE
        // -----------------------------------------------------

        var title = ""

        val nameElement =
            element.selectFirst("p.nametk a")

        if (nameElement != null) {
            title = nameElement.text().trim()
        }

        if (title.isBlank()) {
            title = linkElement
                .attr("title")
                .trim()
        }

        if (title.isBlank()) {
            title = linkElement
                .text()
                .trim()
        }

        if (title.isBlank()) {
            return null
        }

        // -----------------------------------------------------
        // POSTER
        // -----------------------------------------------------

        var poster: String? = null

        val image = element.selectFirst(
            "img.c10, div.boxtk_img img, img"
        )

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
                poster = fixUrl(imageUrl)
            }
        }

        return newMovieSearchResponse(
            title,
            url,
            TvType.Movie
        ) {
            posterUrl = poster
        }
    }

    // =========================================================
    // LOAD
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        try {

            println(
                "VN2 LOAD = $url"
            )

            val response = app.get(
                url,
                headers = requestHeaders,
                referer = mainUrl
            )

            val document = response.document

            // -------------------------------------------------
            // TITLE
            // -------------------------------------------------

            var title = ""

            val h1 =
                document.selectFirst("h1")

            if (h1 != null) {
                title = h1.text().trim()
            }

            if (title.isBlank()) {

                val boxTitle =
                    document.selectFirst(".box_film_title")

                if (boxTitle != null) {
                    title = boxTitle.text().trim()
                }
            }

            if (title.isBlank()) {

                val titleElement =
                    document.selectFirst(".title")

                if (titleElement != null) {
                    title = titleElement.text().trim()
                }
            }

            if (title.isBlank()) {
                title = "Không tên"
            }

            // -------------------------------------------------
            // POSTER
            // -------------------------------------------------

            var poster: String? = null

            val posterElement = document.selectFirst(
                "img.c10, " +
                        ".info-film img, " +
                        ".box_film img, " +
                        ".film-info img, " +
                        "img.poster, " +
                        "img.avatar"
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
                    poster = fixUrl(value)
                }
            }

            // -------------------------------------------------
            // DESCRIPTION
            // -------------------------------------------------

            var plot: String? = null

            val description =
                document.selectFirst(
                    "div.wiew_info p, " +
                            "div.info-film, " +
                            ".description, " +
                            ".desc"
                )

            if (description != null) {

                val value =
                    description.text().trim()

                if (value.isNotBlank()) {
                    plot = value
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
                        extractEpisodeName(
                            episodeUrl
                        )
                }

                episodes.add(
                    newEpisode(
                        episodeUrl
                    ) {
                        name = episodeName
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

                var playUrl = ""

                val playButton =
                    document.selectFirst(
                        "div.playphim a, " +
                                "a.btn-play, " +
                                ".play-btn a"
                    )

                if (playButton != null) {

                    playUrl =
                        playButton
                            .attr("href")
                            .trim()
                }

                if (playUrl.isBlank()) {
                    playUrl = url
                } else {
                    playUrl = fixUrl(playUrl)
                }

                val episode =
                    newEpisode(playUrl) {
                        name = "Full"
                    }

                return newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    listOf(episode)
                ) {
                    posterUrl = poster
                    this.plot = plot
                }
            }

            // -------------------------------------------------
            // RETURN
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

        println("====================================")
        println("VN2 LOADLINK START")
        println("VN2 EPISODE = $data")
        println("====================================")

        try {

            // -------------------------------------------------
            // STEP 1
            // OPEN EPISODE
            // -------------------------------------------------

            val response = app.get(
                data,
                headers = requestHeaders,
                referer = mainUrl
            )

            val html = response.text

            println(
                "VN2 EPISODE HTML LENGTH = ${html.length}"
            )

            // -------------------------------------------------
            // STEP 2
            // SEARCH DIRECT VIDEO
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

                println(
                    "VN2 DIRECT SD = $directSd"
                )

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

                println(
                    "VN2 DIRECT HD = $directHd"
                )

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
                    "VN2 FOUND DIRECT VIDEO"
                )

                return true
            }

            // -------------------------------------------------
            // STEP 3
            // FIND IFRAME
            // -------------------------------------------------

            val iframeElements =
                response.document.select("iframe")

            println(
                "VN2 IFRAME COUNT = ${iframeElements.size}"
            )

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
                // VN2DATA
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
                            callback
                        )

                    if (result) {
                        found = true
                    }
                }

                // -------------------------------------------------
                // PLAY.PHP
                // -------------------------------------------------

                else if (
                    iframeUrl.contains(
                        "play.php",
                        ignoreCase = true
                    )
                ) {

                    val result =
                        loadVn2Data(
                            iframeUrl,
                            data,
                            callback
                        )

                    if (result) {
                        found = true
                    }
                }

                // -------------------------------------------------
                // OTHER IFRAME
                // -------------------------------------------------

                else {

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
            // STEP 4
            // SEARCH MP4 IN EPISODE HTML
            // -------------------------------------------------

            if (!found) {

                val mp4Urls =
                    findMp4Urls(html)

                println(
                    "VN2 EPISODE MP4 COUNT = ${mp4Urls.size}"
                )

                for (videoUrl in mp4Urls) {

                    addVideoLink(
                        videoUrl,
                        "CloudCDN",
                        data,
                        callback
                    )

                    found = true
                }
            }

            // -------------------------------------------------
            // STEP 5
            // SEARCH M3U8
            // -------------------------------------------------

            if (!found) {

                val m3u8Urls =
                    findM3u8Urls(html)

                println(
                    "VN2 EPISODE M3U8 COUNT = ${m3u8Urls.size}"
                )

                for (videoUrl in m3u8Urls) {

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
    // VN2DATA
    // =========================================================

    private suspend fun loadVn2Data(
        url: String,
        episodeUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        println("------------------------------------")
        println("VN2DATA REQUEST")
        println(url)
        println("------------------------------------")

        try {

            val response =
                app.get(
                    url,
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
                "VN2DATA HTML LENGTH = ${html.length}"
            )

            // -------------------------------------------------
            // SD
            // -------------------------------------------------

            val sd =
                findVariable(
                    html,
                    "link_video_sd"
                )

            // -------------------------------------------------
            // HD
            // -------------------------------------------------

            val hd =
                findVariable(
                    html,
                    "link_video_hd"
                )

            // -------------------------------------------------
            // PLAY2
            // -------------------------------------------------

            val play2 =
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
                "VN2DATA PLAY2 = $play2"
            )

            var found = false

            // -------------------------------------------------
            // SD LINK
            // -------------------------------------------------

            if (!sd.isNullOrBlank()) {

                addVideoLink(
                    sd,
                    "CloudCDN SD",
                    url,
                    callback
                )

                found = true
            }

            // -------------------------------------------------
            // HD LINK
            // -------------------------------------------------

            if (
                !hd.isNullOrBlank() &&
                hd != sd
            ) {

                addVideoLink(
                    hd,
                    "CloudCDN HD",
                    url,
                    callback
                )

                found = true
            }

            // -------------------------------------------------
            // MP4 SEARCH
            // -------------------------------------------------

            if (!found) {

                val mp4Urls =
                    findMp4Urls(html)

                println(
                    "VN2DATA MP4 COUNT = ${mp4Urls.size}"
                )

                for (videoUrl in mp4Urls) {

                    addVideoLink(
                        videoUrl,
                        "CloudCDN",
                        url,
                        callback
                    )

                    found = true
                }
            }

            // -------------------------------------------------
            // M3U8 SEARCH
            // -------------------------------------------------

            if (!found) {

                val m3u8Urls =
                    findM3u8Urls(html)

                println(
                    "VN2DATA M3U8 COUNT = ${m3u8Urls.size}"
                )

                for (videoUrl in m3u8Urls) {

                    addVideoLink(
                        videoUrl,
                        "CloudCDN HLS",
                        url,
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
                !play2.isNullOrBlank() &&
                play2.startsWith("http")
            ) {

                println(
                    "VN2DATA TRY PLAY2"
                )

                try {

                    val response2 =
                        app.get(
                            play2,
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Accept" to "*/*",
                                "Referer" to url
                            ),
                            referer = url
                        )

                    val html2 =
                        response2.text

                    println(
                        "VN2DATA PLAY2 HTML = ${html2.length}"
                    )

                    val sd2 =
                        findVariable(
                            html2,
                            "link_video_sd"
                        )

                    val hd2 =
                        findVariable(
                            html2,
                            "link_video_hd"
                        )

                    println(
                        "VN2DATA PLAY2 SD = $sd2"
                    )

                    println(
                        "VN2DATA PLAY2 HD = $hd2"
                    )

                    if (!sd2.isNullOrBlank()) {

                        addVideoLink(
                            sd2,
                            "CloudCDN SD",
                            play2,
                            callback
                        )

                        found = true
                    }

                    if (
                        !hd2.isNullOrBlank() &&
                        hd2 != sd2
                    ) {

                        addVideoLink(
                            hd2,
                            "CloudCDN HD",
                            play2,
                            callback
                        )

                        found = true
                    }

                    if (!found) {

                        val mp4 =
                            findMp4Urls(html2)

                        for (videoUrl in mp4) {

                            addVideoLink(
                                videoUrl,
                                "CloudCDN",
                                play2,
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

            println(
                "VN2DATA FOUND = $found"
            )

            return found

        } catch (e: Exception) {

            println(
                "VN2DATA ERROR = ${e.message}"
            )

            return false
        }
    }

    // =========================================================
    // ADD EXTRACTOR LINK
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

        println(
            "VN2 ADD LINK = $url"
        )

        callback.invoke(
            newExtractorLink(
                source = name,
                name = serverName,
                url = url
            ) {

                this.referer = referer

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

        val regex =
            Regex(
                """(?:var\s+|let\s+|const\s+)?$variable\s*=\s*["']([^"']*)["']""",
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

    private fun extractEpisodeName(
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
            return "Tập ${match.groupValues[1]}"
        }

        return "Tập"
    }

    // =========================================================
    // SLUG
    // =========================================================

    private fun String.toSlug(): String {

        var value =
            trim().lowercase()

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