package com.holi.prdbus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class OsmAccessStateSelfTest {
    public static void main(String[] args) throws Exception {
        firstCheckAndCache();
        retryAndNetworkChanges();
        tileFailuresAndStaleWork();
        refreshKeepsWorkingMap();
        concurrentChecks();
        System.out.println("OsmAccessStateSelfTest passed (cache, retry, concurrency, stale checks/tiles, visibility)");
    }

    private static void firstCheckAndCache() {
        OsmAccessState state = new OsmAccessState();
        require(!state.available(), "unverified map visible");
        long initialGeneration = state.generation();
        require(!state.recordTileResult(initialGeneration, true, 0L), "tile revealed unverified map");
        require(!state.available(), "initial tile bypassed reachability check");
        long ticket = state.beginCheck(0L);
        require(ticket > 0L && state.generation() != initialGeneration, "initial check not started");
        require(state.beginCheck(1L) == 0L, "duplicate concurrent check");
        require(!state.completeCheck(ticket + 1L, true, 10L), "foreign ticket accepted");
        require(!state.available(), "wrong check changed visibility");
        long checkingGeneration = state.generation();
        require(state.completeCheck(ticket, true, 10L), "current success ignored");
        require(state.available() && state.generation() != checkingGeneration, "success not committed");
        require(!state.completeCheck(ticket, false, 11L), "duplicate check accepted");
        require(state.available(), "duplicate check hid map");
        require(state.beginCheck(10L + OsmAccessState.SUCCESS_TTL_MILLIS - 1L) == 0L,
                "success TTL not respected");
        require(state.beginCheck(10L + OsmAccessState.SUCCESS_TTL_MILLIS) > 0L,
                "success cache never expired");
        require(state.available(), "expired cache hid map before recheck completed");
    }

    private static void retryAndNetworkChanges() {
        OsmAccessState state = new OsmAccessState();
        long ticket = state.beginCheck(0L);
        require(state.completeCheck(ticket, false, 20L), "failure ignored");
        require(!state.available(), "failed check visible");
        require(state.beginCheck(20L + OsmAccessState.FAILURE_TTL_MILLIS - 1L) == 0L,
                "failure immediately retried");
        require(!state.recordTileResult(state.generation(), true, 1_000L), "hidden tile changed visibility");
        long retry = state.beginCheck(20L + OsmAccessState.FAILURE_TTL_MILLIS);
        require(retry > 0L && retry != ticket, "failure cache never expired or ticket reused");
        long beforeNetwork = state.generation();
        state.networkChanged();
        require(!state.available() && state.generation() != beforeNetwork, "network reset incomplete");
        long newNetwork = state.beginCheck(60_021L);
        require(newNetwork > 0L && newNetwork != retry, "old in-flight check blocked new network");
        require(!state.completeCheck(retry, true, 60_022L), "old-network check accepted");
        require(!state.available(), "old network exposed map");
        require(state.completeCheck(newNetwork, true, 60_023L), "new network check rejected");
        state.networkChanged();
        require(!state.available() && state.beginCheck(60_024L) > 0L, "network kept success cache");
    }

    private static void tileFailuresAndStaleWork() {
        OsmAccessState state = verified(100L);
        long generation = state.generation();
        require(!state.recordTileResult(generation, false, 200L), "one failure hid map");
        require(!state.recordTileResult(generation, false, 210L), "two failures hid map");
        require(!state.recordTileResult(generation, true, 220L), "success changed working visibility");
        require(!state.recordTileResult(generation, false, 230L), "success did not reset failure streak");
        require(!state.recordTileResult(generation, false, 240L), "second post-success failure hid map");
        require(state.recordTileResult(generation, false, 250L) && !state.available(),
                "three consecutive failures did not hide map");
        require(!state.recordTileResult(generation, false, 260L), "hidden failure changed visibility again");
        require(!state.recordTileResult(generation, true, 270L) && !state.available(),
                "late tile reopened hidden map");
        require(state.beginCheck(250L + OsmAccessState.FAILURE_TTL_MILLIS - 1L) == 0L,
                "tile failure retry not limited");
        long ticket = state.beginCheck(250L + OsmAccessState.FAILURE_TTL_MILLIS);
        require(ticket > 0L, "hidden tile results extended failed cache indefinitely");
        long duringCheck = state.generation();
        require(state.completeCheck(ticket, true, 60_251L), "tile failure recovery not accepted");
        require(!state.recordTileResult(generation, false, 60_252L), "precheck tile accepted");
        require(!state.recordTileResult(duringCheck, false, 60_253L), "in-check tile accepted");
        require(state.available(), "stale tile hid recovered map");
        long recovered = state.generation();
        state.networkChanged();
        require(!state.recordTileResult(recovered, true, 60_254L) && !state.available(),
                "old-network tile exposed map");
    }

    private static void refreshKeepsWorkingMap() {
        OsmAccessState state = verified(100L);
        long generation = state.generation();
        state.recordTileResult(generation, true, 1_000L);
        require(state.beginCheck(100L + OsmAccessState.SUCCESS_TTL_MILLIS) == 0L,
                "working tile did not extend success cache");
        long ticket = state.beginCheck(1_000L + OsmAccessState.SUCCESS_TTL_MILLIS);
        require(ticket > 0L && state.available(), "recheck interrupted working map");
        long whileChecking = state.generation();
        require(state.completeCheck(ticket, false, 301_001L) && !state.available(), "failed refresh remained visible");
        require(!state.recordTileResult(whileChecking, true, 301_002L) && !state.available(),
                "old tile reversed failed refresh");
        OsmAccessState clockReset = verified(1_000L);
        require(clockReset.beginCheck(0L) > 0L, "clock reset pinned stale cache");
    }

    private static void concurrentChecks() throws Exception {
        OsmAccessState state = new OsmAccessState();
        ExecutorService workers = Executors.newFixedThreadPool(12);
        try {
            CountDownLatch ready = new CountDownLatch(12);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) results.add(workers.submit(() -> {
                ready.countDown();
                start.await();
                return state.beginCheck(0L);
            }));
            ready.await();
            start.countDown();
            int started = 0;
            long ticket = 0L;
            for (Future<Long> result : results) {
                long value = result.get();
                if (value > 0L) { started++; ticket = value; }
            }
            require(started == 1, "parallel callers started more than one check");
            require(state.completeCheck(ticket, true, 1L), "concurrent winner could not finish");
            require(state.beginCheck(2L) == 0L && state.available(), "completed cache not shared");
        } finally {
            workers.shutdownNow();
        }
    }

    private static OsmAccessState verified(long now) {
        OsmAccessState state = new OsmAccessState();
        require(state.completeCheck(state.beginCheck(now), true, now), "test setup failed");
        return state;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
