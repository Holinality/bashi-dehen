package com.holi.prdbus;

/** Public source builds use their own signing identity and explicit data sources. */
final class PublicBuildConfig {
    private PublicBuildConfig() {}
    // A locally signed build cannot install the maintainer's signed update APK.
    static final boolean OFFICIAL_UPDATES = false;
    // Public builds do not silently download and cache undocumented subway data.
    // A configured personal AMap Web Key still enables the documented API path.
    static final boolean ALLOW_UNDOCUMENTED_METRO_FETCH = false;
}
