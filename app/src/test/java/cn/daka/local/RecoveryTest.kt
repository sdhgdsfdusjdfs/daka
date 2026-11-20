package cn.daka.local

import org.junit.Assert.*
import org.junit.Test

class RecoveryTest {
    @Test fun retriesRespectGlobalBudgetAndAttemptCap() {
        assertTrue(RetryPolicy.canRetry(1, 40_000))
        assertTrue(RetryPolicy.canRetry(2, 19_000))
        assertFalse(RetryPolicy.canRetry(3, 40_000))
        assertFalse(RetryPolicy.canRetry(1, 18_999))
        assertFalse(RetryPolicy.canRetry(1, -100))
    }

    @Test fun forceStopRequiresCorrectSystemPageAndApplication() {
        assertTrue(StopSafety.isTargetHeader("com.android.settings", "得力e+", "得力e+"))
        assertFalse(StopSafety.isTargetHeader("com.android.settings", "支付宝", "得力e+"))
        assertFalse(StopSafety.isTargetHeader("some.ad.app", "得力e+", "得力e+"))
        assertFalse(StopSafety.mayConfirm("com.android.settings", false, "强行停止该应用？"))
        assertFalse(StopSafety.mayConfirm("com.android.settings", true, "卸载该应用？"))
        assertTrue(StopSafety.mayConfirm("com.android.settings", true, "强行停止该应用？"))
    }

    @Test fun navigationReturnsToParentInsteadOfLeavingApp() {
        val nav = PageNavigation()
        nav.open("settings")
        nav.open("permissions")
        assertTrue(nav.back())
        assertEquals("settings", nav.current)
        assertTrue(nav.back())
        assertEquals("home", nav.current)
        assertFalse(nav.back())
    }

    @Test fun rerenderDoesNotGrowHistoryAndTabsHaveHomeParent() {
        val nav = PageNavigation()
        nav.open("settings")
        nav.open("settings")
        nav.selectTab("logs")
        assertTrue(nav.back())
        assertEquals("home", nav.current)
        assertFalse(nav.back())
    }

    @Test fun restoredPageKeepsItsParent() {
        val first = PageNavigation()
        first.open("settings")
        first.open("permissions")
        val restored = PageNavigation()
        restored.restore(first.snapshot())
        assertEquals("permissions", restored.current)
        assertTrue(restored.back())
        assertEquals("settings", restored.current)
    }
}
