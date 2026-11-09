package com.holi.prdbus;

/** Pure revision rules for the independent app and optional AMap notices. */
final class PrivacyRules {
    static final int NOTICE_REVISION = 1;
    static final int AMAP_REVISION = 1;

    private PrivacyRules() {}

    static boolean needsNotice(int seenRevision) {
        return seenRevision < NOTICE_REVISION;
    }

    static boolean needsAmap(boolean keyPresent, int seenRevision) {
        return keyPresent && seenRevision < AMAP_REVISION;
    }

    static boolean locationAllowed(int seenRevision) {
        return seenRevision >= NOTICE_REVISION;
    }

    static boolean amapAllowed(boolean keyPresent, int seenRevision) {
        return keyPresent && seenRevision >= AMAP_REVISION;
    }
}
