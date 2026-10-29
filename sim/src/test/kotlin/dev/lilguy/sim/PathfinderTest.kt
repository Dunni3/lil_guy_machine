package dev.lilguy.sim

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathfinderTest {
    /** Parses a grid where '#' blocks and anything else is open. */
    private class Grid(vararg rows: String) {
        val width = rows[0].length
        val height = rows.size
        private val cells = rows.toList()
        fun open(x: Int, y: Int) = cells[y][x] != '#'
    }

    @Test
    fun `straight line on an empty grid`() {
        val grid = Grid(".....")
        val path = assertNotNull(Pathfinder.find(grid.width, grid.height, Cell(0, 0), Cell(4, 0), grid::open))
        assertEquals((1..4).map { Cell(it, 0) }, path.cells)
        assertEquals(4f, path.cost)
    }

    @Test
    fun `routes around a wall and every step is adjacent and open`() {
        val grid = Grid(
            ".....",
            ".###.",
            ".#...",
            ".#.#.",
            "...#.",
        )
        val start = Cell(2, 2)
        val path = assertNotNull(Pathfinder.find(grid.width, grid.height, start, Cell(0, 0), grid::open))
        var prev = start
        for (cell in path.cells) {
            assertTrue(grid.open(cell.x, cell.y), "path enters a wall at $cell")
            assertEquals(1, max(abs(cell.x - prev.x), abs(cell.y - prev.y)), "jump from $prev to $cell")
            prev = cell
        }
        assertEquals(Cell(0, 0), path.cells.last())
    }

    @Test
    fun `diagonals never cut corners`() {
        val grid = Grid(
            ".#",
            "..",
        )
        val path = assertNotNull(Pathfinder.find(grid.width, grid.height, Cell(0, 0), Cell(1, 1), grid::open))
        assertEquals(listOf(Cell(0, 1), Cell(1, 1)), path.cells)
    }

    @Test
    fun `diagonal is preferred when the corner is clear`() {
        val grid = Grid("..", "..")
        val path = assertNotNull(Pathfinder.find(grid.width, grid.height, Cell(0, 0), Cell(1, 1), grid::open))
        assertEquals(listOf(Cell(1, 1)), path.cells)
    }

    @Test
    fun `unreachable and blocked goals return null`() {
        val grid = Grid(
            "..#..",
            "..#..",
        )
        assertNull(Pathfinder.find(grid.width, grid.height, Cell(0, 0), Cell(4, 0), grid::open))
        assertNull(Pathfinder.find(grid.width, grid.height, Cell(0, 0), Cell(2, 0), grid::open))
    }

    @Test
    fun `start equals goal is an empty path`() {
        val grid = Grid("...")
        assertEquals(emptyList(), Pathfinder.find(grid.width, grid.height, Cell(1, 0), Cell(1, 0), grid::open)?.cells)
    }
}
