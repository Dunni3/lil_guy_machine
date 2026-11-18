package dev.lilguy.parts

/**
 * Decor he invented at the workbench in live runs, now sold in the shop (items.yaml says which run
 * and why). Each is the look he built, shape for shape, so it's his design rather than a redrawing;
 * the clay fields (taper, cut, hole, arc, glow, opacity) carry over, and a wall thing is lifted and
 * flattened against its wall as the web room drew it while it was an invention. Same conventions as
 * [DecorParts]: floor things on the bottom centre of their tile facing +Z, [ON_WALL] things with
 * their back at z = 0.
 */
object InventedParts {
    /** The ones that hang in a wall tile. */
    val ON_WALL = setOf("wooden_shelf", "amber_wall_lamp", "woven_wall_tapestry")

    /** Everything [invented] knows, for the export. */
    val IDS = listOf(
        "moss_terrarium", "tiny_wooden_stool", "pinecone_ornament", "moss_bowl", "floor_pillow", "moss_lamp",
        "fern_pot", "wooden_shelf", "sunroom_lamp", "acorn_lamp", "amber_floor_lamp", "amber_wall_lamp",
        "woven_wall_tapestry",
    )

    /** Adds invented decor [id]'s parts; false for ids that aren't one of his. */
    fun PartList.invented(id: String): Boolean {
        when (id) {
            "moss_terrarium" -> mossTerrarium()
            "tiny_wooden_stool" -> tinyWoodenStool()
            "pinecone_ornament" -> pineconeOrnament()
            "moss_bowl" -> mossBowl()
            "floor_pillow" -> floorPillow()
            "moss_lamp" -> mossLamp()
            "fern_pot" -> fernPot()
            "wooden_shelf" -> woodenShelf()
            "sunroom_lamp" -> sunroomLamp()
            "acorn_lamp" -> acornLamp()
            "amber_floor_lamp" -> amberFloorLamp()
            "amber_wall_lamp" -> amberWallLamp()
            "woven_wall_tapestry" -> wovenWallTapestry()
            else -> return false
        }
        return true
    }

    /** One shape of a look, with the clay fields a look may set. */
    private fun PartList.look(
        shape: Part.Shape, color: String, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float,
        rotX: Float = 0f, rotY: Float = 0f, rotZ: Float = 0f,
        taper: Float = 1f, cut: Float = 1f, hole: Float = 0f, arc: Float = 360f, glow: Float = 0f, opacity: Float = 1f,
    ) = part(Part(shape, w, h, d, x, y, z, rotX, rotY, rotZ, color, taper = taper, cut = cut, hole = hole, arc = arc, glow = glow, opacity = opacity))

    /** His moss terrarium, shape for shape as he built it. */
    private fun PartList.mossTerrarium() {
        look(Part.Shape.Cylinder, "#3a5a40", 0f, 0f, 0f, 0.3f, 0.05f, 0.3f) // base
        look(Part.Shape.Cylinder, "#e9f5ed", 0f, 0.05f, 0f, 0.26f, 0.3f, 0.26f, taper = 0.9f, opacity = 0.4f) // glass
        look(Part.Shape.Cylinder, "#4a3319", 0f, 0.05f, 0f, 0.24f, 0.06f, 0.24f) // soil
        look(Part.Shape.Sphere, "#588157", -0.05f, 0.11f, 0.05f, 0.15f, 0.08f, 0.15f, cut = 0.5f) // moss
        look(Part.Shape.Sphere, "#a3b18a", 0.05f, 0.11f, -0.05f, 0.12f, 0.06f, 0.12f, cut = 0.5f) // moss
        look(Part.Shape.Sphere, "#99d98c", 0.02f, 0.17f, 0.02f, 0.06f, 0.06f, 0.06f, cut = 0.5f, glow = 1f) // mushroom
        look(Part.Shape.Cylinder, "#3a5a40", 0f, 0.35f, 0f, 0.28f, 0.04f, 0.28f) // lid
        look(Part.Shape.Cylinder, "#582f0e", 0f, 0f, 0f, 0.34f, 0.08f, 0.34f) // stand
    }

    /** His tiny wooden stool, shape for shape as he built it. */
    private fun PartList.tinyWoodenStool() {
        look(Part.Shape.Cylinder, "#a0522d", 0f, 0f, 0f, 0.4f, 0.35f, 0.4f)
        look(Part.Shape.Box, "#deb887", 0f, 0.35f, 0f, 0.45f, 0.05f, 0.45f)
    }

    /** His pinecone ornament, shape for shape as he built it. */
    private fun PartList.pineconeOrnament() {
        look(Part.Shape.Sphere, "#8b5a2b", 0f, 0f, 0f, 0.4f, 0.6f, 0.4f)
    }

    /** His moss bowl, shape for shape as he built it. */
    private fun PartList.mossBowl() {
        look(Part.Shape.Cylinder, "#8b5a2b", 0f, 0f, 0f, 0.3f, 0.3f, 0.3f, taper = 0.8f, hole = 0.8f) // pot
        look(Part.Shape.Cylinder, "#3d2817", 0f, 0.3f, 0f, 0.26f, 0.08f, 0.26f) // soil
        look(Part.Shape.Sphere, "#4a7c59", 0f, 0.38f, 0f, 0.2f, 0.12f, 0.2f, cut = 0.5f) // moss
        look(Part.Shape.Sphere, "#5c8a6f", 0.06f, 0.4f, 0.04f, 0.12f, 0.08f, 0.12f, cut = 0.5f) // moss
        look(Part.Shape.Sphere, "#3b6e50", -0.05f, 0.39f, -0.04f, 0.1f, 0.06f, 0.1f, cut = 0.5f) // moss
    }

    /** His floor pillow, shape for shape as he built it. */
    private fun PartList.floorPillow() {
        look(Part.Shape.Cylinder, "#c8845c", 0f, 0f, 0f, 0.4f, 0.3f, 0.4f) // cushion
        look(Part.Shape.Sphere, "#e6b89c", 0f, 0.3f, 0f, 0.36f, 0.15f, 0.36f, cut = 0.5f) // top
    }

    /** His moss lamp, shape for shape as he built it. */
    private fun PartList.mossLamp() {
        look(Part.Shape.Cylinder, "#4a3f38", 0f, 0f, 0f, 0.3f, 0.05f, 0.3f) // base
        look(Part.Shape.Cylinder, "#e6efec", 0f, 0.05f, 0f, 0.26f, 0.35f, 0.26f, hole = 0.85f, opacity = 0.4f) // bowl
        look(Part.Shape.Sphere, "#3d2817", 0f, 0.25f, 0f, 0.24f, 0.15f, 0.24f, cut = 0.5f) // soil
        look(Part.Shape.Sphere, "#4a6b41", -0.04f, 0.3f, 0.02f, 0.12f, 0.08f, 0.12f, cut = 0.5f) // moss
        look(Part.Shape.Sphere, "#5c8251", 0.04f, 0.3f, -0.03f, 0.1f, 0.06f, 0.1f, cut = 0.5f) // moss
        look(Part.Shape.Capsule, "#8bf2d0", 0f, 0.3f, 0f, 0.08f, 0.15f, 0.08f, cut = 0.5f, glow = 0.8f, opacity = 0.9f) // crystal
    }

    /** His fern pot, shape for shape as he built it. */
    private fun PartList.fernPot() {
        look(Part.Shape.Cylinder, "#8b5a2b", 0f, 0f, 0f, 0.3f, 0.2f, 0.3f, taper = 0.8f, hole = 0.8f) // pot
        look(Part.Shape.Cylinder, "#3d2314", 0f, 0.16f, 0f, 0.28f, 0.04f, 0.28f) // soil
        look(Part.Shape.Capsule, "#4a7c59", 0f, 0.2f, 0.05f, 0.08f, 0.2f, 0.1f, rotX = 20f) // leaf
        look(Part.Shape.Capsule, "#3a6b48", -0.05f, 0.18f, -0.03f, 0.08f, 0.25f, 0.1f, rotX = -20f, rotZ = 20f) // leaf
        look(Part.Shape.Capsule, "#558d63", 0.04f, 0.18f, -0.03f, 0.08f, 0.22f, 0.1f, rotY = 45f, rotZ = -20f) // leaf
    }

    /** His wooden shelf, shape for shape as he built it. */
    private fun PartList.woodenShelf() {
        look(Part.Shape.Box, "#8b5a2b", 0f, 1.4f, 0.01f, 0.5f, 0.04f, 0.2f) // shelf
        look(Part.Shape.Cylinder, "#e6efec", 0f, 1.44f, 0.0225f, 0.12f, 0.1f, 0.12f, taper = 0.8f, hole = 0.8f) // pot
        look(Part.Shape.Sphere, "#2e8b57", 0f, 1.5f, 0.0225f, 0.1f, 0.08f, 0.1f, cut = 0.5f) // plant
        look(Part.Shape.Box, "#5c4033", -0.15f, 1.28f, 0.015f, 0.04f, 0.12f, 0.04f) // bracket
        look(Part.Shape.Box, "#5c4033", 0.15f, 1.28f, 0.015f, 0.04f, 0.12f, 0.04f) // bracket
    }

    /** His sunroom lamp, shape for shape as he built it. */
    private fun PartList.sunroomLamp() {
        look(Part.Shape.Cylinder, "#5c4033", 0f, 0f, 0f, 0.3f, 0.05f, 0.3f) // base
        look(Part.Shape.Cylinder, "#8b5a2b", 0f, 0.05f, 0f, 0.08f, 0.8f, 0.08f) // pole
        look(Part.Shape.Cylinder, "#f5deb3", 0f, 0.85f, 0f, 0.35f, 0.3f, 0.35f, taper = 1.8f, hole = 0.1f) // shade
        look(Part.Shape.Sphere, "#ffeb3b", 0f, 0.95f, 0f, 0.1f, 0.1f, 0.1f, glow = 1f) // bulb
    }

    /** His acorn lamp, shape for shape as he built it. */
    private fun PartList.acornLamp() {
        look(Part.Shape.Cylinder, "#6b4423", 0f, 0f, 0f, 0.3f, 0.15f, 0.3f, taper = 0.8f) // cup
        look(Part.Shape.Sphere, "#3d2817", 0f, 0.15f, 0f, 0.38f, 0.28f, 0.38f, cut = 0.5f) // cap
        look(Part.Shape.Sphere, "#ffea88", 0f, 0.18f, 0f, 0.2f, 0.2f, 0.2f, glow = 1f, opacity = 0.9f) // glow
    }

    /** His amber floor lamp, shape for shape as he built it. */
    private fun PartList.amberFloorLamp() {
        look(Part.Shape.Cylinder, "#5c4033", 0f, 0f, 0f, 0.4f, 0.05f, 0.4f) // base
        look(Part.Shape.Cylinder, "#8b5a2b", 0f, 0.05f, 0f, 0.1f, 0.7f, 0.1f) // stem
        look(Part.Shape.Cylinder, "#ffcc66", 0f, 0.75f, 0f, 0.35f, 0.3f, 0.35f, taper = 1.8f, hole = 0.1f, opacity = 0.9f) // shade
        look(Part.Shape.Sphere, "#ffaa00", 0f, 0.85f, 0f, 0.12f, 0.12f, 0.12f, glow = 1f) // bulb
    }

    /** His amber wall lamp, shape for shape as he built it. */
    private fun PartList.amberWallLamp() {
        look(Part.Shape.Cylinder, "#5c4033", 0f, 1f, 0.135f, 0.3f, 0.05f, 0.25f) // base
        look(Part.Shape.Cylinder, "#d4af37", 0f, 1.05f, 0.135f, 0.08f, 0.4f, 0.08f) // stem
        look(Part.Shape.Sphere, "#ffea75", 0f, 1.45f, 0.135f, 0.12f, 0.12f, 0.12f, glow = 1f) // bulb
        look(Part.Shape.Cylinder, "#e6ccb2", 0f, 1.4f, 0.135f, 0.35f, 0.25f, 0.25f, taper = 1.8f, hole = 0.1f, opacity = 0.9f) // shade
    }

    /** His woven wall tapestry, shape for shape as he built it. */
    private fun PartList.wovenWallTapestry() {
        look(Part.Shape.Cylinder, "#6b4423", 0f, 2.2f, 0.01f, 0.6f, 0.04f, 0.04f, rotY = 90f) // rod
        look(Part.Shape.Box, "#d4bda8", 0f, 1.7f, 0.01f, 0.5f, 0.5f, 0.02f) // tapestry
        look(Part.Shape.Box, "#a27054", 0f, 1.9f, 0.0125f, 0.45f, 0.1f, 0.025f) // pattern
        look(Part.Shape.Box, "#705244", 0f, 1.78f, 0.0125f, 0.45f, 0.08f, 0.025f) // pattern
        look(Part.Shape.Box, "#f0e6dc", -0.2f, 1.6f, 0.0125f, 0.05f, 0.1f, 0.02f) // fringe
        look(Part.Shape.Box, "#f0e6dc", -0.1f, 1.6f, 0.0125f, 0.05f, 0.1f, 0.02f) // fringe
        look(Part.Shape.Box, "#f0e6dc", 0f, 1.6f, 0.0125f, 0.05f, 0.1f, 0.02f) // fringe
        look(Part.Shape.Box, "#f0e6dc", 0.1f, 1.6f, 0.0125f, 0.05f, 0.1f, 0.02f) // fringe
        look(Part.Shape.Box, "#f0e6dc", 0.2f, 1.6f, 0.0125f, 0.05f, 0.1f, 0.02f) // fringe
    }
}
