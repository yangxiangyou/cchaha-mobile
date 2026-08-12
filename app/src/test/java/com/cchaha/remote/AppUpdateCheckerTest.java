package com.cchaha.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 语义化版本比较验证（AppUpdateChecker.compareVersions） */
public class AppUpdateCheckerTest {

    @Test
    public void compareBasic() {
        assertTrue(AppUpdateChecker.compareVersions("1.10.0", "1.9.0") > 0);
        assertTrue(AppUpdateChecker.compareVersions("1.4.2", "1.4.1") > 0);
        assertEquals(0, AppUpdateChecker.compareVersions("1.4.2", "1.4.2"));
        assertTrue(AppUpdateChecker.compareVersions("1.4.1", "1.4.2") < 0);
    }

    @Test
    public void compareDifferentSegmentCounts() {
        assertTrue(AppUpdateChecker.compareVersions("1.5", "1.4.9") > 0);
        assertTrue(AppUpdateChecker.compareVersions("1.4.10", "1.4.2") > 0); // 字符串比较会误判
        assertTrue(AppUpdateChecker.compareVersions("2.0", "1.9.9") > 0);
    }

    @Test
    public void compareWithNonNumericSuffix() {
        assertTrue(AppUpdateChecker.compareVersions("1.4.10", "1.4.9") > 0);
        // 非数字后缀被剥掉：1.4.10-beta 与 1.4.10 视为同版本（tag 无后缀场景不误判）
        assertEquals(0, AppUpdateChecker.compareVersions("1.4.10-beta", "1.4.10"));
        assertEquals(0, AppUpdateChecker.compareVersions("1.4.2", "1.4.2-alpha"));
    }

    @Test
    public void compareEmptyOrGarbage() {
        assertEquals(0, AppUpdateChecker.compareVersions("", ""));
        assertTrue(AppUpdateChecker.compareVersions("1.4", "") > 0);
        assertTrue(AppUpdateChecker.compareVersions("", "1.4") < 0);
    }
}
