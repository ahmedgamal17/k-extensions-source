package eu.kanade.tachiyomi.extension.ar.teamx

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class TeamX : ParsedHttpSource(), ConfigurableSource {

    override val name = "TeamX"

    private val defaultBaseUrl = "https://olympustaff.com"

    private val BASE_URL_PREF = "overrideBaseUrl_v${AppInfo.getVersionName()}"

    override val baseUrl by lazy { getPrefBaseUrl() }

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .rateLimit(10, 1, TimeUnit.SECONDS)
        .build()

    // Popular

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/series?page=$page")
    }

    override fun popularMangaSelector() = "div.bs > div.bsx"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            element.select("a").let {
                setUrlWithoutDomain(it.attr("abs:href"))
                title = it.attr("title")
            }
            element.select("div.limit").let {
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }
    }

    override fun popularMangaNextPageSelector() = "a[rel=next]"

    // Latest

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/?page=$page")
    }

    override fun latestUpdatesSelector() = "div.box div.uta"

    override fun latestUpdatesFromElement(element: Element): SManga {
        return SManga.create().apply {
            element.select("div.imgu a").let {
                setUrlWithoutDomain(it.attr("abs:href"))
                thumbnail_url = it.select("img").attr("src")
                title = element.select("img").attr("alt")
            }
            /*element.select("div.thumb").let {
                title = element.select("h5").text()
            }*/
        }
    }

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val postHeaders = headers.newBuilder()
            .add("Referer", "$baseUrl/")
            .build()
        return if (query.isNotBlank()) {
            GET("$baseUrl/ajax/search?keyword=$query", postHeaders)
        } else {
            val url = "$baseUrl/series?page=$page".toHttpUrlOrNull()!!.newBuilder()
            filters.forEach { filter ->
                when (filter) {
                    is StatusFilter -> {
                        filter.state
                            .filter { it.state != Filter.TriState.STATE_IGNORE }
                            .forEach { url.addQueryParameter("status", it.id) }
                    }
                    is TypeFilter -> {
                        filter.state
                            .filter { it.state != Filter.TriState.STATE_IGNORE }
                            .forEach { url.addQueryParameter("type", it.id) }
                    }
                    is GenreFilter -> {
                        filter.state
                            .filter { it.state != Filter.TriState.STATE_IGNORE }
                            .forEach { url.addQueryParameter("genre", it.id) }
                    }
                    else -> {}
                }
            }
            GET(url.build().toString())
        }
    }

    override fun searchMangaSelector() = "div.bs > div.bsx, li.list-group-item"

    override fun searchMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            element.select("a").let {
                setUrlWithoutDomain(it.attr("abs:href"))
                title = it.attr("href").substringAfterLast("/").replace("-", " ")
            }

            thumbnail_url = element.select("img").attr("abs:src")
        }
    }

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Details

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            document.select("div.review-content").first()!!.let { info ->
                description = info.select("p").text()
            }
            title = document.select("div.author-info-title > h1").text()

            thumbnail_url = document.select("img[alt=Manga Image]").attr("src")

            author = document.select("div.full-list-info:contains(الرسام) > small > a").firstOrNull()?.ownText()
            artist = author

            genre = document.select("div.review-author-info > a, div.full-list-info:contains(النوع) > small > a").joinToString(", ") { it.text() }

            // add series Status to manga description
            document.select("div.full-list-info:contains(الحالة) > small > a")?.first()!!.text()?.also { statusText ->
                when {
                    statusText.contains("مستمرة", true) -> status = SManga.ONGOING
                    statusText.contains("مكتملة", true) -> status = SManga.COMPLETED
                    statusText.contains("قادم قريبًا", true) -> status = SManga.ONGOING
                    statusText.contains("متوقف", true) -> status = SManga.ON_HIATUS
                    else -> status = SManga.UNKNOWN
                }
            }
        }
    }

    // Chapters

    override fun chapterListSelector() = "div.chapter-card > a"

    private fun chapterNextPageSelector() = "a[rel=next]" //"ul.pagination li:last-child a"

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        // Chapter list may be paginated, get recursively
        fun addChapters(document: Document) {
            document.select(chapterListSelector()).map { chapters.add(chapterFromElement(it)) }
            document.select("${chapterNextPageSelector()}").firstOrNull()
                ?.let { addChapters(client.newCall(GET(it.attr("href"))).execute().asJsoup()) }
        }

        addChapters(response.asJsoup())
        return chapters
    }

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            name = element.select("div.chapter-info chapter-number").text() + " : " + element.select("div.chapter-info div.chapter-title").text()
            date_upload = element.select("div.chapter-info div.chapter-date").first()!!.text()?.let { parseChapterDate(it) } ?: 0
            val epNum = getNumberFromEpsString(element.select("div.chapter-info chapter-number").text())
            chapter_number = when {
                (epNum.isNotEmpty()) -> epNum.toFloat()
                else -> 1F
            }
        }
    }

    private fun parseChapterDate(date: String): Long {
        return SimpleDateFormat("yyyy-MM-dd hh:mm:ss", Locale.getDefault()).parse(date)?.time ?: 0L
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Pages

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.image_list canvas[data-src], div.image_list img[src]")
            .mapIndexed { i, element ->
                val url = when {
                    element.hasAttr("src") -> element.absUrl("src")
                    else -> element.absUrl("data-src")
                }
                Page(i, "", url)
            }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    // Filters

    override fun getFilterList() = FilterList(
        Filter.Header("NOTE: Ignored if using text search!"),
        Filter.Separator(),
        StatusFilter(getStatusFilters()),
        Filter.Separator(),
        TypeFilter(getTypeFilter()),
        Filter.Separator(),
        GenreFilter(getGenreFilters()),
    )

    class Type(name: String, val id: String = name) : Filter.TriState(name)
    private class TypeFilter(types: List<Type>) : Filter.Group<Type>("Type", types)
    class Status(name: String, val id: String = name) : Filter.TriState(name)
    private class StatusFilter(statuses: List<Status>) : Filter.Group<Status>("Status", statuses)
    class Genre(name: String, val id: String = name) : Filter.TriState(name)
    private class GenreFilter(genres: List<Genre>) : Filter.Group<Genre>("Genre", genres)

    open fun getGenreFilters(): List<Genre> = listOf(
        Genre("", ""),
        Genre("أكشن", "أكشن"),
        Genre("إثارة", "إثارة"),
        Genre("إيسيكاي", "إيسيكاي"),
        Genre("بطل غير إعتيادي", "بطل غير إعتيادي"),
        Genre("خيال", "خيال"),
        Genre("دموي", "دموي"),
        Genre("نظام", "نظام"),
        Genre("صقل", "صقل"),
        Genre("قوة خارقة", "قوة خارقة"),
        Genre("فنون قتال", "فنون قتال"),
        Genre("غموض", "غموض"),
        Genre("وحوش", "وحوش"),
        Genre("شونين", "شونين"),
        Genre("حريم", "حريم"),
        Genre("خيال علمي", "خيال علمي"),
        Genre("مغامرات", "مغامرات"),
        Genre("دراما", "دراما"),
        Genre("خارق للطبيعة", "خارق للطبيعة"),
        Genre("سحر", "سحر"),
        Genre("كوميدي", "كوميدي"),
        Genre("ويب تون", "ويب تون"),
        Genre("زمكاني", "زمكاني"),
        Genre("رومانسي", "رومانسي"),
        Genre("شياطين", "شياطين"),
        Genre("فانتازيا", "فانتازيا"),
        Genre("عنف", "عنف"),
        Genre("ملائكة", "ملائكة"),
        Genre("بعد الكارثة", "بعد الكارثة"),
        Genre("إعادة إحياء", "إعادة إحياء"),
        Genre("اعمار", "اعمار"),
        Genre("ثأر", "ثأر"),
        Genre("زنزانات", "زنزانات"),
        Genre("تاريخي", "تاريخي"),
        Genre("حرب", "حرب"),
        Genre("خارق", "خارق"),
        Genre("سنين", "سنين"),
        Genre("عسكري", "عسكري"),
        Genre("بوليسي", "بوليسي"),
        Genre("حياة مدرسية", "حياة مدرسية"),
        Genre("واقع افتراضي", "واقع افتراضي"),
        Genre("داخل لعبة", "داخل لعبة"),
        Genre("داخل رواية", "داخل رواية"),
        Genre("الحياة اليومية", "الحياة اليومية"),
        Genre("رعب", "رعب"),
        Genre("طبخ", "طبخ"),
        Genre("مدرسي", "مدرسي"),
        Genre("زومبي", "زومبي"),
        Genre("شوجو", "شوجو"),
        Genre("معالج", "معالج"),
        Genre("شريحة من الحياة", "شريحة من الحياة"),
        Genre("نفسي", "نفسي"),
        Genre("تاريخ", "تاريخ"),
        Genre("أكاديمية", "أكاديمية"),
        Genre("أرواح", "أرواح"),
        Genre("تراجيدي", "تراجيدي"),
        Genre("ابراج", "ابراج"),
        Genre("رياضي", "رياضي"),
        Genre("مصاص دماء", "مصاص دماء"),
        Genre("طبي", "طبي"),
        Genre("مأساة", "مأساة"),
        Genre("إيتشي", "إيتشي"),
        Genre("انتقام", "انتقام"),
        Genre("جوسي", "جوسي"),
        Genre("موريم", "موريم"),
        Genre("لعبة فيديو", "لعبة فيديو"),
        Genre("مغني", "مغني"),
    )

    open fun getTypeFilter(): List<Type> = listOf(
        Type("", ""),
        Type("مانها صيني", "مانها صيني"),
        Type("مانجا ياباني", "مانجا ياباني"),
        Type("ويب تون انجليزية", "ويب تون انجليزية"),
        Type("مانهوا كورية", "مانهوا كورية"),
        Type("ويب تون يابانية", "ويب تون يابانية"),
        Type("عربي", "عربي"),
    )

    open fun getStatusFilters(): List<Status> = listOf(
        Status("", ""),
        Status("مستمرة", "مستمرة"),
        Status("متوقف", "متوقف"),
        Status("مكتمل", "مكتمل"),
        Status("قادم قريبًا", "قادم قريبًا"),
    )

    // settings

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            this.setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    companion object {
        private const val RESTART_APP = ".لتطبيق الإعدادات الجديدة أعد تشغيل التطبيق"
        private const val BASE_URL_PREF_TITLE = "تعديل رابط الموقع"
        private const val BASE_URL_PREF_SUMMARY = ".للاستخدام المؤقت. تحديث التطبيق سيؤدي الى حذف الإعدادات"
    }
}
