package com.example.apptohtml.crawler

/**
 * Serializes the **name half** of a [ScreenIdentity] to the stable string used as the dedup key
 * and written to `<screen-identity>` in the crawl XML.
 *
 * `v3` because the segment name changed to `disambiguator`. The values, the cap and
 * the selection rule behind them are unchanged.
 */
object ScreenIdentityCodec {
    private const val PREFIX = "v3"
    private const val PKG = "pkg"
    private const val TITLE = "title"
    private const val DISAMBIGUATOR = "disambiguator"
    private const val UNKNOWN_PACKAGE = "unknown"
    private const val UNNAMED_TITLE = "unnamed"
    private const val NONE = "none"

    /**
     * How many title disambiguators a screen identity carries.
     *
     * Kept at two. Raising it would fill the extra slots from [ScreenNaming]'s resource-id
     * candidates, which are returned in tree order and filtered by no score threshold, so the set
     * would shift whenever any visible node appeared or disappeared and dedup would stop linking.
     */
    const val MAX_TITLE_DISAMBIGUATORS = 2

    fun encode(
        packageName: String,
        title: String,
        titleDisambiguators: List<String>,
    ): String {
        val normalizedTitle = ScreenNaming.normalizeIdentityToken(title).ifBlank { UNNAMED_TITLE }
        val normalizedPackage =
            ScreenNaming.normalizeIdentityToken(packageName).ifBlank { UNKNOWN_PACKAGE }
        val normalized = titleDisambiguators
            .map(ScreenNaming::normalizeIdentityToken)
            .filter { value -> value.isNotBlank() && value != normalizedTitle }
            .distinct()
            .take(MAX_TITLE_DISAMBIGUATORS)
        return buildString {
            append(PREFIX).append(':')
            append(PKG).append(':').append(normalizedPackage).append(':')
            append(TITLE).append(':').append(normalizedTitle).append(':')
            append(DISAMBIGUATOR).append(':')
            append(normalized.ifEmpty { listOf(NONE) }.joinToString("|"))
        }
    }

    // ---- content half --------------------------------------------------------------------
    //
    // One encoding now serves every screen, root and child alike: the back affordance is carried
    // as a flag rather than being filtered out for some screens and kept for others.

    private const val ELEMENT_SEPARATOR = "||"
    private const val FIELD_SEPARATOR = '|'
    private const val ROOT_SEPARATOR = "::"

    /**
     * The full structured encoding, back-affordance flag included. Written to the debug manifest
     * and the crawl graph; deliberately **not** what the richness metric measures — see
     * [encodeLogical].
     *
     * Deterministic: elements are sorted by their encoded form before joining.
     */
    fun encodeContent(identity: ScreenIdentity): String {
        val encodedElements = identity.elements
            .map { element -> "${element.encoded}$FIELD_SEPARATOR${element.isBackAffordance}" }
            .sorted()
            .joinToString(ELEMENT_SEPARATOR)
        return "${identity.rootClassName}$ROOT_SEPARATOR$encodedElements"
    }

    /**
     * The pre-structure *logical* encoding: six fields per element, no back-affordance flag, and
     * the element set already narrowed by the caller's policy.
     *
     * Kept separate from [encodeContent] because its **length** is not a diagnostic — it feeds
     * `DestinationRichnessMetrics.richnessScore`, which is the live comparator that decides which
     * settle sample becomes the captured child. Measuring the flagged encoding instead would add
     * five or six characters per element, and a whole extra element on any screen whose policy
     * excludes the back affordance, silently moving that decision.
     */
    fun encodeLogical(identity: ScreenIdentity, countBackAffordances: Boolean): String {
        val encodedElements = identity.elementsFor(countBackAffordances)
            .map(ScreenElementIdentity::encoded)
            .sorted()
            .joinToString(ELEMENT_SEPARATOR)
        return "${identity.rootClassName}$ROOT_SEPARATOR$encodedElements"
    }
}
