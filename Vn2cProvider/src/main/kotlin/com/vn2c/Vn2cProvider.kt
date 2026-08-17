package com.vn2c

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Vn2cProvider : MainAPI() {
    override var mainUrl = "https://www.vn2c.my"

    // ĐÃ ĐỔI TÊN ĐỂ KIỂM TRA XEM ĐÃ CẬP NHẬT CHƯA
    override var name = "PhimVN2 (Đã Fix Search)"

    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    // ĐÃ THÊM 'div.boxtk' TỪ BỨC ẢNH F12 CỦA BẠN VÀO ĐÂY
    private val ITEM_SELECTOR = "div.Form2, div.boxtk"
    private val POSTER_SELECTOR = "img.c10, div.boxtk_img img, img"

    private val EPISODES_SELECTOR = "div.num_film a"
    private val DESCRIPTION_SELECTOR = "div.wiew_info p, div.info-film"

    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi-vn2-phim-2vn-phim/new" to "Phim Mới",
        "$mainUrl/danh-muc/trung-quoc-7" to "Phim Trung Quốc",
        "$mainUrl/danh-muc/han-quoc-10" to "Phim Hàn Quốc",
        "$mainUrl/danh-muc/thai-lan-8" to "Phim Thái Lan",
        "$mainUrl/the-loai2/hoat-hinh-anime-29" to "Hoạt Hình Anime"
    )

    // Khử dấu tiếng Việt
    private fun String.toSlug(): String {
        var str = this.trim().lowercase()
        val map = mapOf(
            'a' to Regex("[áàảãạăắằẳẵặâấầẩẫậ]"),
            'e' to Regex("[éèẻẽẹêếềểễệ]"),
            'i' to Regex("[íìỉĩị]"),
            'o' to Regex("[óòỏõọôốồổỗộơớờởỡợ]"),
            'u' to Regex("[úùủũụưứừửữự]"),
            'y' to Regex("[ýỳỷỹỵ]"),
            'd' to Regex("đ")
        )
        for ((repl, reg) in map) {
            str = str.replace(reg, repl.toString())
        }
        return str.replace(Regex("[^a-z0-9]"), "-").replace(Regex("-+"), "-")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select(ITEM_SELECTOR).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val formattedQuery = query.toSlug()
        val url = "$mainUrl/tim-kiem/$formattedQuery"

        val document = app.get(url).document
        return document.select(ITEM_SELECTOR).mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null

        // Hỗ trợ lấy tên phim từ p.nametk a (trang tìm kiếm) HOẶC title attr (trang chủ)
        var title = this.selectFirst("p.nametk a")?.text()
        if (title.isNullOrBlank()) {
            title = linkElement.attr("title").ifBlank { linkElement.text() }
        }
        if (title.isNullOrBlank()) return null

        val href = linkElement.attr("href")

        val posterElement = this.selectFirst(POSTER_SELECTOR)
        val posterUrl = posterElement?.attr("src")?.ifBlank { posterElement.attr("data-src") }

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .box_film_title, .title")?.text() ?: "Không tên"

        val posterElement = document.selectFirst(POSTER_SELECTOR)
        val poster = posterElement?.attr("src")?.ifBlank { posterElement.attr("data-src") }

        val plot = document.selectFirst(DESCRIPTION_SELECTOR)?.text()

        val episodes = mutableListOf<Episode>()
        document.select(EPISODES_SELECTOR).forEach { ep ->
            val epName = ep.text()
            val epHref = ep.attr("href")
            if (epHref.isNotBlank()) {
                episodes.add(newEpisode(data = fixUrl(epHref)) { this.name = epName })
            }
        }

        if (episodes.isEmpty()) {
            val playBtn = document.selectFirst("div.playphim a, a.btn-play")?.attr("href")
            if (playBtn != null) {
                episodes.add(newEpisode(data = fixUrl(playBtn)) { this.name = "Full" })
            } else {
                episodes.add(newEpisode(data = url) { this.name = "Full" })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var linkFound = false

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to mainUrl
        )

        val response = app.get(data, headers = headers)
        val document = response.document
        val mainHtml = response.text

        val iframes = mutableListOf<String>()

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) iframes.add(src)
        }

        Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""").findAll(mainHtml).forEach { match ->
            iframes.add(match.groupValues[1])
        }

        iframes.filter { it.isNotBlank() }.distinct().forEach { rawSrc ->
            var src = rawSrc
            if (src.startsWith("//")) src = "https:$src"
            if (src.startsWith("/")) src = "$mainUrl$src"

            if (src.contains("vn2data") || src.contains("play.php") || src.contains("cloudcdnvn") || src.contains("phim.php")) {
                try {
                    val iframeHtml = app.get(
                        src,
                        headers = mapOf(
                            "User-Agent" to headers["User-Agent"]!!,
                            "Referer" to data
                        )
                    ).text

                    val allUrlsRegex = Regex("""(https?://[^"'\s<>]+)""")
                    allUrlsRegex.findAll(iframeHtml).forEach { match ->
                        var urlString = match.groupValues[1]

                        if (urlString.contains("cloudcdnvn") || urlString.contains("cdninstagram") || urlString.contains(".mp4") || urlString.contains(".m3u8")) {
                            if (!urlString.endsWith(".js") && !urlString.endsWith(".css") && !urlString.endsWith(".jpg") && !urlString.endsWith(".png")) {

                                if (!urlString.contains(".mp4") && !urlString.contains(".m3u8")) {
                                    urlString += "#.mp4"
                                }

                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = if (urlString.contains("cloudcdnvn")) "CloudCDN Server" else "VIP Server",
                                        url = urlString
                                    ) {
                                        this.referer = src
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                linkFound = true
                            }
                        }
                    }

                    val phpRegex = Regex("""php_content_embed\s*=\s*["']([^"']+)["']""")
                    phpRegex.findAll(iframeHtml).forEach { match ->
                        var play2Link = match.groupValues[1]
                        if (play2Link.isNotBlank()) {
                            if (play2Link.startsWith("//")) play2Link = "https:$play2Link"
                            if (play2Link.startsWith("/")) play2Link = "https://vn2data.com$play2Link"

                            if (play2Link.startsWith("http")) {
                                loadExtractor(play2Link, src, subtitleCallback, callback)
                                linkFound = true
                            }
                        }
                    }
                } catch (e: Exception) {}
            } else if (src.startsWith("http")) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
                linkFound = true
            }
        }

        return linkFound
    }
}