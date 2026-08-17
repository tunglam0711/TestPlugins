package com.vn2c

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Vn2cProvider : MainAPI() {
    override var mainUrl = "https://www.vn2c.my"
    override var name = "Vn2c"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // ==========================================
    // 1. HÀM TRANG CHỦ
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homeItems = mutableListOf<HomePageList>()

        document.select("div.boxrightmid").forEach { section ->
            val title = section.selectFirst("div.righttop")?.text()?.trim() ?: "Danh sách phim"

            val movies = section.select("div.Form2").mapNotNull { element ->
                val aTag = element.selectFirst("div.Form2Img a") ?: return@mapNotNull null
                val name = aTag.attr("title")
                val url = aTag.attr("href")
                val absoluteUrl = fixUrl(url)
                val poster = aTag.selectFirst("img")?.attr("src") ?: ""

                newMovieSearchResponse(name, absoluteUrl, TvType.Movie) {
                    this.posterUrl = fixUrl(poster)
                }
            }

            if (movies.isNotEmpty()) {
                homeItems.add(HomePageList(title, movies))
            }
        }
        return newHomePageResponse(homeItems)
    }

    // ==========================================
    // 2. HÀM TÌM KIẾM
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        // 1. Lấy mã bảo mật ẩn của trang chủ (Vượt rào ASP.NET)
        val homeDoc = app.get(mainUrl).document
        val viewState = homeDoc.selectFirst("input[name=__VIEWSTATE]")?.attr("value") ?: ""
        val viewStateGen = homeDoc.selectFirst("input[name=__VIEWSTATEGENERATOR]")?.attr("value") ?: ""
        val eventValidation = homeDoc.selectFirst("input[name=__EVENTVALIDATION]")?.attr("value") ?: ""

        // 2. Gửi lệnh tìm kiếm kèm các mã bảo mật
        val document = app.post(
            url = mainUrl,
            data = mapOf(
                "__VIEWSTATE" to viewState,
                "__VIEWSTATEGENERATOR" to viewStateGen,
                "__EVENTVALIDATION" to eventValidation,
                "ctl00\$txtSearch" to query,
                "ctl00\$btnSearch" to "Tìm kiếm"
            )
        ).document

        // 3. Quét kết quả
        return document.select("div.Form2").mapNotNull { element ->
            val aTag = element.selectFirst("div.Form2Img a") ?: return@mapNotNull null
            val name = aTag.attr("title")
            val url = aTag.attr("href")
            val absoluteUrl = fixUrl(url)
            val poster = aTag.selectFirst("img")?.attr("src") ?: ""

            newMovieSearchResponse(name, absoluteUrl, TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    // ==========================================
    // 3. HÀM CHI TIẾT PHIM
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: "Không rõ tên phim"
        val poster = document.selectFirst("img.imgview")?.attr("src")
        val plot = document.select("div.wiew_info p, div.boxinfo2 p").joinToString("\n") {
            it.text().trim()
        }

        val episodes = mutableListOf<Episode>()

        document.select("div.num_film a").forEach { aTag ->
            val epName = aTag.text().trim()
            val epUrl = aTag.attr("href")

            if (epUrl.isNotEmpty()) {
                episodes.add(
                    newEpisode(data = fixUrl(epUrl)) {
                        this.name = "Tập $epName"
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
        }
    }

    // ==========================================
    // 4. HÀM LẤY LINK VIDEO
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val htmlText = response.text
        var isFound = false

        // 1. Quét tìm link mp4/m3u8 trực tiếp
        val regex = Regex("""(https?://[^"']+\.(?:mp4|m3u8)[^"']*)""")
        val matches = regex.findAll(htmlText)

        matches.forEach { matchResult ->
            val link = matchResult.groupValues[1]
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
            isFound = true
        }

        // 2. Nếu không có link trực tiếp, đào vào Iframe bằng loadExtractor
        if (!isFound) {
            val iframeSrc = response.document.selectFirst("iframe")?.attr("src")
            if (iframeSrc != null) {
                val absoluteIframeUrl = fixUrl(iframeSrc)
                // Gọi bộ giải mã Iframe mặc định của Cloudstream
                loadExtractor(absoluteIframeUrl, data, subtitleCallback, callback)
                isFound = true
            }
        }

        return isFound
    }
}