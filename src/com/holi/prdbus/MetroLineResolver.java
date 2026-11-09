package com.holi.prdbus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将高德返回的线路名称稳定映射到 App 内部地铁数据。 */
final class MetroLineResolver {
    private static final Pattern LINE_NUMBER = Pattern.compile("(\\d+)(?=/|号线)");
    private static final String[] ALIASES = {
            "广佛", "APM", "知识城", "罗宝", "龙岗", "龙华", "环中", "光明",
            "西丽", "梅林", "坂田", "机场", "南宝", "石岩", "东部快", "龙坪"
    };

    static final class Match {
        final MetroDataSource.MetroLine line;
        final int score;

        Match(MetroDataSource.MetroLine line, int score) {
            this.line = line;
            this.score = score;
        }
    }

    private MetroLineResolver() {
    }

    static List<Match> rank(List<MetroDataSource.MetroLine> lines, String rawName,
                            String from, String to, String currentCity) {
        List<Match> ranked = new ArrayList<>();
        for (MetroDataSource.MetroLine line : lines) {
            int score = score(line, rawName, from, to, currentCity);
            if (score >= 45) ranked.add(new Match(line, score));
        }
        Collections.sort(ranked, new Comparator<Match>() {
            @Override
            public int compare(Match a, Match b) {
                int c = Integer.compare(b.score, a.score);
                return c != 0 ? c : a.line.name.compareToIgnoreCase(b.line.name);
            }
        });
        if (ranked.isEmpty()) return ranked;
        int floor = Math.max(45, ranked.get(0).score - 18);
        List<Match> close = new ArrayList<>();
        for (Match match : ranked) {
            if (match.score < floor || close.size() >= 5) break;
            close.add(match);
        }
        return close;
    }

    static MetroDataSource.MetroLine best(List<MetroDataSource.MetroLine> lines,
                                          String rawName, String from, String to,
                                          String currentCity) {
        List<Match> ranked = rank(lines, rawName, from, to, currentCity);
        return ranked.isEmpty() ? null : ranked.get(0).line;
    }

    static int stationIndex(MetroDataSource.MetroLine line, String stopName) {
        String wanted = normalizeStop(stopName);
        if (wanted.isEmpty()) return -1;
        for (int i = 0; i < line.stations.size(); i++) {
            String actual = normalizeStop(line.stations.get(i).name);
            if (actual.equals(wanted) || (actual.length() >= 3 && wanted.contains(actual))
                    || (wanted.length() >= 3 && actual.contains(wanted))) return i;
        }
        return -1;
    }

    static String displayName(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replace('（', '(').replace('）', ')');
        int paren = value.indexOf('(');
        if (paren > 0) value = value.substring(0, paren);
        int arrows = value.indexOf("--");
        if (arrows > 0) value = value.substring(0, arrows);
        return value.trim();
    }

    static String interchangeLabel(String raw) {
        String value = displayName(raw);
        String city = cityIn(value);
        value = value.replaceFirst("^(轨道交通|地铁)", "");
        if (!city.isEmpty()) value = value.substring(city.length());
        return value.replaceFirst("^(轨道交通|地铁)", "").trim();
    }

    private static int score(MetroDataSource.MetroLine line, String rawName,
                             String from, String to, String currentCity) {
        if (from != null && !from.isEmpty()
                && to != null && !to.isEmpty()
                && (stationIndex(line, from) < 0 || stationIndex(line, to) < 0)) return Integer.MIN_VALUE;
        String raw = normalizeLine(rawName);
        String own = normalizeLine(line.name);
        int score = 0;
        if (raw.equals(own)) score += 120;
        else if (!raw.isEmpty() && (raw.contains(own) || own.contains(raw))) score += 62;

        Set<String> rawNumbers = lineNumbers(raw);
        Set<String> ownNumbers = lineNumbers(own);
        Set<String> common = new HashSet<>(rawNumbers);
        common.retainAll(ownNumbers);
        if (!common.isEmpty()) score += 46 + common.size() * 12;
        if (!rawNumbers.isEmpty() && rawNumbers.equals(ownNumbers)) score += 26;

        for (String alias : ALIASES) {
            String n = alias.toUpperCase();
            if (raw.toUpperCase().contains(n) && own.toUpperCase().contains(n)) score += 65;
        }
        if (raw.contains("支线") == own.contains("支线")) score += 8;
        else if (raw.contains("支线") || own.contains("支线")) score -= 30;
        if (raw.contains("北延") == own.contains("北延")) score += 7;
        else if (raw.contains("北延") || own.contains("北延")) score -= 22;

        String rawCity = cityIn(rawName);
        String ownCity = MetroDataSource.cityOfLine(line);
        boolean crossCity = "广佛线".equals(line.name) && ("佛山".equals(rawCity) || "广州".equals(rawCity));
        if (!rawCity.isEmpty() && !rawCity.equals(ownCity) && !crossCity) return Integer.MIN_VALUE;
        if (!rawCity.isEmpty()) score += 42;
        else if (currentCity != null && currentCity.equals(ownCity)) {
            // With only a line number, prefer the current city's matching network,
            // including a combined identity such as Shenzhen 2/8 over another city's 2.
            boolean noEndpoints = (from == null || from.isEmpty()) && (to == null || to.isEmpty());
            score += noEndpoints && !common.isEmpty() ? 100 : 7;
        }

        if (from != null && !from.trim().isEmpty()) {
            score += stationIndex(line, from) >= 0 ? 72 : -18;
        }
        if (to != null && !to.trim().isEmpty()) {
            score += stationIndex(line, to) >= 0 ? 72 : -18;
        }
        return score;
    }

    private static Set<String> lineNumbers(String value) {
        Set<String> result = new HashSet<>();
        Matcher matcher = LINE_NUMBER.matcher(value);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private static String cityIn(String value) {
        if (value == null) return "";
        return PublicMetro.owner(value.trim().replaceFirst("^(轨道交通|地铁)", ""));
    }

    private static String normalizeLine(String value) {
        String s = displayName(value).replace("轨道交通", "").replace("地铁", "")
                .replace("线路", "线").replace(" ", "").replace("－", "-")
                .replace("号线线", "号线");
        return s.toUpperCase();
    }

    private static String normalizeStop(String value) {
        if (value == null) return "";
        String s = value.trim().replace("地铁站", "").replace("站", "")
                .replace("朗", "塱").replace(" ", "")
                .replace('（', '(').replace('）', ')');
        s = s.replaceAll("\\([^)]*(方向|出口|航站楼)[^)]*\\)", "");
        return s;
    }
}
