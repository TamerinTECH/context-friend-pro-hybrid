package tech.tamerin.contextfriendpro

/**
 * Deterministic, local pseudonymisation of meeting text before an opt-in cloud request.
 *
 * Nothing here calls a model or the network, so every decision is testable and explainable.
 * It can miss an unusual name. The privacy panel therefore shows the exact outgoing text,
 * lists words that still look like names, and lets the user redact more before consenting.
 * The placeholder mapping lives in this object only: create one per request and drop it.
 */
enum class Entity { PERSON, EMAIL, PHONE, DATE, ADDRESS, ID, IBAN, URL, SCHOOL }

data class Replacement(val placeholder: String, val original: String, val type: Entity)

/** One person the user knows by name: a speaker they named, a voice profile or a roster entry. */
data class KnownName(val fullName: String)

class Pseudonymizer(known: Collection<KnownName>, extraTerms: Collection<String> = emptyList()) {
    private val counters = mutableMapOf<Entity, Int>()
    private val placeholderByKey = mutableMapOf<String, String>()
    private val restoreText = linkedMapOf<String, String>()
    private val types = mutableMapOf<String, Entity>()

    /** Name variants (full name, first name, surname, hyphen parts) with the person they belong to. */
    private val nameVariants: List<Pair<String, Int>>
    private val people: List<String>
    private val extra: List<String>

    init {
        people = known.map { it.fullName.trim().replace(Regex("\\s+"), " ") }.filter { it.length >= 2 }.distinct()
        val variants = mutableMapOf<String, MutableSet<Int>>()
        people.forEachIndexed { index, name ->
            val parts = name.split(' ').filter { it.length >= 2 && it.trimEnd('.').lowercase() !in HONORIFICS }
            val all = buildSet {
                add(name)
                addAll(parts)
                parts.filter { '-' in it }.forEach { part -> addAll(part.split('-').filter { it.length >= 3 }) }
            }
            all.forEach { variants.getOrPut(it.lowercase()) { mutableSetOf() }.add(index) }
        }
        // Longest first, so "Mila Sonnenberg" wins over "Mila".
        nameVariants = variants.entries.sortedByDescending { it.key.length }.map { (variant, owners) ->
            variant to (owners.singleOrNull() ?: -1)
        }
        extra = extraTerms.map { it.trim() }.filter { it.length >= 2 }.distinct().sortedByDescending { it.length }
    }

    val replacements: List<Replacement>
        get() = restoreText.map { (placeholder, original) -> Replacement(placeholder, original, types.getValue(placeholder)) }

    fun counts(): Map<Entity, Int> = types.values.groupingBy { it }.eachCount()

    /** Replaces every detected identifier with a stable placeholder such as `[PERSON_1]`. */
    fun redact(text: String): String {
        var out = text
        out = replaceAll(out, URL_PATTERN, Entity.URL)
        out = replaceAll(out, EMAIL_PATTERN, Entity.EMAIL)
        out = replaceAll(out, IBAN_PATTERN, Entity.IBAN)
        DATE_PATTERNS.forEach { out = replaceAll(out, it, Entity.DATE) }
        // A phone number has a separator, a + / 00 prefix or a trunk 0; a bare run of digits is an ID.
        out = replaceAll(out, PHONE_PATTERN, Entity.PHONE) { match ->
            match.count(Char::isDigit) >= 7 && (match[0] == '+' || match[0] == '0' || match.any { it in " /()-." })
        }
        ADDRESS_PATTERNS.forEach { out = replaceAll(out, it, Entity.ADDRESS) }
        SCHOOL_PATTERNS.forEach { out = replaceAll(out, it, Entity.SCHOOL) }
        out = replaceAll(out, ID_PATTERN, Entity.ID)
        out = replaceKnownNames(out)
        extra.forEach { term -> out = replaceAll(out, wordPattern(term, ignoreCase = true), Entity.PERSON) }
        out = replaceGroup(out, HONORIFIC_NAME, 2, Entity.PERSON)
        out = replaceGroup(out, CUE_NAME, 2, Entity.PERSON)
        return out
    }

    /** Puts the original text back into a model answer. Tolerates a model that dropped the brackets. */
    fun reidentify(text: String): String = PLACEHOLDER_IN_ANSWER.replace(text) { match ->
        val placeholder = "[${match.groupValues[1]}_${match.groupValues[2]}]"
        restoreText[placeholder] ?: match.value
    }

    /**
     * What a reviewer should still look at after [redact]: identifiers the detectors would still
     * find (must be zero before anything is sent), and capitalised words that might be names.
     */
    fun residualRisk(redacted: String, language: String): ResidualRisk {
        val withoutPlaceholders = PLACEHOLDER_IN_ANSWER.replace(redacted, " ")
        val probe = Pseudonymizer(people.map(::KnownName), extra)
        val again = probe.redact(withoutPlaceholders)
        val hits = probe.counts().values.sum()
        val candidates = if (language.startsWith("en")) nameCandidates(withoutPlaceholders) else emptyList()
        return ResidualRisk(detectorHits = hits, possibleNames = candidates, reviewedText = again)
    }

    private fun placeholderFor(original: String, type: Entity, key: String = "$type:${original.lowercase()}"): String =
        placeholderByKey.getOrPut(key) {
            val n = (counters[type] ?: 0) + 1
            counters[type] = n
            val placeholder = "[${type.name}_$n]"
            restoreText[placeholder] = original
            types[placeholder] = type
            placeholder
        }

    private fun replaceAll(text: String, pattern: Regex, type: Entity, accept: (String) -> Boolean = { true }): String =
        pattern.replace(text) { match ->
            if (insidePlaceholder(text, match.range.first) || !accept(match.value)) match.value
            else placeholderFor(match.value, type)
        }

    private fun replaceGroup(text: String, pattern: Regex, group: Int, type: Entity): String =
        pattern.replace(text) { match ->
            val target = match.groups[group] ?: return@replace match.value
            if (target.value.lowercase() in COMMON_CAPITALISED || target.value.startsWith("[")) return@replace match.value
            val start = target.range.first - match.range.first
            match.value.substring(0, start) + placeholderFor(target.value, type) + match.value.substring(start + target.value.length)
        }

    private fun replaceKnownNames(text: String): String {
        var out = text
        nameVariants.forEach { (variant, owner) ->
            val pattern = wordPattern(variant, ignoreCase = variant !in COMMON_WORD_NAMES, possessive = true)
            out = pattern.replace(out) { match ->
                if (insidePlaceholder(out, match.range.first)) return@replace match.value
                val name = match.groups[1]!!.value
                val suffix = match.value.substring(name.length)
                val placeholder = if (owner >= 0) placeholderFor(people[owner], Entity.PERSON, "person:$owner")
                else placeholderFor(name, Entity.PERSON)
                placeholder + suffix
            }
        }
        return out
    }

    private fun nameCandidates(text: String): List<String> = SENTENCE_SPLIT.split(text).flatMap { sentence ->
        WORD.findAll(sentence).drop(1).map { it.value }
            .filter { it[0].isUpperCase() && it.length >= 2 && it.lowercase() !in COMMON_CAPITALISED && !it.all(Char::isUpperCase) }
            .toList()
    }.distinct()

    companion object {
        private val HONORIFICS = setOf("frau", "herr", "herrn", "fr", "hr", "dr", "prof", "mr", "mrs", "ms", "miss", "mx")
        /** First names that are also ordinary English or German words: only matched when capitalised. */
        private val COMMON_WORD_NAMES = setOf("will", "may", "mark", "rose", "grace", "hope", "joy", "june", "april", "august",
            "summer", "dawn", "bill", "frank", "art", "sunny", "rich", "faith", "ernst", "wolf", "klein", "lang", "kurz", "fuchs")
        private val COMMON_CAPITALISED = setOf(
            "i", "i'm", "i'll", "i've", "i'd", "ok", "okay", "yes", "no", "the", "a", "an", "and", "but", "so", "we", "you", "he", "she", "they",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
            "english", "german", "maths", "math", "mathematics", "science", "biology", "chemistry", "physics", "history", "geography",
            "art", "music", "sport", "pe", "french", "spanish", "latin", "deutsch", "mathe", "englisch",
            "speaker", "mum", "mom", "dad", "teacher", "school", "class", "year", "grade", "term", "christmas", "easter",
            "europe", "germany", "berlin", "doctor", "dr", "mr", "mrs", "ms", "frau", "herr")
        private val B = "(?<![\\p{L}\\p{N}_])"
        private val E = "(?![\\p{L}\\p{N}_])"
        private val URL_PATTERN = Regex("(?:https?://|www\\.)[^\\s<>\"]+", RegexOption.IGNORE_CASE)
        private val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val IBAN_PATTERN = Regex("${B}[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{4}){3,7}(?:\\s?[A-Z0-9]{1,3})?$E")
        private const val MONTHS = "Jan(?:uary|uar)?|Feb(?:ruary|ruar)?|M(?:ar(?:ch)?|ärz)|Apr(?:il)?|Ma[iy]|Jun[ie]?|Jul[iy]?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|O[ck]t(?:ober)?|Nov(?:ember)?|De[cz](?:ember)?"
        private val DATE_PATTERNS = listOf(
            Regex("$B\\d{4}-\\d{2}-\\d{2}$E"),
            Regex("$B\\d{1,2}[./-]\\d{1,2}[./-](?:\\d{4}|\\d{2})$E"),
            Regex("$B\\d{1,2}(?:\\.|st|nd|rd|th)?\\s+(?:of\\s+)?(?:$MONTHS)\\.?(?:\\s+\\d{4})?$E", RegexOption.IGNORE_CASE),
            Regex("$B(?:$MONTHS)\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+\\d{4})?$E", RegexOption.IGNORE_CASE))
        private val PHONE_PATTERN = Regex("(?<![\\p{L}\\p{N}])(?:\\+|00)?\\d[\\d\\s/().-]{5,}\\d(?![\\p{L}\\p{N}])")
        private val ADDRESS_PATTERNS = listOf(
            Regex("$B[A-ZÄÖÜ][\\p{L}-]*(?:straße|strasse|str\\.|weg|gasse|platz|allee|ring|damm|ufer|chaussee)\\s+\\d+\\s?[a-z]?$E"),
            Regex("$B\\d+[a-z]?\\s+(?:[A-Z][a-z]+\\s){1,3}(?:Street|St\\.|Road|Rd\\.|Avenue|Ave\\.|Lane|Drive|Way|Close|Court)$E"),
            Regex("$B\\d{5}\\s+[A-ZÄÖÜ][\\p{L}-]+$E"))
        private val SCHOOL_PATTERNS = listOf(
            Regex("$B[A-ZÄÖÜ][\\p{L}.]*(?:[- ][A-ZÄÖÜ][\\p{L}.]*)*[- ](?:Gymnasium|Grundschule|Realschule|Gesamtschule|Oberschule|Mittelschule|Hauptschule|Förderschule|Schule|Primary School|High School|Secondary School|Academy)$E"),
            Regex("$B(?:Gymnasium|Grundschule|Realschule|Gesamtschule|Oberschule)\\s+(?:am\\s+|an\\s+der\\s+)?[A-ZÄÖÜ][\\p{L}-]+$E"))
        private val ID_PATTERN = Regex("$B\\d{6,}$E")
        private val HONORIFIC_NAME = Regex("$B(Frau|Herrn?|Fr\\.|Hr\\.|Mrs?\\.?|Ms\\.?|Miss|Mx\\.?|Dr\\.|Prof\\.)\\s+((?:Dr\\.\\s+)?[A-ZÄÖÜ][\\p{L}'’-]+(?:\\s+[A-ZÄÖÜ][\\p{L}'’-]+)?)")
        private val CUE_NAME = Regex("$B((?:[Mm]y|[Oo]ur|[Hh]er|[Hh]is)\\s+(?:son|daughter|child|kid|brother|sister)|[Mm]eine?\\s+(?:Sohn|Tochter|Kind)|[Uu]nsere?\\s+(?:Sohn|Tochter|Kind)|named|called|hei(?:ß|ss)t|namens)\\s+([A-ZÄÖÜ][\\p{L}'’-]+)")
        private val PLACEHOLDER_IN_ANSWER = Regex("\\[?(PERSON|EMAIL|PHONE|DATE|ADDRESS|ID|IBAN|URL|SCHOOL)_(\\d+)\\]?")
        private val SENTENCE_SPLIT = Regex("(?<=[.!?:])\\s+|\\n+")
        private val WORD = Regex("[\\p{L}][\\p{L}'’-]*")

        /** Whole-word match. Names that are also ordinary words match only when capitalised. */
        private fun wordPattern(term: String, ignoreCase: Boolean, possessive: Boolean = false): Regex {
            val suffix = if (possessive) "(?:['’]s|['’]|s)?" else ""
            return if (ignoreCase) Regex("$B(${Regex.escape(term)})$suffix$E", RegexOption.IGNORE_CASE)
            else Regex("$B(${Regex.escape(term.replaceFirstChar { it.uppercaseChar() })})$suffix$E")
        }
        private fun insidePlaceholder(text: String, index: Int): Boolean {
            val open = text.lastIndexOf('[', index)
            return open >= 0 && text.indexOf(']', open).let { it >= index } && PLACEHOLDER_IN_ANSWER.matchesAt(text, open)
        }
    }
}

data class ResidualRisk(val detectorHits: Int, val possibleNames: List<String>, val reviewedText: String) {
    /** Detector hits after redaction are a bug in the redaction, never something to send. */
    val blocking: Boolean get() = detectorHits > 0
}
