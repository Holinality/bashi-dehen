package com.holi.prdbus;

/** Process-local OSM reachability state. All timestamps use the same monotonic clock. */
final class OsmAccessState {
    static final long SUCCESS_TTL_MILLIS = 5L * 60L * 1_000L;
    static final long FAILURE_TTL_MILLIS = 60L * 1_000L;
    private static final int FAILURE_THRESHOLD = 3;

    private boolean available;
    private boolean hasResult;
    private long resultAt;
    private long resultTtl;
    private long generation = 1L;
    private long ticketSequence;
    private long checkingTicket;
    private int consecutiveFailures;

    synchronized boolean available() {
        return available;
    }

    /** Returns a positive ticket only for the caller that should start a network check. */
    synchronized long beginCheck(long now) {
        if (checkingTicket != 0L || cacheFresh(now)) return 0L;
        // Work started before this check must not influence its result.
        generation = nextPositive(generation);
        ticketSequence = nextPositive(ticketSequence);
        checkingTicket = ticketSequence;
        return checkingTicket;
    }

    /** Accepts only the current check; stale or duplicate completion has no effect. */
    synchronized boolean completeCheck(long ticket, boolean ok, long now) {
        if (ticket <= 0L || ticket != checkingTicket) return false;
        checkingTicket = 0L;
        available = ok;
        rememberResult(now, ok ? SUCCESS_TTL_MILLIS : FAILURE_TTL_MILLIS);
        consecutiveFailures = 0;
        // Also invalidate tile work that started while the check was running.
        generation = nextPositive(generation);
        return true;
    }

    /** A different network requires a new check, including if an old one is still running. */
    synchronized void networkChanged() {
        available = false;
        hasResult = false;
        checkingTicket = 0L;
        consecutiveFailures = 0;
        generation = nextPositive(generation);
    }

    /**
     * Records actual tile requests for the generation captured when that request started.
     * Only a successful explicit check can reveal a hidden map. A lone failed tile is not
     * enough to hide an otherwise working map; a later success resets that failure streak.
     * Returns whether visibility changed.
     */
    synchronized boolean recordTileResult(long requestGeneration, boolean success, long now) {
        if (requestGeneration != generation) return false;
        if (success) {
            consecutiveFailures = 0;
            if (available) rememberResult(now, SUCCESS_TTL_MILLIS);
            return false;
        }
        if (!available) return false;
        consecutiveFailures++;
        if (consecutiveFailures < FAILURE_THRESHOLD) return false;
        available = false;
        consecutiveFailures = 0;
        rememberResult(now, FAILURE_TTL_MILLIS);
        return true;
    }

    synchronized long generation() {
        return generation;
    }

    private boolean cacheFresh(long now) {
        // A clock reset must not turn an old result into an effectively permanent cache.
        return hasResult && now >= resultAt && now - resultAt < resultTtl;
    }

    private void rememberResult(long now, long ttl) {
        hasResult = true;
        resultAt = now;
        resultTtl = ttl;
    }

    private static long nextPositive(long value) {
        return value == Long.MAX_VALUE ? 1L : value + 1L;
    }
}
