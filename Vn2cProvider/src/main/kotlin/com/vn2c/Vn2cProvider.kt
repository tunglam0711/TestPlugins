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
        // Gửi lệnh tìm kiếm lên hệ thống của Vn2c
        val document = app.post(
            url = mainUrl,
            data = mapOf(
                "ctl00\$txtSearch" to query,
                "ctl00\$btnSearch" to "Tìm kiếm"
            )
        ).document

        // Quét kết quả trả về
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
        // 'data' chính là url của tập phim truyền từ hàm load
        val response = app.get(data)
        val htmlText = response.text // Lấy toàn bộ mã nguồn thô của trang

        var isFound = false

        // 1. Quét tìm tất cả link có đuôi .mp4 hoặc .m3u8
        val regex = Regex("""(https?://[^"']+\.(?:mp4|m3u8)[^"']*)""")
        val matches = regex.findAll(htmlText)

        matches.forEach { matchResult ->
            val link = matchResult.groupValues[1]
            val isM3u8 = link.contains(".m3u8")

            // Gửi link tìm được cho Cloudstream phát
            callback(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    referer = data, 
                    quality = Qualities.Unknown.value,
                    isM3u8 = isM3u8
                )
            )
            isFound = true
        }

        // 2. Dự phòng: Nếu regex không tìm thấy, có thể họ nhúng video qua thẻ iframe
        if (!isFound) {
            val iframeSrc = response.document.selectFirst("iframe")?.attr("src")
            if (iframeSrc != null) {
                println("Vn2c dùng Iframe: $iframeSrc")
            }
        }

        return isFound
    }
} // CHÚ Ý: Dấu ngoặc đóng class Vn2cProvider được đặt ở tận cùng file!
