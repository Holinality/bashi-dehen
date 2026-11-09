package com.holi.prdbus;

/** 路线规划省域过滤的纯逻辑回归测试。 */
public final class GuangdongFilterSelfTest {
    public static void main(String[] args) {
        check(ChelaileDataSource.isGuangdongAdministrative(
                "440106", "440000", "广东省", "广东省广州市天河区"));
        check(ChelaileDataSource.isGuangdongAdministrative(
                "", "440000", "", "珠海市香洲区"));
        check(ChelaileDataSource.isGuangdongAdministrative(
                "", "", "广东省", "深圳市南山区"));
        reject(ChelaileDataSource.isGuangdongAdministrative(
                "430102", "430000", "湖南省", "长沙市芙蓉区"));
        reject(ChelaileDataSource.isGuangdongAdministrative(
                "450103", "450000", "广西壮族自治区", "南宁市青秀区"));
        reject(ChelaileDataSource.isGuangdongAdministrative("", "", "", ""));
        System.out.println("GuangdongFilterSelfTest passed");
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError("Expected Guangdong result");
    }

    private static void reject(boolean value) {
        if (value) throw new AssertionError("Expected non-Guangdong result");
    }
}
