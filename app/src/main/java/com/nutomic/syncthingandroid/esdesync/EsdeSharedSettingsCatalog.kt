package com.nutomic.syncthingandroid.esdesync

data class EsdeSettingSpec(
    val name: String,
    val type: String,
    val category: String,
    val allowed: Set<String>? = null,
    val intRange: IntRange? = null,
    val pattern: Regex? = null,
) {
    fun normalize(value: Any): String {
        val text = when (type) {
            "bool" -> (value as? Boolean)?.toString()
            "int" -> when (value) {
                is Number -> value.toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()?.toString()
                else -> null
            }
            "float" -> (value as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toString()
            "string" -> value as? String
            else -> null
        } ?: throw IllegalArgumentException("$name has the wrong type")
        require(text.length <= 512 && text.none { it.isISOControl() }) { "$name has an invalid value" }
        if (allowed != null) require(text in allowed) { "$name has an unsupported value" }
        if (intRange != null) require(text.toIntOrNull() in intRange) { "$name is outside the allowed range" }
        if (pattern != null) require(pattern.matches(text)) { "$name has an unsupported value" }
        if (name == "CollectionSystemsCustom" || name == "CollectionSystemsAuto") {
            text.split(',').filter { it.isNotBlank() }.forEach { EsdeCollectionCodec().validateName(it.trim()) }
        }
        return text
    }
}

object EsdeSharedSettingsCatalog {
    private val SAFE_THEME_VALUE = Regex("^[A-Za-z0-9._ -]{0,160}$")
    private fun bool(category: String, vararg names: String) = names.map { EsdeSettingSpec(it, "bool", category) }
    private fun string(category: String, vararg names: String) = names.map { EsdeSettingSpec(it, "string", category) }
    private fun integer(category: String, range: IntRange, vararg names: String) =
        names.map { EsdeSettingSpec(it, "int", category, intRange = range) }
    private fun choice(category: String, name: String, vararg values: String) =
        EsdeSettingSpec(name, "string", category, allowed = values.toSet())

    val specs: List<EsdeSettingSpec> = buildList {
        addAll(string("Collections", "CollectionCustomGrouping", "CollectionSystemsAuto", "CollectionSystemsCustom"))
        addAll(bool("Collections", "FavoritesAddButton", "FavoritesFirst", "FavoritesStar", "FavFirstCustom", "FavStarCustom"))
        addAll(bool("Gamelist and metadata", "LegacyGamelistFileLocation", "AlternativeEmulatorPerGame", "FoldersOnTop", "GamelistFilters", "MAMENameStripExtraInfo", "ParseGamelistOnly", "ShowHiddenFiles", "ShowHiddenGames"))
        addAll(string("Gamelist and metadata", "DefaultSortOrder", "SystemsSorting"))
        add(choice("Gamelist and metadata", "SaveGamelistsMode", "always", "on exit", "never"))
        addAll(bool("Navigation and UI", "DisplayClock", "ListScrollOverlay", "MenuBlurBackground", "NavigationSounds", "ShowHelpPrompts"))
        addAll(string("Navigation and UI", "ApplicationLanguage", "QuickSystemSelect", "StartupSystem"))
        add(choice("Navigation and UI", "MenuColorScheme", "dark", "light"))
        add(choice("Navigation and UI", "MenuOpeningEffect", "scale-up", "none"))
        add(choice("Navigation and UI", "RandomEntryButton", "games", "system"))
        add(choice("Navigation and UI", "StartupView", "system", "gamelist"))
        add(choice("Navigation and UI", "UIMode", "full", "kiosk", "kid"))
        addAll(string("Theme", "ThemeColorScheme", "ThemeFontSize", "ThemeLanguage", "ThemeTransitions", "ThemeAspectRatio"))
        add(EsdeSettingSpec("Theme", "string", "Theme", pattern = SAFE_THEME_VALUE))
        add(EsdeSettingSpec("ThemeVariant", "string", "Theme", pattern = SAFE_THEME_VALUE))
        addAll(bool("Theme", "ThemeVariantTriggers"))

        addAll(bool("Media viewer", "MediaViewerKeepVideoRunning", "MediaViewerScreenshotScanlines", "MediaViewerShowTypes", "MediaViewerStretchVideos", "MediaViewerVideoBlur", "MediaViewerVideoScanlines"))
        addAll(string("Media viewer", "MediaViewerHelpPrompts"))
        addAll(bool("Miximage", "MiximageCoverFallback", "MiximageGenerate", "MiximageIncludeBox", "MiximageIncludeMarquee", "MiximageIncludePhysicalMedia", "MiximageOverwrite", "MiximageRemoveLetterboxes", "MiximageRemovePillarboxes", "MiximageRotateHorizontalBoxes"))
        addAll(string("Miximage", "MiximageBoxSize", "MiximageFileFormat", "MiximagePhysicalMediaSize", "MiximageResolution", "MiximageScreenshotAspectThreshold", "MiximageScreenshotBlankAreasColor", "MiximageScreenshotHorizontalFit", "MiximageScreenshotScaling", "MiximageScreenshotVerticalFit"))
        addAll(bool("Scraper content", "Scrape3DBoxes", "ScrapeBackCovers", "ScrapeCovers", "ScrapeFanArt", "ScrapeGameNames", "ScrapeManuals", "ScrapeMarquees", "ScrapeMetadata", "ScrapePhysicalMedia", "ScrapeRatings", "ScrapeScreenshots", "ScrapeTitleScreens", "ScrapeVideos", "ScraperRegionFallback"))
        addAll(string("Scraper content", "ScraperLanguage", "ScraperRegion"))
        addAll(bool("Screensaver", "ScreensaverControls", "ScreensaverSlideshowCustomImages", "ScreensaverSlideshowGameInfo", "ScreensaverSlideshowOnlyFavorites", "ScreensaverSlideshowRecurse", "ScreensaverSlideshowScanlines", "ScreensaverStretchImages", "ScreensaverStretchVideos", "ScreensaverVideoBlur", "ScreensaverVideoGameInfo", "ScreensaverVideoOnlyFavorites", "ScreensaverVideoScanlines"))
        addAll(integer("Screensaver", 0..86_400_000, "ScreensaverSwapImageTimeout", "ScreensaverSwapVideoTimeout", "ScreensaverTimer"))
        add(choice("Screensaver", "ScreensaverType", "video", "slideshow", "dim", "black"))
    }

    val byName: Map<String, EsdeSettingSpec> = specs.associateBy { it.name }

    // Defense in depth: these can never become shared even if a future caller bypasses the UI.
    fun requireAllowed(name: String): EsdeSettingSpec = byName[name]
        ?: throw IllegalArgumentException("Unknown or forbidden ES-DE setting: $name")

}
