package de.wandern.app.model

object WaypointOrdering {
    fun <T> move(items: MutableList<T>, fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) {
            return false
        }
        val item = items.removeAt(fromIndex)
        items.add(toIndex, item)
        return true
    }
}
