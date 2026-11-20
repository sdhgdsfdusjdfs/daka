package cn.daka.local

object GitHubRoutes {
    fun repositoryUrl(repository: String, path: String = ""): String {
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository)) {
            "仓库格式应为 owner/repo"
        }
        val base = "https://api.github.com/repos/$repository"
        return if (path.isEmpty()) base else "$base/${path.removePrefix("/")}"
    }
}
