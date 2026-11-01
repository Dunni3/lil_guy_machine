package dev.lilguy.app

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decor from the shop, as part lists in [RoomModels]' conventions: floor things have their pivot at
 * the bottom centre of their tile and face +Z; [ON_WALL] things have their back at z = 0, x along
 * the wall. Each is the room's pastels with rounded edges, small lumps and ink, like the rest.
 */
object DecorModels {
    /** Decor that hangs in a wall tile rather than standing on the floor. */
    val ON_WALL = setOf("string_lights", "window_succulents", "moon_mobile", "round_mirror", "cork_board", "rainbow", "cloud_light", "bunting")

    /** Adds decor [id]'s parts; false for ids that aren't decor. */
    fun PartList.decor(id: String): Boolean {
        when (id) {
            "lava_lamp" -> lavaLamp()
            "sunflowers" -> sunflowers()
            "mushroom_lamp" -> mushroomLamp()
            "beanbag" -> beanbag()
            "fishbowl" -> fishbowl()
            "teddy_bear" -> teddyBear()
            "cactus" -> cactus()
            "string_lights" -> stringLights()
            "window_succulents" -> windowSucculents()
            "moon_mobile" -> moonMobile()
            "round_mirror" -> roundMirror()
            "cork_board" -> corkBoard()
            "rainbow" -> rainbow()
            "cloud_light" -> cloudLight()
            "bunting" -> bunting()
            else -> return false
        }
        return true
    }

    // --- on the floor ----------------------------------------------------------------------------

    /** A bedside table against the back wall with a lava lamp on it, blobs pressing at the glass. */
    private fun PartList.lavaLamp() {
        val z = -0.25f
        box(WOOD_LIGHT, 0f, 0f, z, 0.42f, 0.42f, 0.38f, round = 0.03f)
        box(WOOD, 0f, 0.17f, z + 0.19f, 0.34f, 0.15f, 0.02f, round = 0.01f, outline = 0.007f)
        sphere(KNOB, 0f, 0.225f, z + 0.2f, 0.04f, 0.04f, 0.03f)
        cylinder(LILAC_METAL, 0f, 0.42f, z, 0.17f, 0.11f, 0.17f, taper = 0.62f, round = 0.015f)
        cylinder(LAVA_GLASS, 0f, 0.53f, z, 0.095f, 0.15f, 0.095f, taper = 1.35f, round = 0.01f, lump = 0f)
        cylinder(LAVA_GLASS, 0f, 0.68f, z, 0.128f, 0.17f, 0.128f, taper = 0.5f, round = 0.01f, lump = 0f)
        cylinder(LILAC_METAL, 0f, 0.84f, z, 0.07f, 0.06f, 0.07f, taper = 0.65f, round = 0.01f)
        sphere(LAVA_BLOB, -0.005f, 0.56f, z + 0.03f, 0.075f, 0.07f, 0.06f, outline = 0.004f, lump = 0.004f)
        sphere(LAVA_BLOB, 0.012f, 0.69f, z + 0.035f, 0.05f, 0.06f, 0.05f, outline = 0.004f, lump = 0.004f)
        sphere(LAVA_BLOB, -0.01f, 0.78f, z + 0.02f, 0.035f, 0.035f, 0.03f, outline = 0.004f)
    }

    /** A jug of three sunflowers against the back wall. */
    private fun PartList.sunflowers() {
        val z = -0.2f
        cylinder(TEAL, 0f, 0f, z, 0.21f, 0.26f, 0.21f, taper = 0.7f, round = 0.03f)
        cylinder(TEAL_LIGHT, 0f, 0.24f, z, 0.16f, 0.045f, 0.16f, round = 0.015f)
        capsule(STEM, 0f, 0.24f, z, 0.026f, 0.48f, 0.026f, outline = 0.006f)
        capsule(STEM, -0.025f, 0.24f, z, 0.024f, 0.4f, 0.024f, rotZ = 15f, outline = 0.006f)
        capsule(STEM, 0.025f, 0.24f, z, 0.024f, 0.35f, 0.024f, rotZ = -17f, outline = 0.006f)
        sphere(LEAF, -0.07f, 0.4f, z, 0.11f, 0.04f, 0.06f, outline = 0.006f)
        sphere(LEAF, 0.07f, 0.47f, z, 0.1f, 0.035f, 0.06f, outline = 0.006f)
        flower(SUN_PETAL, SEED, 0f, 0.72f, z + 0.03f, 0.075f, 0.065f)
        flower(SUN_PETAL, SEED, -0.128f, 0.63f, z + 0.02f, 0.06f, 0.055f)
        flower(SUN_PETAL, SEED, 0.127f, 0.58f, z + 0.025f, 0.058f, 0.052f)
    }

    /** A toadstool lamp as tall as the desk: cream stem, rosy cap with white spots and a frilled brim. */
    private fun PartList.mushroomLamp() {
        val z = -0.12f
        lump = 0.006f
        cylinder(MUSHROOM_STEM, 0f, 0f, z, 0.32f, 0.07f, 0.32f, round = 0.03f)
        cylinder(MUSHROOM_STEM, 0f, 0.05f, z, 0.2f, 0.6f, 0.2f, taper = 0.72f, round = 0.04f)
        cylinder(MUSHROOM_GILL, 0f, 0.6f, z, 0.5f, 0.035f, 0.5f, round = 0.015f, lump = 0.004f)
        sphere(MUSHROOM_CAP, 0f, 0.56f, z, 0.64f, 0.44f, 0.64f, lump = 0.01f)
        val spots = listOf(floatArrayOf(0f, 0.45f, 0.9f), floatArrayOf(-0.62f, 0.35f, 0.7f), floatArrayOf(0.66f, 0.3f, 0.68f), floatArrayOf(-0.25f, 0.92f, 0.3f), floatArrayOf(0.35f, 0.82f, 0.2f), floatArrayOf(-0.85f, 0.25f, 0.1f), floatArrayOf(0.9f, 0.2f, 0.05f))
        spots.forEachIndexed { i, (u, v, w) -> onEllipsoid(SPOT, 0f, 0.78f, z, 0.32f, 0.22f, 0.32f, u, v, w, if (i % 2 == 0) 0.1f else 0.075f) }
    }

    /** A slouched lavender beanbag in the corner, its top pushed in. */
    private fun PartList.beanbag() {
        sphere(BEANBAG, 0.04f, 0f, -0.04f, 0.74f, 0.5f, 0.68f, lump = 0.022f)
        sphere(BEANBAG_TOP, 0.03f, 0.25f, 0f, 0.5f, 0.24f, 0.44f, lump = 0.012f)
        box(BEANBAG_TAG, 0.32f, 0.12f, 0.18f, 0.06f, 0.08f, 0.02f, rotY = 50f, round = 0.008f, outline = 0.005f)
    }

    /** A goldfish in a round bowl, a sprig of weed poking out of the top, on a little round table. */
    private fun PartList.fishbowl() {
        val x = -0.12f
        cylinder(WOOD_DARK, x, 0f, 0f, 0.28f, 0.04f, 0.28f, round = 0.015f)
        cylinder(WOOD_DARK, x, 0.03f, 0f, 0.07f, 0.46f, 0.07f, round = 0.02f)
        cylinder(WOOD, x, 0.48f, 0f, 0.38f, 0.045f, 0.38f, round = 0.018f)
        sphere(WATER, x, 0.52f, 0f, 0.36f, 0.32f, 0.36f, lump = 0f)
        cylinder(GLASS_RIM, x, 0.81f, 0f, 0.22f, 0.035f, 0.22f, round = 0.012f)
        capsule(LEAF_DARK, x - 0.05f, 0.62f, -0.04f, 0.03f, 0.3f, 0.03f, rotZ = 10f, outline = 0.006f)
        capsule(LEAF, x - 0.03f, 0.62f, -0.03f, 0.025f, 0.26f, 0.025f, rotZ = -14f, outline = 0.006f)
        for ((i, px) in listOf(-0.07f, -0.02f, 0.04f, 0.08f).withIndex()) {
            sphere(PEBBLES[i % PEBBLES.size], x + px, 0.545f, 0.115f - 0.01f * i, 0.05f, 0.035f, 0.04f, outline = 0.004f)
        }
        // The goldfish, at the glass facing left, with a fan tail and one eye.
        sphere(GOLDFISH_FIN, x + 0.085f, 0.63f, 0.155f, 0.065f, 0.09f, 0.03f, outline = 0.005f)
        sphere(GOLDFISH, x + 0.01f, 0.635f, 0.165f, 0.135f, 0.085f, 0.05f, outline = 0.006f)
        sphere(INK, x - 0.03f, 0.68f, 0.19f, 0.017f, 0.019f, 0.008f, outline = 0f)
        sphere(BUBBLE, x - 0.06f, 0.71f, 0.16f, 0.022f, 0.022f, 0.012f, outline = 0.003f)
        sphere(BUBBLE, x - 0.075f, 0.76f, 0.15f, 0.016f, 0.016f, 0.01f, outline = 0.003f)
    }

    /** A teddy bear sitting in the corner with his legs out and a bow on, facing the room. */
    private fun PartList.teddyBear() {
        val x = -0.08f
        val z = -0.05f
        lump = 0.003f
        capsule(TEDDY, x - 0.075f, 0.045f, z + 0.02f, 0.09f, 0.17f, 0.09f, rotX = 90f)
        capsule(TEDDY, x + 0.075f, 0.045f, z + 0.02f, 0.09f, 0.17f, 0.09f, rotX = 90f)
        sphere(TEDDY_LIGHT, x - 0.075f, 0.045f, z + 0.18f, 0.07f, 0.075f, 0.03f, outline = 0.005f)
        sphere(TEDDY_LIGHT, x + 0.075f, 0.045f, z + 0.18f, 0.07f, 0.075f, 0.03f, outline = 0.005f)
        sphere(TEDDY, x, 0f, z, 0.27f, 0.3f, 0.23f)
        sphere(TEDDY_LIGHT, x, 0.06f, z + 0.075f, 0.15f, 0.16f, 0.09f, outline = 0.006f)
        capsule(TEDDY, x - 0.12f, 0.06f, z + 0.03f, 0.075f, 0.16f, 0.075f, rotZ = 28f)
        capsule(TEDDY, x + 0.12f, 0.06f, z + 0.03f, 0.075f, 0.16f, 0.075f, rotZ = -28f)
        sphere(TEDDY, x, 0.25f, z + 0.01f, 0.23f, 0.21f, 0.2f)
        for (side in listOf(-1f, 1f)) {
            sphere(TEDDY, x + side * 0.085f, 0.41f, z, 0.08f, 0.075f, 0.045f)
            sphere(TEDDY_LIGHT, x + side * 0.085f, 0.42f, z + 0.018f, 0.045f, 0.045f, 0.02f, outline = 0f)
            sphere(INK, x + side * 0.048f, 0.355f, z + 0.1f, 0.022f, 0.027f, 0.012f, outline = 0f)
        }
        sphere(TEDDY_LIGHT, x, 0.285f, z + 0.09f, 0.095f, 0.07f, 0.055f, outline = 0.005f)
        sphere(INK, x, 0.32f, z + 0.118f, 0.032f, 0.022f, 0.016f, outline = 0f)
        sphere(BOW, x - 0.04f, 0.205f, z + 0.1f, 0.06f, 0.05f, 0.03f, outline = 0.005f)
        sphere(BOW, x + 0.04f, 0.205f, z + 0.1f, 0.06f, 0.05f, 0.03f, outline = 0.005f)
        sphere(BOW, x, 0.212f, z + 0.11f, 0.03f, 0.035f, 0.025f, outline = 0.005f)
    }

    /** A cactus with two arms and a pink flower, in a blue pot. */
    private fun PartList.cactus() {
        val x = 0.08f
        cylinder(POT_BLUE, x, 0f, 0f, 0.24f, 0.2f, 0.24f, taper = 1.2f, round = 0.02f)
        cylinder(POT_BLUE_RIM, x, 0.18f, 0f, 0.31f, 0.055f, 0.31f, round = 0.02f)
        cylinder(SOIL, x, 0.225f, 0f, 0.25f, 0.02f, 0.25f, round = 0.008f, outline = 0.006f)
        lump = 0.006f
        capsule(CACTUS, x, 0.2f, 0f, 0.15f, 0.52f, 0.15f)
        capsule(CACTUS, x - 0.15f, 0.38f, 0f, 0.075f, 0.17f, 0.075f, rotZ = -90f)
        capsule(CACTUS, x - 0.12f, 0.35f, 0f, 0.075f, 0.2f, 0.075f)
        capsule(CACTUS, x, 0.3f, 0f, 0.07f, 0.15f, 0.07f, rotZ = -90f)
        capsule(CACTUS, x + 0.115f, 0.28f, 0f, 0.07f, 0.16f, 0.07f)
        lump = 0f
        flower(CACTUS_FLOWER, SUN_PETAL, x, 0.73f, 0.01f, 0.03f, 0.035f)
        for ((px, py) in listOf(-0.03f to 0.4f, 0.035f to 0.5f, -0.02f to 0.6f, 0.03f to 0.3f)) {
            sphere(SPOT, x + px, py, 0.072f, 0.016f, 0.016f, 0.01f, outline = 0f)
        }
    }

    // --- in the wall -----------------------------------------------------------------------------

    /** Fairy lights strung along the top of the wall in two swags, the bulbs in every pastel. */
    private fun PartList.stringLights() {
        val y = 2.0f
        val z = 0.03f
        var k = 0
        for ((x0, x1) in listOf(-0.95f to 0.05f, 0.05f to 1.05f)) {
            val points = swag(WIRE, x0, x1, y, 0.16f, z, 9, 0.012f)
            for (p in points.drop(1).dropLast(1)) {
                box(WIRE, p.first, p.second - 0.03f, z, 0.022f, 0.03f, 0.022f, round = 0.006f, outline = 0f)
                sphere(BULBS[k++ % BULBS.size], p.first, p.second - 0.1f, z, 0.05f, 0.075f, 0.05f, outline = 0.005f)
            }
        }
        for (x in listOf(-0.95f, 0.05f, 1.05f)) sphere(KNOB, x, y - 0.02f, 0.015f, 0.04f, 0.04f, 0.03f, outline = 0.005f)
    }

    /** Three little pots on a plank along the windowsill: a rosette, a cactus and a trailing string of pearls. */
    private fun PartList.windowSucculents() {
        val cx = -0.1f
        val y = 1.0f
        val z = 0.08f
        box(WOOD_LIGHT, cx, y, z, 0.92f, 0.03f, 0.16f, round = 0.01f)
        lump = 0.003f
        // A rosette in a pink pot.
        val a = cx - 0.3f
        cylinder(POT_PINK, a, y + 0.03f, z, 0.13f, 0.11f, 0.13f, taper = 1.18f, round = 0.015f)
        sphere(LEAF_DARK, a, y + 0.13f, z, 0.16f, 0.07f, 0.14f, outline = 0.006f)
        sphere(LEAF, a, y + 0.16f, z + 0.005f, 0.11f, 0.06f, 0.1f, outline = 0.006f)
        sphere(LEAF_LIGHT, a, y + 0.19f, z + 0.01f, 0.06f, 0.045f, 0.06f, outline = 0.006f)
        // A tiny cactus in a butter pot.
        cylinder(POT_BUTTER, cx, y + 0.03f, z, 0.12f, 0.1f, 0.12f, taper = 1.15f, round = 0.015f)
        capsule(CACTUS, cx, y + 0.11f, z, 0.07f, 0.17f, 0.07f, outline = 0.008f)
        sphere(CACTUS_FLOWER, cx, y + 0.265f, z, 0.04f, 0.03f, 0.04f, outline = 0.005f)
        // String of pearls spilling over the front of a blue pot.
        val b = cx + 0.3f
        cylinder(POT_BLUE, b, y + 0.03f, z, 0.13f, 0.11f, 0.13f, taper = 1.18f, round = 0.015f)
        sphere(LEAF, b, y + 0.12f, z, 0.14f, 0.06f, 0.13f, outline = 0.006f)
        for ((i, dx) in listOf(-0.045f, 0f, 0.05f).withIndex()) {
            val len = 3 + i % 2 * 2
            for (j in 0 until len) sphere(LEAF_LIGHT, b + dx + 0.008f * (j % 2), y + 0.12f - 0.035f * j, z + 0.075f + 0.004f * j, 0.03f, 0.03f, 0.03f, outline = 0.004f, lump = 0f)
        }
    }

    /**
     * A mobile on an arm out over the bed: a sleepy moon between two stars, hung at different
     * heights along a bar that runs out from the wall. They face along -X, which on the left wall is
     * toward the camera.
     */
    private fun PartList.moonMobile() {
        box(WOOD_DARK, 0f, 1.92f, 0.012f, 0.09f, 0.12f, 0.024f, round = 0.008f)
        box(WOOD_DARK, 0f, 1.97f, 0.18f, 0.03f, 0.03f, 0.34f, round = 0.01f)
        capsule(THREAD, 0f, 1.9f, 0.32f, 0.006f, 0.08f, 0.006f, outline = 0f)
        capsule(WOOD_LIGHT, 0f, 1.89f, 0.08f, 0.024f, 0.48f, 0.024f, rotX = 90f, outline = 0.006f)
        for ((z, drop) in listOf(0.12f to 0.24f, 0.48f to 0.3f)) {
            capsule(THREAD, 0f, 1.89f - drop, z, 0.006f, drop, 0.006f, outline = 0f)
            star(STAR, 0f, 1.89f - drop - 0.06f, z, 0.07f)
        }
        val my = 1.89f - 0.42f
        capsule(THREAD, 0f, my, 0.31f, 0.006f, 0.42f, 0.006f, outline = 0f)
        sphere(MOON, 0f, my - 0.19f, 0.31f, 0.035f, 0.19f, 0.19f, outline = 0.006f)
        for (side in listOf(-1f, 1f)) {
            capsule(INK, -0.019f, my - 0.08f, 0.31f + side * 0.04f - 0.016f, 0.008f, 0.032f, 0.008f, rotX = 90f, outline = 0f)
            sphere(BLUSH, -0.018f, my - 0.125f, 0.31f + side * 0.055f, 0.006f, 0.022f, 0.03f, outline = 0f)
        }
    }

    /** A round mirror in a scalloped pink frame, with a glint. */
    private fun PartList.roundMirror() {
        val y = 1.25f
        cylinder(FRAME_PINK, 0f, y, 0f, 0.5f, 0.04f, 0.5f, rotX = 90f, round = 0.012f, lump = 0.003f)
        for (k in 0 until 14) {
            val a = k * TAU / 14
            sphere(FRAME_PINK, 0.26f * cos(a), y + 0.26f * sin(a) - 0.04f, 0.02f, 0.08f, 0.08f, 0.04f, outline = 0.006f)
        }
        cylinder(MIRROR, 0f, y, 0.03f, 0.4f, 0.02f, 0.4f, rotX = 90f, round = 0.006f, outline = 0.006f)
        segment(SHINE, -0.13f, y - 0.03f, -0.04f, y + 0.08f, 0.055f, 0.022f)
        segment(SHINE, -0.08f, y - 0.09f, -0.04f, y - 0.04f, 0.055f, 0.016f)
    }

    /** A cork board of notes, a photo and a doodle, each held by a pin. */
    private fun PartList.corkBoard() {
        val y = 0.95f
        box(WOOD, 0f, y, 0.015f, 0.72f, 0.5f, 0.03f, round = 0.012f)
        box(CORK, 0f, y + 0.04f, 0.032f, 0.63f, 0.42f, 0.01f, round = 0.004f, outline = 0.005f)
        val notes = listOf(
            floatArrayOf(-0.19f, y + 0.24f, 0.15f, 0.13f, -6f),
            floatArrayOf(0.02f, y + 0.22f, 0.16f, 0.17f, 4f),
            floatArrayOf(0.2f, y + 0.08f, 0.14f, 0.13f, -3f),
            floatArrayOf(-0.16f, y + 0.07f, 0.17f, 0.13f, 5f),
        )
        notes.forEachIndexed { i, (x, ny, w, h, tilt) ->
            box(NOTES[i], x, ny, 0.04f, w, h, 0.004f, rotZ = tilt, round = 0.003f, outline = 0.004f)
            sphere(PINS[i % PINS.size], x, ny + h - 0.035f, 0.044f, 0.03f, 0.03f, 0.02f, outline = 0.004f)
        }
        // The photo on the tall white note: sky, a green hill and a sun.
        box(PHOTO_SKY, 0.02f, y + 0.26f, 0.043f, 0.12f, 0.1f, 0.003f, rotZ = 4f, round = 0.002f, outline = 0.003f)
        sphere(LEAF, 0.02f, y + 0.255f, 0.045f, 0.12f, 0.05f, 0.004f, outline = 0f)
        sphere(SUN_PETAL, 0.05f, y + 0.31f, 0.046f, 0.03f, 0.03f, 0.004f, outline = 0f)
        // Scribbled lines on two of the notes.
        for (j in 0..2) box(INK_SOFT, -0.19f, y + 0.18f - j * 0.025f, 0.043f, 0.09f, 0.006f, 0.002f, rotZ = -6f, round = 0.002f, outline = 0f)
        for (j in 0..1) box(INK_SOFT, 0.2f, y + 0.15f - j * 0.03f, 0.043f, 0.08f, 0.006f, 0.002f, rotZ = -3f, round = 0.002f, outline = 0f)
        sphere(HEART, -0.16f, y + 0.11f, 0.044f, 0.05f, 0.045f, 0.006f, outline = 0.003f)
    }

    /** A rainbow arch of five crayon bands, its feet in two clouds. */
    private fun PartList.rainbow() {
        val y = 1.08f
        BANDS.forEachIndexed { i, color -> arc(color, 0f, y, 0.02f + 0.004f * i, 0.34f - 0.045f * i, 0.05f, 9) }
        for (side in listOf(-1f, 1f)) {
            val cx = side * 0.25f
            sphere(CLOUD, cx - 0.06f, y - 0.07f, 0.05f, 0.12f, 0.1f, 0.06f, outline = 0.006f)
            sphere(CLOUD, cx + 0.05f, y - 0.07f, 0.05f, 0.12f, 0.1f, 0.06f, outline = 0.006f)
            sphere(CLOUD, cx, y - 0.04f, 0.06f, 0.13f, 0.12f, 0.06f, outline = 0.006f)
        }
    }

    /** A puffy cloud night-light with a sleeping face and rosy cheeks, its cord looping down the wall. */
    private fun PartList.cloudLight() {
        val y = 1.2f
        lump = 0.004f
        sphere(CLOUD, -0.15f, y, 0.07f, 0.22f, 0.19f, 0.14f)
        sphere(CLOUD, 0.15f, y, 0.07f, 0.22f, 0.18f, 0.14f)
        sphere(CLOUD, -0.05f, y + 0.04f, 0.085f, 0.24f, 0.24f, 0.17f)
        sphere(CLOUD, 0.07f, y + 0.03f, 0.085f, 0.22f, 0.22f, 0.16f)
        lump = 0f
        for (side in listOf(-1f, 1f)) {
            segment(INK, side * 0.065f - 0.022f, y + 0.13f, side * 0.065f + 0.022f, y + 0.13f, 0.172f, 0.012f)
            sphere(BLUSH, side * 0.11f, y + 0.08f, 0.165f, 0.045f, 0.028f, 0.01f, outline = 0f)
        }
        sphere(INK, 0f, y + 0.09f, 0.172f, 0.022f, 0.016f, 0.008f, outline = 0f)
        capsule(WIRE, 0.15f, y - 0.4f, 0.01f, 0.01f, 0.42f, 0.01f, outline = 0f)
        box(WALL_PLUG, 0.15f, y - 0.47f, 0.012f, 0.06f, 0.08f, 0.024f, round = 0.008f, outline = 0.005f)
    }

    /** A garland of pastel pennants along the wall, in two swags. */
    private fun PartList.bunting() {
        val y = 1.95f
        val z = 0.03f
        var k = 0
        for ((x0, x1) in listOf(-1.25f to -0.4f, -0.4f to 0.42f)) {
            val points = swag(WIRE, x0, x1, y, 0.13f, z, 6, 0.01f)
            for (p in points.drop(1).dropLast(1)) {
                cylinder(PENNANTS[k++ % PENNANTS.size], p.first, p.second + 0.005f, z + 0.008f, 0.12f, 0.17f, 0.012f, rotZ = 180f, taper = 0.06f, round = 0.004f, outline = 0.005f)
            }
        }
        for (x in listOf(-1.25f, -0.4f, 0.42f)) sphere(KNOB, x, y - 0.02f, 0.015f, 0.04f, 0.04f, 0.03f, outline = 0.005f)
    }

    // --- helpers -------------------------------------------------------------------------------

    /** A capsule from ([ax], [ay]) to ([bx], [by]) in the plane z = [z], [thick] across. */
    private fun PartList.segment(color: String, ax: Float, ay: Float, bx: Float, by: Float, z: Float, thick: Float) {
        val angle = atan2(by - ay, bx - ax) * RAD_TO_DEG
        capsule(color, ax, ay, z, thick, hypot(bx - ax, by - ay) + thick / 2, thick, rotZ = angle - 90f, outline = 0f)
    }

    /** A line hung from ([x0], [y]) to ([x1], [y]), [sag] lower in the middle, as [n] capsules; returns its n + 1 points. */
    private fun PartList.swag(color: String, x0: Float, x1: Float, y: Float, sag: Float, z: Float, n: Int, thick: Float): List<Pair<Float, Float>> {
        val points = (0..n).map { i ->
            val t = i.toFloat() / n
            (x0 + (x1 - x0) * t) to (y - sag * 4 * t * (1 - t))
        }
        points.zipWithNext { a, b -> segment(color, a.first, a.second, b.first, b.second, z, thick) }
        return points
    }

    /** A half circle of radius [r] centred on ([cx], [cy]), facing +Z, as [n] capsules [thick] across. */
    private fun PartList.arc(color: String, cx: Float, cy: Float, z: Float, r: Float, thick: Float, n: Int) {
        val points = (0..n).map { i -> (i * PI / n).let { a -> (cx + r * cos(a)) to (cy + r * sin(a)) } }
        points.zipWithNext { a, b -> segment(color, a.first, a.second, b.first, b.second, z, thick) }
    }

    /** A flower facing +Z: eight round petals [r] out from ([cx], [cy]) around a centre [size] across. */
    private fun PartList.flower(petal: String, centre: String, cx: Float, cy: Float, z: Float, r: Float, size: Float) {
        val p = r * 0.95f
        for (k in 0 until 8) {
            val a = k * TAU / 8
            sphere(petal, cx + r * 0.7f * cos(a), cy + r * 0.7f * sin(a) - p / 2, z, p, p, p * 0.35f, outline = 0.004f)
        }
        sphere(centre, cx, cy - size / 2, z + 0.01f, size, size, size * 0.45f, outline = 0.004f)
    }

    /** A five-pointed star [r] across its points, centred on ([cx], [cy], [cz]) and facing along X. */
    private fun PartList.star(color: String, cx: Float, cy: Float, cz: Float, r: Float) {
        for (k in 0 until 5) cylinder(color, cx, cy, cz, 0.018f, r, r * 0.75f, rotX = k * 72f, taper = 0.08f, round = 0.004f, outline = 0.005f)
        sphere(color, cx, cy - r * 0.4f, cz, 0.024f, r * 0.8f, r * 0.8f, outline = 0.005f)
    }

    /** A dot [size] across on an ellipsoid of half-axes [a], [b], [c] about ([cx], [cy], [cz]), toward ([u], [v], [w]). */
    private fun PartList.onEllipsoid(color: String, cx: Float, cy: Float, cz: Float, a: Float, b: Float, c: Float, u: Float, v: Float, w: Float, size: Float) {
        val n = sqrt(u * u + v * v + w * w)
        sphere(color, cx + a * u / n, cy + b * v / n - size * 0.35f, cz + c * w / n, size, size * 0.7f, size, outline = 0.005f, lump = 0f)
    }

    private const val PI = Math.PI.toFloat()
    private const val TAU = 2 * PI
    private const val RAD_TO_DEG = 180f / PI

    // The room's pastels (RoomModels), and a few of decor's own.
    private const val WOOD = "#dba77c"
    private const val WOOD_LIGHT = "#edc49c"
    private const val WOOD_DARK = "#b47e5e"
    private const val KNOB = "#f5cf6a"
    private const val LEAF = "#9fd49a"
    private const val LEAF_DARK = "#80c088"
    private const val LEAF_LIGHT = "#c2e6a8"
    private const val INK = "#4a3a4f"
    private const val INK_SOFT = "#8a7a96"
    private const val WIRE = "#6b5a6e"
    private const val THREAD = "#8a7a96"
    private const val BLUSH = "#f4a3ad"
    private const val LILAC_METAL = "#b9b0d6"
    private const val LAVA_GLASS = "#f7b0cf"
    private const val LAVA_BLOB = "#ff9e7a"
    private const val TEAL = "#9fd3d9"
    private const val TEAL_LIGHT = "#bfe4e8"
    private const val STEM = "#7fb87a"
    private const val SUN_PETAL = "#ffd25a"
    private const val SEED = "#a8724e"
    private const val MUSHROOM_STEM = "#fff1dc"
    private const val MUSHROOM_GILL = "#fde2c2"
    private const val MUSHROOM_CAP = "#f2867f"
    private const val SPOT = "#fffaf2"
    private const val BEANBAG = "#c9b2ec"
    private const val BEANBAG_TOP = "#d8c6f2"
    private const val BEANBAG_TAG = "#ffe3a1"
    private const val WATER = "#a9dcef"
    private const val GLASS_RIM = "#dff3f6"
    private const val GOLDFISH = "#ff9a5c"
    private const val GOLDFISH_FIN = "#ffb98a"
    private const val BUBBLE = "#ffffff"
    private val PEBBLES = listOf("#f4a3ad", "#ffe3a1", "#c9b2ec", "#9fc1ef")
    private const val TEDDY = "#d9a27a"
    private const val TEDDY_LIGHT = "#f3d3b0"
    private const val BOW = "#9fc1ef"
    private const val POT_BLUE = "#9fc1ef"
    private const val POT_BLUE_RIM = "#b8d2f4"
    private const val POT_PINK = "#f4a3ad"
    private const val POT_BUTTER = "#ffe3a1"
    private const val SOIL = "#8a6a5a"
    private const val CACTUS = "#8fcf9a"
    private const val CACTUS_FLOWER = "#f6a0b8"
    private val BULBS = listOf("#ffe08a", "#f6a0b8", "#a8e0b8", "#9fc1ef", "#d6aee6", "#ffbf8a")
    private const val STAR = "#ffe08a"
    private const val MOON = "#fff0a8"
    private const val FRAME_PINK = "#f6b2b7"
    private const val MIRROR = "#d8eef3"
    private const val SHINE = "#ffffff"
    private const val CORK = "#e3b57f"
    private val NOTES = listOf("#ffe08a", "#fffaf2", "#a8e0b8", "#f6b2b7")
    private val PINS = listOf("#ef7b7b", "#8fb0ec", "#f5cf6a")
    private const val PHOTO_SKY = "#9fc1ef"
    private const val HEART = "#ef7b7b"
    private val BANDS = listOf("#f4a3ad", "#ffbf8a", "#ffe08a", "#a8e0b8", "#9fc1ef")
    private const val CLOUD = "#fffaf2"
    private const val WALL_PLUG = "#fff8ee"
    private val PENNANTS = listOf("#f4a3ad", "#ffe08a", "#9fc1ef", "#a8e0b8", "#d6aee6")
}
