package com.holi.prdbus;

import java.util.ArrayList;
import java.util.List;

/** 附近站点（公交站或地铁站）。同名双向站台已合并，保留各方向原始站名用于查询。 */
final class NearbyStation {
    final String name;
    final double lat;
    final double lon;
    final double distanceKm;
    final boolean subway;
    /** 合并的站台数量（一般双向站台为 2）。 */
    final int platformCount;
    /** 该站所有方向的原始站名（Code410 按站台精确匹配，需分别查询）。 */
    final List<String> queryNames;

    NearbyStation(String name, double lat, double lon, double distanceKm, boolean subway,
                  int platformCount, List<String> queryNames) {
        this.name = name;
        this.lat = lat;
        this.lon = lon;
        this.distanceKm = distanceKm;
        this.subway = subway;
        this.platformCount = platformCount;
        this.queryNames = queryNames == null ? new ArrayList<String>() : queryNames;
    }

    String typeLabel() {
        return subway ? "地铁" : "公交";
    }

    /** 站台描述：多于一个站台时标注。 */
    String platformLabel() {
        return platformCount > 1 ? " · " + platformCount + " 个站台" : "";
    }
}
