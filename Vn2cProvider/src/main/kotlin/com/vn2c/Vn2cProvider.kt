package com.vn2c

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.text.Normalizer

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

    private val ITEM_SELECTOR = "div.Form2, ul.list-film li, div.item, div.film-item"
    private val LINK_SELECTOR = "a"
    private val POSTER_SELECTOR = "img.c10, img.lazy, img"

    private val EPISODES_SELECTOR = "div.num_film a"
    private val DESCRIPTION_SELECTOR = "div.wiew_info p, div.info-film"

    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi-vn2-phim-2vn-phim/new" to "Phim Mới",
        "$mainUrl/danh-muc/trung-quoc-7" to "Phim Trung Quốc",
        "$mainUrl/danh-muc/han-quoc-10" to "Phim Hàn Quốc",
        "$mainUrl/danh-muc/thai-lan-8" to "Phim Thái Lan",
        "$mainUrl/the-loai2/hoat-hinh-anime-29" to "Hoạt Hình Anime"
    )

    // Hàm hỗ trợ xóa dấu tiếng Việt để tìm kiếm chính xác (VD: "Nam Hí" -> "nam-hi")
    private fun String.toSlug(): String {
        val normalized = Normalizer.normalize(this.trim(), Normalizer.Form.NFD)
        val noDiacritics = Regex("\\p{InCombiningDiacriticalMarks}+").replace(normalized, "")
            .replace("đ", "d").replace("Đ", "D")
        return noDiacritics.replace(Regex("\\s+"), "-").lowercase()
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
        val linkElement = this.selectFirst(LINK_SELECTOR) ?: return null
        val title = linkElement.attr("title").ifBlank { linkElement.text() }
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
        val poster = document.selectFirst(POSTER_SELECTOR)?.attr("src") ?: document.selectFirst(POSTER_SELECTOR)?.attr("data-src")
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

        // Thêm Header giả lập trình duyệt để tránh bị chặn
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to mainUrl
        )

        val response = app.get(data, headers = headers)
        val document = response.document
        val mainHtml = response.text

        val iframes = mutableListOf<String>()

        // 1. Tìm iframe bằng cách xét DOM (Ưu tiên lấy cả data-src nếu bị ẩn)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) iframes.add(src)
        }

        // 2. Tìm iframe bằng Regex quét toàn bộ mã nguồn đề phòng JS ẩn
        val playerUrlRegex = Regex("""(https?://[^"']*(?:vn2data|play\.php|cloudcdnvn|phim\.php)[^"']*)""")
        playerUrlRegex.findAll(mainHtml).forEach { match ->
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

                    // Quét link HD và SD
                    val varRegex = Regex("""link_video_(?:sd|hd)\s*=\s*["'](http[^"']+)["']""")
                    varRegex.findAll(iframeHtml).forEach { match ->
                        var link = match.groupValues[1]
                        val isHd = match.value.contains("_hd")

                        if (link.isNotBlank()) {
                            // Sửa lỗi 3003 (Không hỗ trợ định dạng)
                            if (!link.contains(".mp4") && !link.contains(".m3u8")) {
                                link += "#.mp4"
                            }
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = if (isHd) "VN2Data HD" else "VN2Data SD",
                                    url = link
                                ) {
                                    this.referer = src
                                    this.quality = if (isHd) Qualities.P1080.value else Qualities.P720.value
                                }
                            )
                            linkFound = true
                        }
                    }

                    // Quét play2.php (lớp iframe thứ 2)
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

                    // Quét dự phòng link trực tiếp trong iframe
                    val directRegex = Regex("""(?:file|src)\s*["']?\s*:\s*["'](http[^"']+(?:\.m3u8|\.mp4)[^"']*)["']""")
                    directRegex.findAll(iframeHtml).forEach { match ->
                        val link = match.groupValues[1]
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = if (link.contains(".m3u8")) "HLS Server" else "MP4 Server",
                                url = link
                            ) {
                                this.referer = src
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        linkFound = true
                    }
                } catch (e: Exception) {}
            } else if (src.startsWith("http")) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
                linkFound = true
            }
        }

        // Quét dự phòng trực tiếp trên mainHtml
        val fallbackRegex = Regex("""(?:file|src)\s*["']?\s*:\s*["'](http[^"']+(?:\.m3u8|\.mp4)[^"']*)["']""")
        fallbackRegex.findAll(mainHtml).forEach { match ->
            val link = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    source = name,
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