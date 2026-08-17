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

    // NÂNG CẤP BẮT LINK VÉT CẠN (BRUTE-FORCE) VÀ CHỐNG CRASH
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var linkFound = false

        // Tải trang xem phim (chỉ gọi app.get 1 lần để tối ưu)
        val response = app.get(data)
        val mainHtml = response.text
        val document = response.document

        // Tạo danh sách chứa TẤT CẢ iframe tìm thấy bằng cả 2 cách (DOM và Regex Text)
        val iframes = mutableListOf<String>()

        document.select("iframe").forEach { iframes.add(it.attr("src")) }

        Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(mainHtml).forEach {
            iframes.add(it.groupValues[1])
        }

        // Lọc trùng và quét từng iframe
        iframes.filter { it.isNotBlank() }.distinct().forEach { rawSrc ->
            var src = rawSrc
            if (src.startsWith("//")) src = "https:$src"
            if (src.startsWith("/")) src = "$mainUrl$src"

            if (src.startsWith("http")) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)

                if (src.contains("vn2data") || src.contains("play.php") || src.contains("cloudcdnvn") || src.contains("phim.php")) {
                    try {
                        // Gọi iframe với Header giả lập
                        val iframeHtml = app.get(
                            src,
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36",
                                "Referer" to data,
                                "Accept" to "*/*"
                            )
                        ).text

                        // Chiến thuật 1: Quét biến link_video_sd và link_video_hd
                        val varRegex = Regex("""link_video_(?:sd|hd)\s*=\s*["'](http[^"']+)["']""")
                        varRegex.findAll(iframeHtml).forEach { match ->
                            val link = match.groupValues[1]
                            val isHd = match.value.contains("_hd")

                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = if (isHd) "Server HD" else "Server SD",
                                    url = link
                                ) {
                                    this.referer = src
                                    this.quality = if (isHd) Qualities.P1080.value else Qualities.P720.value
                                }
                            )
                            linkFound = true
                        }

                        // Chiến thuật 2: Quét link MP4 / M3U8 trực tiếp bị ẩn
                        val directRegex = Regex("""(?:file|src)\s*["']?\s*:\s*["'](http[^"']+(?:\.m3u8|\.mp4)[^"']*)["']""")
                        directRegex.findAll(iframeHtml).forEach { match ->
                            val link = match.groupValues[1]
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = if (link.contains(".m3u8")) "HLS Server" else "MP4 Server",
                                    url = link
                                ) {
                                    this.referer = src
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            linkFound = true
                        }
                    } catch (e: Exception) {
                        // Bỏ qua lỗi ngầm để tiếp tục quét iframe khác
                    }
                }
            }
        }

        // Chiến thuật 3: Dự phòng trường hợp web vứt link video ra ngoài mainHtml
        val backupRegex = Regex("""(?:file|src)\s*["']?\s*:\s*["'](http[^"']+(?:\.m3u8|\.mp4)[^"']*)["']""")
        backupRegex.findAll(mainHtml).forEach { match ->
            val link = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Backup Server",
                    url = link
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