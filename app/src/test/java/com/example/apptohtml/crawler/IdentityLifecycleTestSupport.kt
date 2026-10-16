package com.example.apptohtml.crawler

/** Builders shared by the settlement and recognition pins. */
internal object Lifecycle {

    fun name(
        title: String,
        confidence: ScreenDedupConfidence = ScreenDedupConfidence.STRONG,
        packageName: String = LifecycleTrees.PKG,
    ): ScreenNameIdentity = ScreenNameIdentity(
        packageName = ScreenNaming.normalizeIdentityToken(packageName),
        screenName = ScreenNaming.normalizeIdentityToken(title),
        titleDisambiguators = emptyList(),
        confidence = confidence,
    )

    /** The identity a capture of [tree] would carry, named [name], with [traits]. */
    fun identityOf(
        tree: AccessibilityNodeSnapshot,
        name: ScreenNameIdentity,
        traits: List<Trait>,
    ): ScreenIdentity = ScreenIdentity.fromRoot(tree).withName(name).copy(traits = traits)

    /** The identity a capture of [tree] would carry once its proposal settled. */
    fun settled(tree: AccessibilityNodeSnapshot, name: ScreenNameIdentity): ScreenIdentity =
        identityOf(tree, name, TraitProposer.propose(tree))

    /** The identity a capture of [tree] carries when it was left unsettled. */
    fun unsettled(tree: AccessibilityNodeSnapshot, name: ScreenNameIdentity): ScreenIdentity =
        identityOf(tree, name, emptyList())

    fun known(
        screenId: String,
        tree: AccessibilityNodeSnapshot?,
        identity: ScreenIdentity,
    ): KnownScreen = KnownScreen(screenId, identity, tree)

    fun contextOf(tree: AccessibilityNodeSnapshot, name: ScreenNameIdentity): TraitEvaluationContext =
        TraitEvaluationContext.of(tree, name)
}
