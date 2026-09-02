package ru.arc.paper.menu

import ru.arc.menu.MenuPageState

internal fun pageState(totalItems: Int, pageSize: Int, requestedPage: Int): MenuPageState =
    MenuPageState.of(totalItems, pageSize, requestedPage)
