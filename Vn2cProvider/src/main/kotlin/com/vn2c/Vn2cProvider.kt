package com.vn2c

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Vn2cProvider : MainAPI() {

    override var mainUrl = "https://www.vn2c.my"
    override var name = "PhimVN2 (Bản Hoàn Hảo)"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val itemSelector = "div.Form2, div.boxtk"
    private val posterSelector = "img.c10, div.boxtk_img img, img"
    private val episodeSelector = "div.num_film a"
    private val descriptionSelector = "div.wiew_info p, div.info-film, .content-film"

    private fun headers(referer: String): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to referer,
        "Accept" to "*/*",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi-vn2-phim-2vn-phim/new" to "Phim Mới",
        "$mainUrl/danh-muc/trung-quoc-7" to "Phim Trung Quốc",
        "$mainUrl/danh-muc/han-quoc-10" to "Phim Hàn Quốc",
        "$mainUrl/danh-muc/thai-lan-8" to "Phim Thái Lan",
        "$mainUrl/the-loai2/hoat-hinh-anime-29" to "Hoạt Hình Anime"
    )

    private fun String.toSlug(): String {
        var text = trim().lowercase()
        val groups = listOf("áàảãạăắằẳẵặâấầẩẫậ" to "a", "éèẻẽẹêếềểễệ" to "e", "íìỉĩị" to "i", "óòỏõọôốồổỗộơớờởỡợ" to "o", "úùủũụưứừửữự" to "u", "ýỳỷỹỵ" to "y", "đ" to "d")
        for ((characters, replacement) in groups) {
            for (character in characters) text = text.replace(character.toString(), replacement)
        }
        return text.replace(Regex("[^a-z0-9]+"), "-").replace(Regex("-+"), "-").trim('-')
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val pageUrl = if (page <= 1) request.data else "${request.data}?page=$page"
            val document = app.get(pageUrl, headers = headers(mainUrl)).document
            val results = document.select(itemSelector).mapNotNull { it.toSearchResult() }
            newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val slug = query.toSlug()
            if (slug.isBlank()) return emptyList()
            val document = app.get("$mainUrl/tim-kiem/$slug", headers = headers(mainUrl)).document
            document.select(itemSelector).mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a[href]") ?: return null
        var title = selectFirst("p.nametk a")?.text()?.trim().orEmpty()
        if (title.isBlank()) title = linkElement.attr("title").trim()
        if (title.isBlank()) title = linkElement.text().trim()
        val href = linkElement.attr("href").trim()
        if (title.isBlank() || href.isBlank()) return null
        val posterElement = selectFirst(posterSelector)
        val poster = posterElement?.attr("data-src")?.ifBlank { posterElement.attr("data-original") }?.ifBlank { posterElement.attr("src") }
        return newMovieSearchResponse(title, fixUrl(href), TvType.TvSeries) { posterUrl = fixUrlNull(poster) }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = app.get(url, headers = headers(mainUrl)).document
            val title = document.selectFirst("h1, .box_film_title, .title, h2")?.text()?.trim() ?: "Không tên"
            val posterElement = document.selectFirst(posterSelector)
            val poster = posterElement?.attr("data-src")?.ifBlank { posterElement.attr("data-original") }?.ifBlank { posterElement.attr("src") }
            val plot = document.selectFirst(descriptionSelector)?.text()?.trim()
            val episodes = mutableListOf<Episode>()

            document.select(episodeSelector).forEachIndexed { index, element ->
                val episodeUrl = element.attr("href").trim()
                if (episodeUrl.isNotBlank()) episodes.add(newEpisode(fixUrl(episodeUrl)) { name = element.text().trim().ifBlank { "Tập ${index + 1}" } })
            }

            if (episodes.isEmpty()) {
                document.select("a[href]").mapNotNull {
                    val href = it.attr("href").trim()
                    if (href.contains("-tap-", true) || href.contains("-sub1-", true) || href.contains("-sub2-", true)) href to it.text().trim() else null
                }.distinctBy { it.first }.forEachIndexed { index, pair ->
                    episodes.add(newEpisode(fixUrl(pair.first)) { name = pair.second.ifBlank { "Tập ${index + 1}" } })
                }
            }

            if (episodes.isEmpty()) episodes.add(newEpisode(url) { name = "Full" })
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = fixUrlNull(poster); this.plot = plot }
        } catch (e: Exception) { null }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val episodeUrl = data.trim()
        if (episodeUrl.isBlank()) throw IllegalStateException("VN2 DEBUG\nSTEP=0\nDATA=EMPTY")

        val foundLinks = mutableSetOf<String>()
        var playerCount = 0
        val debugPlayers = mutableListOf<String>()

        suspend fun add(url: String, server: String, referer: String) {
            val clean = cleanUrl(url)
            if (!isVideoUrl(clean)) return
            if (!foundLinks.add(clean)) return
            addVideoLink(clean, server, referer, callback)
        }

        try {
            // 1. TẠO COOKIE SESSION VN2DATA TRƯỚC (Bắt buộc để player không bị block)
            try {
                app.get("https://vn2data.com/check-user.php?url=www.vn2c.my", headers = headers(mainUrl))
            } catch (e: Exception) {}

            val html = app.get(episodeUrl, headers = headers(mainUrl)).text

            // 2. KHÔI PHỤC VÀ NÂNG CẤP THUẬT TOÁN BUILD URL ĐỘNG
            val generatedPlayers = buildGeneratedPlayerUrls(html, episodeUrl)
            playerCount = generatedPlayers.size

            generatedPlayers.forEachIndexed { index, playerUrl ->
                debugPlayers.add(playerUrl)
                val playerLinks = fetchPlayerLinks(playerUrl, "Server ${index + 1}", episodeUrl)
                for (item in playerLinks) add(item.url, item.name, item.referer)
            }

            // 3. FALLBACK TÌM TRỰC TIẾP TRONG HTML NẾU CÓ
            if (foundLinks.isEmpty()) {
                extractVideoUrls(html).forEachIndexed { index, videoUrl ->
                    add(videoUrl, "Direct Server ${index + 1}", episodeUrl)
                }
            }

            if (foundLinks.isNotEmpty()) return true

            throw IllegalStateException(
                "VN2 DEBUG\n" +
                        "URL=$episodeUrl\n" +
                        "HTML=${html.length}\n" +
                        "PLAYER_COUNT=$playerCount\n" +
                        "PLAYERS_TESTED=\n${debugPlayers.joinToString("\n")}\n"
            )

        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("VN2 ERROR: ${e.message}")
        }
    }

    private data class VideoResult(val url: String, val name: String, val referer: String)

    private suspend fun fetchPlayerLinks(playerUrl: String, serverName: String, referer: String): List<VideoResult> {
        val result = mutableListOf<VideoResult>()
        if (isVideoUrl(playerUrl)) {
            result.add(VideoResult(playerUrl, "$serverName VIP", referer))
            return result
        }
        try {
            val html = app.get(playerUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to referer)).text

            // Dùng hàm extractData cực mạnh để bóc cả từ var lẫn từ thẻ <input>
            val sd = extractData(html, "link_video_sd")
            val hd = extractData(html, "link_video_hd")

            if (sd.isNotBlank() && isVideoUrl(sd)) result.add(VideoResult(sd, "$serverName - SD", playerUrl))
            if (hd.isNotBlank() && hd != sd && isVideoUrl(hd)) result.add(VideoResult(hd, "$serverName - FHD", playerUrl))

            if (result.isEmpty()) {
                extractVideoUrls(html).forEach { url ->
                    result.add(VideoResult(url, serverName, playerUrl))
                }
            }
        } catch (e: Exception) {}
        return result.distinctBy { it.url }
    }

    private suspend fun addVideoLink(url: String, serverName: String, referer: String, callback: (ExtractorLink) -> Unit) {
        if (!url.startsWith("http")) return
        var finalUrl = url
        // Ép đuôi .mp4 để Cloudstream không ném lỗi 3003
        if (!finalUrl.contains(".mp4", true) && !finalUrl.contains(".m3u8", true)) {
            finalUrl += "#.mp4"
        }
        val quality = when {
            serverName.contains("1080", true) || serverName.contains("FHD", true) -> 1080
            serverName.contains("720", true) || serverName.contains("HD", true) -> 720
            serverName.contains("SD", true) -> 480
            else -> 0
        }
        try {
            callback(newExtractorLink(name, serverName, finalUrl, if (finalUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = quality
                this.headers = mapOf("Referer" to referer)
            })
        } catch (e: Exception) {}
    }

    // TỐI ƯU HÓA HÀM TRÍCH XUẤT: Bóc cả từ JS variable, <input>, và data- attribute
    private fun extractData(html: String, key: String): String {
        // 1. var key = "val"
        var r = Regex("""(?:var|let|const)?\s*$key\s*[:=]\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        r.find(html)?.groupValues?.getOrNull(1)?.let { return it.trim() }

        // 2. <input id="key" value="val">
        r = Regex("""id\s*=\s*["']$key["'][^>]*value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        r.find(html)?.groupValues?.getOrNull(1)?.let { return it.trim() }

        // 3. <input value="val" id="key">
        r = Regex("""value\s*=\s*["']([^"']*)["'][^>]*id\s*=\s*["']$key["']""", RegexOption.IGNORE_CASE)
        r.find(html)?.groupValues?.getOrNull(1)?.let { return it.trim() }

        // 4. data-key="val"
        r = Regex("""data-$key\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        r.find(html)?.groupValues?.getOrNull(1)?.let { return it.trim() }

        return ""
    }

    private fun buildGeneratedPlayerUrls(html: String, episodeUrl: String): List<String> {
        val result = mutableListOf<String>()

        val nameTap = extractData(html, "name_tapphim")
        val nameId = extractData(html, "name_fixid").ifBlank {
            // Nếu web giấu name_fixid đi, tự động bóc ID từ URL phim
            Regex("""-id-(\d+)""").find(episodeUrl)?.groupValues?.get(1) ?: ""
        }
        val totalView = extractData(html, "totalview")
        val channelLink = extractData(html, "channel_link")
        val numChecks = extractData(html, "numchecks").ifBlank { extractData(html, "numcheck") }.ifBlank { "1" }
        val channelFix = extractData(html, "channel_fix").ifBlank { channelLink }

        val nameFixLoad = extractData(html, "name_fixload")
        val nameFixFull2 = extractData(html, "name_fixfull2")
        val channelFixIframe = extractData(html, "channel_fix_iframe")
        val nameFixFull = extractData(html, "name_fixfull")
        val channelUrlFix = extractData(html, "channel_url_fix")
        val channelStream3 = extractData(html, "channel_stream_3")
        val channelStream5 = extractData(html, "channel_stream_5")
        val channelStream9 = extractData(html, "channel_stream_9")
        val channel10 = extractData(html, "channel10")
        val nameNumGet = extractData(html, "name_numget")
        val nameTitle = extractData(html, "name_title")

        // Nếu thiếu cả 2 biến quan trọng thì có nghĩa là web đã đổi cấu trúc hoàn toàn.
        if (nameTap.isBlank() && nameId.isBlank()) return emptyList()

        val numServer = when {
            episodeUrl.contains("-sub1-", true) -> if (nameFixFull.contains("fix-to111", true)) "111" else "333"
            episodeUrl.contains("-sub2-", true) -> if (nameFixFull.contains("fix-to444", true)) "444" else "333"
            episodeUrl.contains("-tm2", true) -> if (nameFixFull.contains("fix-to222", true)) "222" else "111"
            else -> "111"
        }

        val idPlay = "${nameTap}sv${numServer}id${nameId}numview${totalView}chankey${numChecks}${channelFix}"
        val fixLoad3 = if (nameFixFull.contains("fullhd", true)) nameFixFull else channelStream3
        val fixLoad4 = when {
            channelStream9.contains(".m3u8", true) -> channelStream9
            channel10.contains("fbsv", true) -> channel10
            else -> ""
        }

        val generated = "https://vn2data.com/play/js_fix/9x/play.php?link=" +
                idPlay + "vn2fix1" + channelFix + "vn2fix2" + channelUrlFix +
                "vn2fix3" + channelFixIframe + "vn2fixload" + fixLoad3 +
                "vn2fixurl4" + fixLoad4 + "vn2fixurl5" + "" + "6O548l721190" +
                "cookiecat" + channelStream5 + "vn2myname" + nameFixLoad +
                "folderfix" + nameNumGet + "catnumget" + nameFixFull2 + episodeUrl

        result.add(generated)
        result.add(generated.replace("/play.php?", "/play2.php?"))
        result.add(generated.replace("/9x/", "/10x/").replace("/play.php?", "/play.php?"))

        return result.distinct()
    }

    private fun extractVideoUrls(html: String): List<String> {
        val results = mutableListOf<String>()
        val regex = Regex("""https?://[^"'\\s<>]+(?:\.mp4|\.m3u8|cloudcdnvn|scontent|cdninstagram|vn2data)[^"'\\s<>]*""", RegexOption.IGNORE_CASE)
        regex.findAll(html).forEach { match ->
            val url = cleanUrl(match.value)
            if (isVideoUrl(url)) results.add(url)
        }
        return results.distinct()
    }

    private fun isVideoUrl(url: String): Boolean {
        // Lọc bỏ file rác
        if (url.endsWith(".js") || url.endsWith(".css") || url.endsWith(".jpg") || url.endsWith(".png") || url.endsWith(".ico")) return false

        return url.contains(".mp4", true) || url.contains(".m3u8", true) ||
                url.contains("cloudcdnvn", true) || url.contains("scontent", true) ||
                url.contains("cdninstagram", true)
    }

    private fun cleanUrl(value: String): String {
        return value.trim().trim('"', '\'').replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").removeSuffix(";")
    }

    private fun absoluteUrl(value: String, baseUrl: String): String {
        val url = cleanUrl(value)
        if (url.startsWith("http")) return url
        return try {
            if (url.startsWith("//")) "https:$url"
            else if (url.startsWith("/")) baseUrl.substringBefore("://") + "://" + baseUrl.substringAfter("://").substringBefore("/") + url
            else baseUrl.substringBeforeLast("/") + "/" + url
        } catch (e: Exception) { url }
    }
}