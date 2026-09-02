package ru.arc.menu

@ConsistentCopyVisibility
data class MenuPageState private constructor(
    val totalItems: Int,
    val pageSize: Int,
    val pageIndex: Int,
    val pageCount: Int,
) {
    val displayPage: Int get() = pageIndex + 1
    val hasPrevious: Boolean get() = pageIndex > 0
    val hasNext: Boolean get() = pageIndex + 1 < pageCount

    fun first(): MenuPageState = copy(pageIndex = 0)
    fun previous(): MenuPageState = copy(pageIndex = (pageIndex - 1).coerceAtLeast(0))
    fun next(): MenuPageState = copy(pageIndex = (pageIndex + 1).coerceAtMost(pageCount - 1))
    fun at(index: Int): MenuPageState = copy(pageIndex = index.coerceIn(0, pageCount - 1))

    fun <T> slice(items: List<T>): List<T> {
        require(items.size == totalItems) {
            "Page content size ${items.size} does not match declared total $totalItems"
        }
        val from = pageIndex * pageSize
        val until = (from + pageSize).coerceAtMost(items.size)
        return items.subList(from.coerceAtMost(items.size), until)
    }

    companion object {
        fun of(totalItems: Int, pageSize: Int, requestedPage: Int = 0): MenuPageState {
            require(totalItems >= 0) { "Menu page total must be non-negative" }
            require(pageSize > 0) { "Menu page size must be positive" }
            val pageCount = ((totalItems + pageSize - 1) / pageSize).coerceAtLeast(1)
            return MenuPageState(totalItems, pageSize, requestedPage.coerceIn(0, pageCount - 1), pageCount)
        }
    }
}
