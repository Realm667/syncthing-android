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

data class EsdeSettingCategory(
    val id: String,
    val title: String,
    val summary: String,
    val specs: List<EsdeSettingSpec>,
)

object EsdeSharedSettingsCatalog {
    private val SAFE_THEME_VALUE = Regex("^[A-Za-z0-9._ :&+()!'-]{0,160}$")
    private fun bool(category: String, vararg names: String) = names.map { EsdeSettingSpec(it, "bool", category) }
    private fun string(category: String, vararg names: String) = names.map { EsdeSettingSpec(it, "string", category) }
    private fun integer(category: String, range: IntRange, vararg names: String) =
        names.map { EsdeSettingSpec(it, "int", category, intRange = range) }
    private fun choice(category: String, name: String, vararg values: String) =
        EsdeSettingSpec(name, "string", category, allowed = values.toSet())

    val specs: List<EsdeSettingSpec> = buildList {
        addAll(string("Collections", "CollectionCustomGrouping", "CollectionSystemsAuto", "CollectionSystemsCustom"))
        addAll(bool("Collections", "FavoritesAddButton", "FavoritesFirst", "FavoritesStar", "FavFirstCustom", "FavStarCustom"))
        addAll(bool("Gamelist and metadata", "AlternativeEmulatorPerGame", "FoldersOnTop", "GamelistFilters", "MAMENameStripExtraInfo", "ParseGamelistOnly", "ShowHiddenFiles", "ShowHiddenGames"))
        addAll(string("Gamelist and metadata", "DefaultSortOrder", "SystemsSorting"))
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

    private val locallyManagedCompatibilitySpecs = listOf(
        EsdeSettingSpec("LegacyGamelistFileLocation", "bool", "SafeSync requirements"),
        choice("SafeSync requirements", "SaveGamelistsMode", "always", "on exit", "never"),
    )

    val byName: Map<String, EsdeSettingSpec> = (specs + locallyManagedCompatibilitySpecs).associateBy { it.name }

    val categories: List<EsdeSettingCategory> = listOf(
        category(
            id = "collections",
            title = "Collections",
            summary = "Synchronizes collection grouping and enabled automatic/custom collections, plus favorites button, ordering, and star display options.",
        ),
        category(
            id = "gamelist_metadata",
            title = "Gamelist and metadata",
            summary = "Synchronizes emulator overrides, folder order, filters, hidden entries, MAME names, parsing, and sorting. ROM location and immediate saving are managed automatically by SafeSync.",
        ),
        category(
            id = "navigation_ui",
            title = "Navigation and UI",
            summary = "Synchronizes language, startup view/system, clock, overlays, help prompts, menu appearance, UI mode, navigation sounds, and quick/random navigation.",
        ),
        category(
            id = "theme",
            title = "Theme",
            summary = "Synchronizes theme, variant, colors, font size, language, transitions, aspect ratio, and variant triggers. Themes are applied only when installed locally.",
        ),
        category(
            id = "media_viewer",
            title = "Media viewer",
            summary = "Synchronizes media viewer help, visible media types, video continuation, stretching, blur, and scanline options.",
        ),
        category(
            id = "miximage",
            title = "Miximage",
            summary = "Synchronizes miximage generation, overwrite and artwork inclusion options, sizing, resolution, format, scaling, rotation, and letterbox handling.",
        ),
        category(
            id = "scraper_content",
            title = "Scraper content",
            summary = "Synchronizes which artwork and metadata types are scraped, together with scraper language, region, and region fallback. Accounts and credentials are never included.",
        ),
        category(
            id = "screensaver",
            title = "Screensaver",
            summary = "Synchronizes screensaver type and timing, controls, favorite filtering, game information, custom slideshow images, stretching, blur, and scanlines.",
        ),
    )

    val categoryById: Map<String, EsdeSettingCategory> = categories.associateBy { it.id }

    fun namesForCategories(categoryIds: Set<String>): Set<String> = categories
        .filter { it.id in categoryIds }
        .flatMapTo(linkedSetOf()) { category -> category.specs.map { it.name } }

    fun categoriesForSettingNames(settingNames: Set<String>): Set<String> = categories
        .filter { category -> category.specs.any { it.name in settingNames } }
        .mapTo(linkedSetOf()) { it.id }

    // Defense in depth: these can never become shared even if a future caller bypasses the UI.
    fun requireAllowed(name: String): EsdeSettingSpec = byName[name]
        ?: throw IllegalArgumentException("Unknown or forbidden ES-DE setting: $name")

    private fun category(id: String, title: String, summary: String): EsdeSettingCategory {
        val categorySpecs = specs.filter { it.category == title }
        require(categorySpecs.isNotEmpty()) { "Shared ES-DE settings category has no allowlisted values: $title" }
        return EsdeSettingCategory(id, title, summary, categorySpecs)
    }

}
