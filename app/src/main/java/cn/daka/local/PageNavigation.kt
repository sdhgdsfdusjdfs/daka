package cn.daka.local

class PageNavigation {
    var current = "home"
        private set
    private val history = java.util.ArrayDeque<String>()

    fun open(destination: String) {
        if (destination == current) return
        history.addLast(current)
        current = destination
    }

    fun selectTab(destination: String) {
        history.clear()
        if (destination != "home") history.addLast("home")
        current = destination
    }

    fun back(): Boolean {
        if (history.isEmpty()) return false
        current = history.removeLast()
        return true
    }

    fun snapshot(): List<String> = history.toList() + current

    fun restore(pages: List<String>) {
        val allowed = setOf("home", "plans", "logs", "settings", "permissions")
        if (pages.isEmpty() || pages.any { it !in allowed }) return
        history.clear()
        pages.dropLast(1).forEach(history::addLast)
        current = pages.last()
    }
}
