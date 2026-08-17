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
        val formattedQuery = query.trim().replace(" ", "-")
        val url = "$mainUrl/tim-kiem/$formattedQuery"

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

    // =========================================================================
    // BẬT CHẾ ĐỘ DEBUG: IN LỖI TRỰC TIẾP RA MÀN HÌNH CHỌN SERVER
    // =========================================================================
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var linkFound = false
        val document = app.get(data).document

        val iframes = mutableListOf<String>()
        document.select("iframe").forEach { iframes.add(it.attr("src")) }

        // --- DEBUG 1: TÌM IFRAME ---
        if (iframes.isEmpty()) {
            callback.invoke(newExtractorLink(source = name, name = "LỖI 1: Không tìm thấy iframe nào trên trang", url = data) { this.quality = Qualities.Unknown.value })
            return true
        }

        iframes.forEach { rawSrc ->
            var src = rawSrc
            if (src.startsWith("//")) src = "https:$src"
            if (src.startsWith("/")) src = "$mainUrl$src"

            // --- DEBUG 2: IN RA ĐỊA CHỈ IFRAME ---
            callback.invoke(newExtractorLink(source = name, name = "BƯỚC 1: Đang quét iframe ->", url = src) { this.quality = Qualities.Unknown.value })
            linkFound = true

            if (src.contains("vn2data") || src.contains("play.php") || src.contains("cloudcdnvn")) {
                try {
                    // Cố gắng tải iframe
                    val iframeHtml = app.get(src, referer = data).text

                    // --- DEBUG 3: KIỂM TRA MÃ NGUỒN IFRAME ---
                    if (iframeHtml.isBlank()) {
                        callback.invoke(newExtractorLink(source = name, name = "LỖI 2: Tải iframe thất bại (Bị server chặn)", url = src) { this.quality = Qualities.Unknown.value })
                    } else {
                        callback.invoke(newExtractorLink(source = name, name = "BƯỚC 2: Tải thành công mã nguồn (${iframeHtml.length} ký tự)", url = src) { this.quality = Qualities.Unknown.value })
                    }

                    // TÌM BIẾN JS link_video_sd hoặc link_video_hd
                    val varRegex = Regex("""link_video_(?:sd|hd)\s*=\s*["']([^"']+)["']""")
                    val matches = varRegex.findAll(iframeHtml).toList()

                    if (matches.isEmpty()) {
                        callback.invoke(newExtractorLink(source = name, name = "LỖI 3: Mã nguồn không chứa biến link_video", url = src) { this.quality = Qualities.Unknown.value })
                    }

                    matches.forEach { match ->
                        val link = match.groupValues[1]
                        if (link.isNotBlank()) {
                            callback.invoke(
                                newExtractorLink(source = name, name = "THÀNH CÔNG: Đã cào được link video!", url = link) {
                                    this.referer = src
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        } else {
                            callback.invoke(newExtractorLink(source = name, name = "LỖI 4: Có biến link_video nhưng link bị trống rỗng", url = src) { this.quality = Qualities.Unknown.value })
                        }
                    }

                    // TÌM THÊM PLAY2.PHP (Trường hợp web nhúng 2 lớp iframe)
                    val phpRegex = Regex("""php_content_embed\s*=\s*["']([^"']+)["']""")
                    phpRegex.findAll(iframeHtml).forEach { match ->
                        val play2Link = match.groupValues[1]
                        if (play2Link.isNotBlank()) {
                            callback.invoke(newExtractorLink(source = name, name = "THÔNG TIN PHỤ: Tìm thấy link dự phòng play2.php", url = play2Link) { this.quality = Qualities.Unknown.value })
                        }
                    }

                } catch (e: Exception) {
                    callback.invoke(newExtractorLink(source = name, name = "LỖI CRASH: ${e.message}", url = src) { this.quality = Qualities.Unknown.value })
                }
            }
        }
        return linkFound
    }
}