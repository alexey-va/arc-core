package ru.arc.paper.metrics.client

import ru.arc.paper.api.ArcMetricsService
import java.util.concurrent.TimeUnit

/** Test consumer compiled against only the Paper metrics API contract. */
object ApiOnlyMetricsClient {
    @JvmStatic
    fun record(service: ArcMetricsService) {
        val scope = service.open("ArcFarms")
        scope.counter("arc_farms_actions", "Completed actions", emptyMap()).increment(3.0)
        scope.gauge("arc_farms_workers", "Current workers", emptyMap()).set(2.5)
        scope.timer("arc_farms_action_duration", "Action duration", emptyMap()).record(12L, TimeUnit.MILLISECONDS)
    }
}
