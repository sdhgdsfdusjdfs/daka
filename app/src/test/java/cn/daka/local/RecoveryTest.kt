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

    @Test fun workbenchMustMatchOrganizationAndHaveNoSuccessModal() {
        val workbench = listOf("数统803", "今日考勤", "上班打卡 09:10")
        assertTrue(ExitEvidence.isWorkbench(workbench, "数统803"))
        assertTrue(ExitEvidence.isWorkbench(listOf("数统803", "今日考勤", "外勤打卡"), "数统803"))
        assertFalse(ExitEvidence.isWorkbench(workbench, "其他组织"))
        assertFalse(ExitEvidence.isWorkbench(workbench + "打卡成功", "数统803"))
        assertFalse(ExitEvidence.isWorkbench(listOf("数统803", "登录"), "数统803"))
        assertFalse(ExitEvidence.isWorkbench(workbench, ""))
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
