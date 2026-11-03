package com.kynrek.spamreporter

/**
 * Rough keyword classifier. The user always reviews and can override before anything is filed.
 * Political texts are exempt from Do Not Call rules (filed to FCC only, labeled political);
 * surveys/polls are exempt too; commercial solicitations go to FCC + FTC.
 */
object Classifier {
    private val political = listOf(
        "vote", "voting", "ballot", "election", "polls", "candidate", "campaign", "senate", "congress", "governor",
        "state house", "state rep", "paid for by", "pac", "republican", "democrat", "gop", "amendment", "proposition",
        "prop ", "measure", "millage", "rally", "early voting", "polling place", "rush \$", "chip in", "donate",
    )
    private val survey = listOf("survey", "poll ", "research", "dynata", "short questionnaire", "[poll]")
    private val commercial = listOf(
        "quote", "insurance", "coverage", "loan", "line of credit", "lines of credit", "free estimate", "offer",
        "deal", "discount", "% off", "file now", "enrollment", "warranty", "sale", "buy", "price", "approved for",
    )

    fun classify(bodies: List<String>): Category {
        val t = bodies.joinToString(" ").lowercase()
        fun score(words: List<String>) = words.count { t.contains(it) }
        val p = score(political); val s = score(survey); val c = score(commercial)
        return when {
            s > 0 && s >= p && c == 0 -> Category.SURVEY
            p >= 2 || (p > 0 && p >= c) -> Category.POLITICAL
            c > 0 -> Category.COMMERCIAL
            else -> Category.IGNORE
        }
    }

    /** FCC "Type of Property, Goods, or Services" option for commercial texts. */
    fun fccGoodsCategory(bodies: List<String>): String {
        val t = bodies.joinToString(" ").lowercase()
        return when {
            listOf("health", "medicare", "medicaid", "ppo", "coverage", "insurance").any { t.contains(it) } -> "Healthcare (including Medicare/Medicaid)"
            listOf("business", "line of credit", "lines of credit", "funding").any { t.contains(it) } -> "Business Loans"
            listOf("tree", "roof", "solar", "siding", "window", "remodel").any { t.contains(it) } -> "Home Improvement*"
            listOf("warranty", "vehicle").any { t.contains(it) } -> "Auto Warranties"
            listOf("debt", "credit card").any { t.contains(it) } -> "Debt Relief/Debt Consolidation"
            else -> "Other"
        }
    }

    /** ReportFraud.ftc.gov assistant answers: (top-level choice, optional sub-choice). */
    fun ftcCategory(bodies: List<String>): Pair<String, String?> {
        val t = bodies.joinToString(" ").lowercase()
        return if (listOf("health", "medicare", "ppo", "coverage", "insurance").any { t.contains(it) })
            "Health" to "Any other health care problem" else "Something else" to null
    }
}
