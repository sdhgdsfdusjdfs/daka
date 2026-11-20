package cn.daka.local

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubRoutesTest {
    @Test fun repositoryLookupDoesNotHaveTrailingSlash() {
        assertEquals(
            "https://api.github.com/repos/sdhgdsfdusjdfs/daka",
            GitHubRoutes.repositoryUrl("sdhgdsfdusjdfs/daka", "")
        )
    }

    @Test fun branchAndContentsRoutesRemainIntact() {
        assertEquals(
            "https://api.github.com/repos/owner/repo/branches/device-logs",
            GitHubRoutes.repositoryUrl("owner/repo", "branches/device-logs")
        )
        assertEquals(
            "https://api.github.com/repos/owner/repo/contents/devices/vivo-test/status.md?ref=device-logs",
            GitHubRoutes.repositoryUrl("owner/repo", "contents/devices/vivo-test/status.md?ref=device-logs")
        )
    }
}
