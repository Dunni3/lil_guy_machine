package dev.lilguy.sim

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Where his things stand and where they could go, by one set of rules for the `put` intent
 * ([Intent.Put]), his code's `world.move` and `world.spots`. A spot is a slot on the map: the
 * room's, a built room's, or the land's (`world.yaml` `slots`). It is free when nothing in the
 * content stands in it: a slot kept for a thing he doesn't own yet isn't free, and the land's free
 * spots are the slots no item names. Fixtures stay where they are, a thing in a wall goes only in
 * a wall (so nothing goes outdoors that hangs), and a spot holding another thing of his swaps the two.
 *
 * Made per question from the world's current [content] and [layout]; [owns] says whether he has a thing.
 */
class Spots(private val content: BedroomContent, private val layout: Layout, private val owns: (String) -> Boolean) {
    private val standing: Map<String, ItemSpec> = content.items.mapNotNull { s -> s.slot?.let { it to s } }.toMap()

    fun isWall(spot: String) = spot in content.room.wallSlots

    /** The thing whose spot [spot] is, owned or not. */
    fun holder(spot: String): ItemSpec? = standing[spot]

    /** Spots nothing stands in, in map order (the rooms' first). */
    fun free(): List<String> = layout.slotCells.keys.filter { it !in standing }

    /** What he could put somewhere else: things he owns that stand in a spot on the map, fixtures aside. */
    fun movable(): List<ItemSpec> = content.items.filter { s -> s.group != FIXTURE && s.slot in layout.slotCells && owns(s.id) }

    /** Why [id] can't go to [spot], or null when it can. */
    fun lock(id: String, spot: String): String? {
        val s = content.item(id) ?: return "there's no thing called '$id'"
        if (!owns(id)) return "you don't have the ${s.name}"
        if (s.group == FIXTURE) return "the ${s.name} is part of the house or the land, and stays where it is"
        val from = s.slot?.takeIf { it in layout.slotCells } ?: return "the ${s.name} doesn't stand anywhere; only furniture and decor can be put somewhere else"
        if (spot == from) return "the ${s.name} is already at $spot"
        if (spot !in layout.slotCells) return "there's no spot called '$spot'"
        if (isWall(spot) != isWall(from)) return "the ${s.name} goes ${if (isWall(from)) "in a wall" else "on the ground"}, and $spot is ${if (isWall(spot)) "in a wall" else "on the ground"}"
        val other = standing[spot]
        if (other != null && (!owns(other.id) || other.group == FIXTURE)) return "$spot is kept for the ${other.name}"
        return null
    }

    /** The free spots, by the place they're in: rooms first, then the land in the content's order. Places with none are left out. */
    fun freeByPlace(): List<FreeSpots> {
        val free = free()
        return layout.places.mapNotNull { p ->
            val here = free.filter { layout.placeOf(layout.slotCells.getValue(it)) == p }
            if (here.isEmpty()) return@mapNotNull null
            FreeSpots(p.name, here.filter { !isWall(it) }, here.filter(::isWall).ifEmpty { null })
        }
    }

    /** What models deciding are shown, or null when he has nothing to move or nowhere to put it. */
    fun options(): PutOptions? {
        val things = movable()
        val spots = freeByPlace()
        if (things.isEmpty() || spots.isEmpty()) return null
        return PutOptions(things.filter { !isWall(it.slot!!) }.map { it.id }, things.filter { isWall(it.slot!!) }.map { it.id }.ifEmpty { null }, spots)
    }

    companion object {
        const val FIXTURE = "fixture"
    }
}

/**
 * What he could put where ([Intent.Put]): the things of his on the ground ([things]) and in walls
 * ([wall]), and the free spots by place. A spot holding another thing of his works too (they swap),
 * but only the free ones are listed, to keep the prompt short.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PutOptions(
    val things: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val wall: List<String>? = null,
    val spots: List<FreeSpots>,
)

/** The free spots in one place (its name): on the ground, and in its walls. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FreeSpots(
    val place: String,
    val floor: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val wall: List<String>? = null,
)
