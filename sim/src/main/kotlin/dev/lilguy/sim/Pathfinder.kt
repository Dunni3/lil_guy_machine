package dev.lilguy.sim

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A* on an 8-connected grid. Diagonal steps may not cut a corner: both orthogonal neighbours
 * must be passable too. Ties break in insertion order, so the same query always gives the same
 * path.
 */
object Pathfinder {
    data class Path(val cells: List<Cell>, val cost: Float)

    private val SQRT2 = sqrt(2f)
    private val STEPS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)

    /** Cells from (excluding) [start] to (including) [goal], or null if [goal] can't be reached. */
    fun find(width: Int, height: Int, start: Cell, goal: Cell, passable: (Int, Int) -> Boolean): Path? {
        if (start == goal) return Path(emptyList(), 0f)
        if (!passable(goal.x, goal.y)) return null
        val n = width * height
        fun index(x: Int, y: Int) = y * width + x
        val g = FloatArray(n) { Float.POSITIVE_INFINITY }
        val parent = IntArray(n) { -1 }
        val closed = BooleanArray(n)
        var order = 0L
        val open = PriorityQueue<Node>(compareBy<Node> { it.f }.thenBy { it.order })

        g[index(start.x, start.y)] = 0f
        open.add(Node(start.x, start.y, heuristic(start.x, start.y, goal), order++))
        while (open.isNotEmpty()) {
            val node = open.poll()
            val i = index(node.x, node.y)
            if (closed[i]) continue
            closed[i] = true
            if (node.x == goal.x && node.y == goal.y) return Path(unwind(parent, i, width, start), g[i])
            for ((dx, dy) in STEPS) {
                val nx = node.x + dx
                val ny = node.y + dy
                if (nx !in 0 until width || ny !in 0 until height || !passable(nx, ny)) continue
                if (dx != 0 && dy != 0 && (!passable(node.x + dx, node.y) || !passable(node.x, node.y + dy))) continue
                val j = index(nx, ny)
                val cost = g[i] + if (dx != 0 && dy != 0) SQRT2 else 1f
                if (cost < g[j]) {
                    g[j] = cost
                    parent[j] = i
                    open.add(Node(nx, ny, cost + heuristic(nx, ny, goal), order++))
                }
            }
        }
        return null
    }

    /** Octile distance: exact on an empty 8-connected grid, so it never overestimates. */
    private fun heuristic(x: Int, y: Int, goal: Cell): Float {
        val dx = abs(x - goal.x)
        val dy = abs(y - goal.y)
        return max(dx, dy) + (SQRT2 - 1f) * min(dx, dy)
    }

    private fun unwind(parent: IntArray, end: Int, width: Int, start: Cell): List<Cell> {
        val cells = ArrayList<Cell>()
        var i = end
        val startIndex = start.y * width + start.x
        while (i != startIndex) {
            cells += Cell(i % width, i / width)
            i = parent[i]
        }
        return cells.asReversed()
    }

    private class Node(val x: Int, val y: Int, val f: Float, val order: Long)
}
