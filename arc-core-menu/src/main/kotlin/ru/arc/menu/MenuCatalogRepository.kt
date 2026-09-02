package ru.arc.menu

import java.util.concurrent.atomic.AtomicReference

sealed interface MenuCatalogReplaceResult {
    data class Replaced(val previous: MenuCatalog, val current: MenuCatalog) : MenuCatalogReplaceResult
    data class Rejected(val issues: List<MenuValidationIssue>) : MenuCatalogReplaceResult
}

class MenuCatalogRepository(initial: MenuCatalog) {
    private val current = AtomicReference(initial)

    fun current(): MenuCatalog = current.get()

    fun replace(candidate: MenuCatalog): MenuCatalogReplaceResult.Replaced {
        while (true) {
            val previous = current.get()
            val next = candidate.withGeneration(previous.generation + 1)
            if (current.compareAndSet(previous, next)) {
                return MenuCatalogReplaceResult.Replaced(previous, next)
            }
        }
    }

    fun replace(candidate: MenuCatalogLoadResult): MenuCatalogReplaceResult = when (candidate) {
        is MenuCatalogLoadResult.Loaded -> replace(candidate.catalog)
        is MenuCatalogLoadResult.Rejected -> MenuCatalogReplaceResult.Rejected(candidate.issues)
    }
}
