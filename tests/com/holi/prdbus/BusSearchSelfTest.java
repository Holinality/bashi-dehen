package com.holi.prdbus;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class BusSearchSelfTest {
    public static void main(String[] args) throws Exception {
        if (MygolbsDataSource.arrivalSeconds(8, 7, 0, false) != 0
                || MygolbsDataSource.arrivalSeconds(8, 9, 0, false) != -1
                || MygolbsDataSource.arrivalSeconds(8, 7, -1, false) != -1)
            throw new AssertionError("zero minutes vs missing or passed ETA");
        if (!"2元".equals(MygolbsDataSource.fare("票价2元。"))
                || !"2-6元".equals(MygolbsDataSource.fare("票价2-6元"))
                || !"1.5元".equals(MygolbsDataSource.fare("票价：1.5元，刷卡优惠")))
            throw new AssertionError("fare punctuation and range");
        if (!"37路".equals(BusSearch.normalize(" ３７路 "))) throw new AssertionError("full-width input");
        ChelaileDataSource.SearchLine forward = line("cl1", "37路", "甲", "乙");
        ChelaileDataSource.SearchLine reverse = line("mg2", "37路", "乙", "甲");
        ChelaileDataSource.SearchResult first = result(line("137", "137路", "甲", "丙"), forward);
        ChelaileDataSource.SearchResult second = result(line("mg1", "37路", "甲", "乙"), reverse);
        ChelaileDataSource.SearchResult merged = BusSearch.merge("37", 0, 0, first, second);
        if (merged.lines.size() != 3 || !"cl1".equals(merged.lines.get(0).lineId)
                || !"mg2".equals(merged.lines.get(1).lineId)) throw new AssertionError("rank/directions/provider priority");
        if (BusSearch.exact("37", result(line("137", "137路", "甲", "乙"))))
            throw new AssertionError("partial route should allow fallback");
        RequestReuse<String> reuse = new RequestReuse<>(1000, 4);
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> owner = workers.submit(() -> reuse.get("city|37", () -> {
                requests.incrementAndGet(); entered.countDown(); release.await(); return "ok";
            }));
            if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("request did not start");
            Future<String> follower = workers.submit(() -> reuse.get("city|37", () -> {
                requests.incrementAndGet(); return "duplicate";
            }));
            release.countDown();
            if (!"ok".equals(owner.get()) || !"ok".equals(follower.get()) || requests.get() != 1)
                throw new AssertionError("duplicate request");
            if (!"other".equals(reuse.get("otherCity|37", () -> "other")))
                throw new AssertionError("city cache isolation");
        } finally { release.countDown(); workers.shutdownNow(); }
        System.out.println("BusSearchSelfTest passed (ranking, direction, full-width input, city isolation, request reuse)");
    }
    private static ChelaileDataSource.SearchLine line(String id, String name, String from, String to) {
        return new ChelaileDataSource.SearchLine(id, name, name, from, to, 0);
    }
    private static ChelaileDataSource.SearchResult result(ChelaileDataSource.SearchLine... lines) {
        return new ChelaileDataSource.SearchResult(Arrays.asList(lines), Collections.emptyList());
    }
}
