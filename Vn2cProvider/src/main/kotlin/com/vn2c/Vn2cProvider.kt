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

    // Đã mở rộng Selector tìm kiếm để vét cạn các giao diện
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
        val poster = document.selectFirst(POSTER_SELECTOR)?.attr("src")
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
        val response = app.get(data)
        val document = response.document
        val mainHtml = response.text

        val iframes = mutableListOf<String>()
        document.select("iframe").forEach { iframes.add(it.attr("src")) }
        Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(mainHtml).forEach { iframes.add(it.groupValues[1]) }

        iframes.filter { it.isNotBlank() }.distinct().forEach { rawSrc ->
            var src = rawSrc
            if (src.startsWith("//")) src = "https:$src"
            if (src.startsWith("/")) src = "$mainUrl$src"

            if (src.contains("vn2data") || src.contains("play.php") || src.contains("cloudcdnvn")) {
                try {
                    val iframeHtml = app.get(
                        src,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36",
                            "Referer" to data
                        )
                    ).text

                    val varRegex = Regex("""link_video_(?:sd|hd)\s*=\s*["'](http[^"']+)["']""")
                    varRegex.findAll(iframeHtml).forEach { match ->
                        var link = match.groupValues[1]
                        val isHd = match.value.contains("_hd")

                        if (link.isNotBlank()) {
                            // Ép đuôi mp4 để lừa trình phát, tránh lỗi 3003
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

                    val phpRegex = Regex("""php_content_embed\s*=\s*["']([^"']+)["']""")
                    phpRegex.findAll(iframeHtml).forEach { match ->
                        val play2Link = match.groupValues[1]
                        if (play2Link.isNotBlank() && play2Link.startsWith("http")) {
                            loadExtractor(play2Link, src, subtitleCallback, callback)
                            linkFound = true
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