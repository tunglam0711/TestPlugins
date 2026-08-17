package com.vn2c

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Vn2cProvider : MainAPI() {
    override var mainUrl = "https://www.vn2c.my"
    override var name = "PhimVN2 (vn2c)"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    // =========================================================================
    // --- CẤU HÌNH CSS SELECTORS ---
    // =========================================================================

    private val ITEM_SELECTOR = "div.Form2"
    private val LINK_SELECTOR = "a"
    private val POSTER_SELECTOR = "img.c10"

    private val EPISODES_SELECTOR = "div.num_film a"
    private val DESCRIPTION_SELECTOR = "div.wiew_info p"

    // =========================================================================

    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi-vn2-phim-2vn-phim/new" to "Phim Mới",
        "$mainUrl/danh-muc/trung-quoc-7" to "Phim Trung Quốc",
        "$mainUrl/danh-muc/han-quoc-10" to "Phim Hàn Quốc",
        "$mainUrl/danh-muc/thai-lan-8" to "Phim Thái Lan",
        "$mainUrl/the-loai2/hoat-hinh-anime-29" to "Hoạt Hình Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select(ITEM_SELECTOR).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/$query"
        val document = app.get(url).document
        return document.select(ITEM_SELECTOR).mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst(LINK_SELECTOR) ?: return null

        val title = linkElement.attr("title")
        if (title.isBlank()) return null

        val href = linkElement.attr("href")

        val posterElement = this.selectFirst(POSTER_SELECTOR)
        val posterUrl = posterElement?.attr("src") ?: posterElement?.attr("data-src")

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1, .box_film_title, .title")?.text() ?: "Không tên"
        val poster = document.selectFirst(POSTER_SELECTOR)?.attr("src")
        val plot = document.selectFirst(DESCRIPTION_SELECTOR)?.text()

        val episodes = mutableListOf<Episode>()
        document.select(EPISODES_SELECTOR).forEach { ep ->
            val epName = ep.text()
            val epHref = ep.attr("href")
            if (epHref.isNotBlank()) {
                episodes.add(newEpisode(data = fixUrl(epHref)) {
                    this.name = epName
                })
            }
        }

        // Trường hợp phim lẻ (có nút XEM PHIM nhưng không có danh sách tập)
        if (episodes.isEmpty()) {
            val playBtn = document.selectFirst("div.playphim a")?.attr("href")
            if (playBtn != null) {
                episodes.add(newEpisode(data = fixUrl(playBtn)) {
                    this.name = "Full"
                })
            } else {
                episodes.add(newEpisode(data = url) {
                    this.name = "Full"
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        var linkFound = false

        // 1. Quét iframe nhúng
        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src" // Sửa lỗi thiếu giao thức

            if (src.isNotBlank() && src.startsWith("http")) {
                // Gọi bộ giải mã mặc định của Cloudstream
                loadExtractor(src, mainUrl, subtitleCallback, callback)

                // --- PHẦN THÊM MỚI: XỬ LÝ RIÊNG CHO IFRAME VN2DATA (JWPLAYER) ---
                if (src.contains("vn2data") || src.contains("play.php")) {
                    val iframeText = app.get(src, referer = data).text

                    val jwFileRegex = Regex("""file\s*:\s*['"](http[^'"]+)['"]""")

                    jwFileRegex.findAll(iframeText).forEach { match ->
                        val videoLink = match.groupValues[1]
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "VN2Data Server",
                                url = videoLink
                            ) {
                                this.referer = src
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
                // ----------------------------------------------------------------

                linkFound = true
            }
        }

        // 2. Quét thẻ script để tìm link M3u8 hoặc Mp4 ẩn ở trang ngoài
        val scriptContent = document.select("script").joinToString("") { it.html() }
        val m3u8Regex = Regex("(?<=file: '|\")(.*?\\.m3u8.*?)(?='|\")")
        val mp4Regex = Regex("(?<=file: '|\")(.*?\\.mp4.*?)(?='|\")")

        m3u8Regex.find(scriptContent)?.let { match ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Server 1",
                    url = match.groupValues[1]
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            linkFound = true
        }

        mp4Regex.find(scriptContent)?.let { match ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Server 2",
                    url = match.groupValues[1]
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            linkFound = true
        }

        return linkFound
    }
}