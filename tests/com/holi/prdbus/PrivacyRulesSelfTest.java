package com.holi.prdbus;

/** Plain-JDK scenarios; no Android Context, preferences, permissions or network. */
public final class PrivacyRulesSelfTest {
    private static int scenarios;

    public static void main(String[] args) {
        freshInstall();
        upgradeWithExistingKey();
        repeatedLaunch();
        addingAndReplacingKey();
        noKeyDoesNotShowAmap();
        independentlyStoredStages();
        futureRevisionIsPreserved();
        negativeRevisionIsUnread();
        firstStageDoesNotAuthorizeAmap();
        removingKeyDisablesAmap();
        require(PrivacyRules.NOTICE_REVISION == 1 && PrivacyRules.AMAP_REVISION == 1,
                "initial notice revisions changed without updating migration cases");
        System.out.println("PrivacyRulesSelfTest passed (" + scenarios
                + " scenarios: first use, upgrade, repeat, independent stages, key replacement and revision bounds)");
    }

    private static void freshInstall() {
        int noticeSeen = 0, amapSeen = 0;
        require(PrivacyRules.needsNotice(noticeSeen), "fresh install skipped the app notice");
        require(!PrivacyRules.locationAllowed(noticeSeen), "fresh install could acquire location before its notice");
        require(!PrivacyRules.needsAmap(false, amapSeen), "no-key install showed an unrelated AMap notice");
        noticeSeen = PrivacyRules.NOTICE_REVISION;
        require(!PrivacyRules.needsNotice(noticeSeen) && PrivacyRules.locationAllowed(noticeSeen),
                "acknowledging app notice did not unlock location for that stage");
        scenarios++;
    }

    private static void upgradeWithExistingKey() {
        int noticeSeen = 0, amapSeen = 0;
        require(PrivacyRules.needsNotice(noticeSeen) && !PrivacyRules.locationAllowed(noticeSeen),
                "an old installation bypassed the new app notice");
        require(PrivacyRules.needsAmap(true, amapSeen) && !PrivacyRules.amapAllowed(true, amapSeen),
                "an existing saved key bypassed its first AMap notice");
        noticeSeen = PrivacyRules.NOTICE_REVISION;
        require(PrivacyRules.locationAllowed(noticeSeen) && PrivacyRules.needsAmap(true, amapSeen),
                "app notice incorrectly consumed the separate AMap notice");
        amapSeen = PrivacyRules.AMAP_REVISION;
        require(PrivacyRules.amapAllowed(true, amapSeen), "acknowledging AMap notice did not unlock configured AMap");
        scenarios++;
    }

    private static void repeatedLaunch() {
        for (int launch = 0; launch < 5; launch++) {
            require(!PrivacyRules.needsNotice(1), "app notice reappeared on a repeated launch");
            require(!PrivacyRules.needsAmap(true, 1), "AMap notice reappeared on a repeated launch");
            require(PrivacyRules.locationAllowed(1) && PrivacyRules.amapAllowed(true, 1),
                    "acknowledged stage lost its effect on a repeated launch");
        }
        scenarios++;
    }

    private static void addingAndReplacingKey() {
        int amapSeen = 0;
        require(!PrivacyRules.needsAmap(false, amapSeen), "AMap was shown before configuring a key");
        require(PrivacyRules.needsAmap(true, amapSeen), "adding a first key skipped the AMap notice");
        amapSeen = PrivacyRules.AMAP_REVISION;
        // Replacing credentials does not change the notice's revision or saved flag.
        require(!PrivacyRules.needsAmap(true, amapSeen) && PrivacyRules.amapAllowed(true, amapSeen),
                "replacing a key asked for the same AMap notice again");
        scenarios++;
    }

    private static void noKeyDoesNotShowAmap() {
        for (int seen : new int[]{Integer.MIN_VALUE, -1, 0, 1, 2, Integer.MAX_VALUE}) {
            require(!PrivacyRules.needsAmap(false, seen), "AMap notice appeared without a configured key");
            require(!PrivacyRules.amapAllowed(false, seen), "AMap was allowed with no configured key");
        }
        scenarios++;
    }

    private static void independentlyStoredStages() {
        require(PrivacyRules.locationAllowed(1) && PrivacyRules.needsAmap(true, 0),
                "app revision and AMap revision were conflated");
        require(PrivacyRules.needsNotice(0) && PrivacyRules.amapAllowed(true, 1),
                "AMap revision should not rewrite the separate app revision");
        scenarios++;
    }

    private static void futureRevisionIsPreserved() {
        for (int seen : new int[]{2, 10, Integer.MAX_VALUE}) {
            require(!PrivacyRules.needsNotice(seen) && PrivacyRules.locationAllowed(seen),
                    "downgrading from a newer notice version caused the app notice to repeat");
            require(!PrivacyRules.needsAmap(true, seen) && PrivacyRules.amapAllowed(true, seen),
                    "downgrading from a newer notice version caused the AMap notice to repeat");
        }
        scenarios++;
    }

    private static void negativeRevisionIsUnread() {
        for (int seen : new int[]{-1, -2, Integer.MIN_VALUE}) {
            require(PrivacyRules.needsNotice(seen) && !PrivacyRules.locationAllowed(seen),
                    "negative app revision was treated as already read");
            require(PrivacyRules.needsAmap(true, seen) && !PrivacyRules.amapAllowed(true, seen),
                    "negative AMap revision was treated as already read");
        }
        scenarios++;
    }

    private static void firstStageDoesNotAuthorizeAmap() {
        require(PrivacyRules.locationAllowed(1) && !PrivacyRules.amapAllowed(true, 0),
                "reading the general notice alone permitted a configured AMap call");
        scenarios++;
    }

    private static void removingKeyDisablesAmap() {
        require(PrivacyRules.amapAllowed(true, 1), "test setup must have AMap enabled");
        require(!PrivacyRules.amapAllowed(false, 1), "removing key left AMap active");
        require(!PrivacyRules.needsAmap(true, 1), "restoring key repeated an unchanged notice");
        scenarios++;
    }

    private static void require(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
}
