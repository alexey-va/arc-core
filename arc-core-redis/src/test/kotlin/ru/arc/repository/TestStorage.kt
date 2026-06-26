package ru.arc.repository

/**
 * Test entity implementation.
 */
data class TestEntity(
    private val _id: String,
    var value: String,
    var counter: Int = 0,
) : Entity, Mergeable<TestEntity> {

    override fun id(): String = _id

    override fun merge(other: TestEntity) {
        value = other.value
        counter = other.counter
    }

    fun incrementCounter() {
        counter++
    }
}
