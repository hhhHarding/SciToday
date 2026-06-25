package com.rssai.push.ui

import com.rssai.push.data.*

// 后端 timestamp 形如 "20260624_143025"（YYYYMMDD_HHMMSS），格式化为"年月日 时:分"
fun formatDigestTimestamp(ts: String): String {
    if (ts.isBlank()) return ts
    val parts = ts.split("_")
    if (parts.size < 2) return ts
    val d = parts[0]
    val t = parts[1]
    if (d.length < 8 || t.length < 4) return ts
    val y = d.substring(0, 4)
    val mo = d.substring(4, 6)
    val day = d.substring(6, 8)
    val h = t.substring(0, 2)
    val mi = t.substring(2, 4)
    return "${y}年${mo}月${day}日 ${h}:${mi}"
}

// 常见地学期刊名 → 缩写映射（小写键）
private val JOURNAL_ABBR = mapOf(
    "lithos" to "Lithos",
    "tectonophysics" to "Tectonophysics",
    "tectonics" to "Tectonics",
    "geology" to "Geology",
    "catena" to "Catena",
    "geomorphology" to "Geomorphology",
    "sedimentology" to "Sedimentology",
    "geofluids" to "Geofluids",
    "minerals" to "Minerals",
    "island arc" to "Island Arc",
    "ofioliti" to "Ofioliti",
    "gondwana research" to "Gondwana Res.",
    "precambrian research" to "Precambrian Res.",
    "chemical geology" to "Chem. Geol.",
    "ore geology reviews" to "Ore Geol. Rev.",
    "basin research" to "Basin Res.",
    "marine and petroleum geology" to "Mar. Pet. Geol.",
    "sedimentary geology" to "Sediment. Geol.",
    "journal of sedimentary research" to "JSR",
    "journal of structural geology" to "JSG",
    "journal of metamorphic geology" to "JMG",
    "contributions to mineralogy and petrology" to "CMP",
    "american mineralogist" to "Am. Mineral.",
    "journal of petrology" to "J. Petrol.",
    "earth-science reviews" to "ESR",
    "earth science reviews" to "ESR",
    "geoscience frontiers" to "GSF",
    "geochemistry geophysics geosystems" to "G3",
    "geophysical research letters" to "GRL",
    "journal of geophysical research" to "JGR",
    "quaternary science reviews" to "QSR",
    "palaeogeography palaeoclimatology palaeoecology" to "PPP",
    "applied geochemistry" to "Appl. Geochem.",
    "organic geochemistry" to "Org. Geochem.",
    "journal of geochemical exploration" to "J. Geochem. Explor.",
    "mineralium deposita" to "Miner. Deposita",
    "journal of asian earth sciences" to "JAES",
    "journal of south american earth sciences" to "JSAES",
    "science china earth sciences" to "Sci. China Earth Sci.",
    "acta petrologica sinica" to "Acta Petrol. Sin.",
    "nature geoscience" to "Nat. Geosci.",
    "nature communications" to "Nat. Commun.",
    "science advances" to "Sci. Adv.",
    "earth and planetary science letters" to "EPSL",
    "geochimica et cosmochimica acta" to "GCA",
    "geological society of america bulletin" to "GSAB",
    "geological journal" to "Geol. J.",
    "physics of the earth and planetary interiors" to "PEPI",
    "cretaceous research" to "Cretac. Res.",
    "journal of african earth sciences" to "JAFES"
)

private val STOPWORDS = setOf("of", "and", "the", "et", "for", "in", "on", "to", "a", "an", "de", "du")

// 期刊名 → 缩写。先查映射表，命中则返回；否则取实义词首字母生成缩写（如 GCA/GSAB）。
// 单词期刊或缩写过短则原样返回（如 Lithos）。
fun journalAbbr(journal: String): String {
    val s = journal.trim()
    if (s.isEmpty()) return ""
    JOURNAL_ABBR[s.lowercase()]?.let { return it }
    val words = s.split(Regex("\\s+")).filter { it.isNotBlank() }
    val acronym = words
        .filter { it.lowercase().trim('.', ',') !in STOPWORDS }
        .mapNotNull { it.firstOrNull { c -> c.isLetter() } }
        .joinToString("")
    return when {
        acronym.length in 2..6 -> acronym.uppercase()
        else -> s
    }
}
