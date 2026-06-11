package eu.kanade.tachiyomi.extension.ko.wolfdotcomauto

import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import org.jsoup.nodes.Document

class WolfFactory : SourceFactory {
    override fun createSources() = listOf(
        Wolf("Webtoon", "ing", "list", "view"),
        Wolf("Comic", "cm", "cl", "cv"),
        object : Wolf("Photo", "pt", "list", "view") {
            override fun getFilterList(): FilterList = FilterList()

            override fun parseSearchFilters(document: Document) {
                return
            }
        },
    )
}
