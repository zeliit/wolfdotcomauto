package eu.kanade.tachiyomi.extension.ko.toonkorauto

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale

class Toonkor :
    HttpSource(),
    ConfigurableSource {

    override val name = "Toonkor Auto"

    override val baseUrl: String
        get() = "https://$currentDomain"

    override val client = network.client.newBuilder()
        .addInterceptor(::domainInterceptor)
        .addInterceptor(::browserHeadersInterceptor)
        .addNetworkInterceptor(::refererInterceptor)
        .build()

    private val telegramClient = network.client

    override val lang = "ko"

    override val supportsLatest = true

    private val webtoonsRequestPath = "/%EC%9B%B9%ED%88%B0"
    private val latestRequestModifier = "?fil=%EC%B5%9C%EC%8B%A0"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private val pageListRegex = Regex("""src="([^"]*)"""")

    private val preferences: SharedPreferences by getPreferencesLazy()

    // Popular

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl + webtoonsRequestPath, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.section-item-inner").map { element ->
            SManga.create().apply {
                element.select("div.section-item-title a").let {
                    title = it.select("h3").text()
                    setUrlWithoutDomain(it.attr("abs:href"))
                }
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }

        return MangasPage(mangas, false)
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl + webtoonsRequestPath + latestRequestModifier, headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        val type = filterList.firstInstanceOrNull<TypeFilter>()
        val sort = filterList.firstInstanceOrNull<SortFilter>()

        val requestPath = when {
            query.isNotBlank() -> "/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx=$query"
            type?.isSelection("Hentai") == true && sort?.isSelection("Completed") == true -> type.toUriPart()
            else -> (type?.toUriPart() ?: "") + (sort?.toUriPart() ?: "")
        }

        return GET(baseUrl + requestPath, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            with(document.select("table.bt_view1")) {
                title = select("td.bt_title").text()
                author = select("td.bt_label span.bt_data").text()
                description = select("td.bt_over").text()
                thumbnail_url = select("td.bt_thumb img").firstOrNull()?.attr("abs:src")
            }
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("table.web_list tr:has(td.content__title)").map { element ->
            SChapter.create().apply {
                element.select("td.content__title").let {
                    url = it.attr("data-role")
                    name = it.text()
                }
                date_upload = dateFormat.tryParse(element.select("td.episode__index").text())
            }
        }
    }

    // Pages

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val encoded = document.select("script:containsData(toon_img)").firstOrNull()?.data()
            ?.substringAfter("'")?.substringBefore("'") ?: throw Exception("toon_img script not found")

        val decoded = Base64.decode(encoded, Base64.DEFAULT).toString(Charset.defaultCharset())

        return pageListRegex.findAll(decoded).mapIndexed { i, matchResult ->
            val imageUrl = matchResult.destructured.component1().let { if (it.startsWith("http")) it else baseUrl + it }
            Page(i, imageUrl = imageUrl)
        }.toList()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Filters

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Note: can't combine with text search!"),
        Filter.Separator(),
        TypeFilter(),
        SortFilter(),
    )

    private val currentDomain: String
        get() = preferences.getString(PREF_DOMAIN, "")!!.ifEmpty { DEFAULT_DOMAIN }

    private fun fetchDomainFromTelegram(): String? = runCatching {
        telegramClient.newCall(GET(TELEGRAM_CHANNEL_URL, headers)).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val document = Jsoup.parse(response.body.string(), TELEGRAM_CHANNEL_URL)
            val candidates = document.select("a[href]").eachAttr("href") + listOf(document.text())
            candidates.asSequence()
                .mapNotNull { domainRegex.find(it)?.groupValues?.get(1) }
                .firstOrNull()
        }
    }.getOrNull()

    private fun refreshDomainFromTelegram(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val lastChecked = preferences.getLong(PREF_LAST_CHECK, 0L)
        val neverChecked = lastChecked == 0L
        if (!force && !neverChecked && now - lastChecked < CHECK_INTERVAL_MS) return
        preferences.edit().putLong(PREF_LAST_CHECK, now).apply()
        val newDomain = fetchDomainFromTelegram() ?: return
        if (newDomain != preferences.getString(PREF_DOMAIN, "")) {
            preferences.edit().putString(PREF_DOMAIN, newDomain).apply()
        }
    }

    private fun domainInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.matches(tkorHostRegex)) {
            return chain.proceed(request)
        }
        refreshDomainFromTelegram()
        val adjustedRequest = rebuildWithCurrentDomain(request)
        return tryProceed(chain, adjustedRequest)
    }

    private fun tryProceed(chain: Interceptor.Chain, request: Request): Response {
        val response = runCatching { chain.proceed(request) }.getOrElse {
            refreshDomainFromTelegram(force = true)
            val retryRequest = rebuildWithCurrentDomain(request)
            if (retryRequest.url.host != request.url.host) {
                return chain.proceed(retryRequest)
            }
            throw it
        }
        if (!response.isSuccessful) {
            refreshDomainFromTelegram(force = true)
            val retryRequest = rebuildWithCurrentDomain(request)
            if (retryRequest.url.host != request.url.host) {
                response.close()
                return chain.proceed(retryRequest)
            }
        }
        return response
    }

    private fun rebuildWithCurrentDomain(request: Request): Request {
        val currentHost = baseUrl.toHttpUrl().host
        if (request.url.host == currentHost) return request
        return request.newBuilder()
            .url(request.url.newBuilder().host(currentHost).build())
            .build()
    }

    private fun browserHeadersInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        return chain.proceed(request)
    }

    private fun refererInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Referer", "$baseUrl/")
            .build()
        return chain.proceed(request)
    }

    private val domainRegex = Regex("""https?://(tkor\d+\.com)""", RegexOption.IGNORE_CASE)
    private val tkorHostRegex = Regex("""^tkor\d+\.com$""", RegexOption.IGNORE_CASE)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN
            title = "도메인 직접 입력"
            summary = "자동 감지가 안 될 때만 입력 (예: tkor125.com)"
            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).trim()
                    .removePrefix("https://").removePrefix("http://").trimEnd('/')
                preferences.edit().putString(PREF_DOMAIN, value).apply()
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val DEFAULT_DOMAIN = "tkor125.com"
        private const val TELEGRAM_CHANNEL_URL = "https://t.me/s/toonkor_com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        private const val PREF_DOMAIN = "domain"
        private const val PREF_LAST_CHECK = "telegram_last_check"
        private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L
    }
}
