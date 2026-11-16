package dev.lilguy.parts

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The room's models in clay (`docs/decisions/2026-11-15-clay-replaces-crayon-and-models-stay-part-lists.md`):
 * smooth rounded solids in flat pastels, lit by the room's lights and casting soft shadows, with no
 * outlines and no lumps. The look is `world_sketches/fable_field_lab.html`'s.
 *
 * Same conventions as [RoomParts] (1 unit = 1 metre = 1 tile, pivot at the bottom centre of the
 * tile, front +Z, wall items' backs at z = 0), and the same positions where the view relies on them
 * (the desk's page, the easel's canvas, the record's spindle), so the web renderer draws one of these
 * where there is one and [RoomParts]' list otherwise. `:app` never reads this file.
 *
 * The guy is here too, as the pieces the view animates: body (centred on the origin, so it squashes
 * about its middle), arm, foot, sprout (pivot at its root, on top of his head), and the lantern that
 * follows him with its rotor. His face is the body's texture, `face`, painted per expression.
 */
object ClayParts {
    fun parts(body: PartList.() -> Unit): List<Part> = PartList().apply { outline = 0f; lump = 0f; body() }.parts

    /** Patches on the land, each with a `_bare` model for while nothing has grown back (declared first: [ITEM_IDS] uses it). */
    val PATCHES = listOf("glowcap_patch", "moss_patch", "fern_patch", "beetle_log", "pond_patch", "seedpod_bush")

    /** Looks an item takes on in a state the sim publishes: with its visitors round it (`_visited`), or ripe (declared before [ITEM_IDS], which uses it). */
    val LOOKS = listOf("bird_feeder_visited", "moth_lamp_visited", "pond_patch_visited", "planter_ripe")

    /** Item ids with a clay model of their own. */
    val ITEM_IDS = listOf(
        "bed", "desk", "chair", "easel", "record_player", "plant", "rug", "mail_hatch", "picture_rail", "poster",
        "lava_lamp", "mushroom_lamp", "string_lights", "cloud_light",
        "microscope", "garden_bench", "forest_trail", "clearing_log", "pond_edge",
        "lab_bench", "lab_shelf", "armchair", "floor_lamp", "weather_station",
        "heater", "fire_pit", "wind_chime", "bird_feeder", "moth_lamp", "planter",
    ) + PATCHES.flatMap { listOf(it, "${it}_bare") } + LOOKS

    /** The clay model of item [id]. */
    fun item(id: String): List<Part> = parts {
        when (id) {
            "bed" -> bed()
            "desk" -> desk()
            "chair" -> chair()
            "easel" -> easel()
            "record_player" -> recordPlayer()
            "plant" -> plant()
            "rug" -> cylinder("#ffffff", 0f, 0f, 0f, 2.4f, 0.025f, 1.6f, round = 0.012f, texture = "rug")
            "mail_hatch" -> mailHatch()
            "picture_rail" -> capsule(WOOD_D, RoomParts.RAIL_X - RoomParts.RAIL_LENGTH / 2, RoomParts.RAIL_Y + 0.02f, 0.03f, 0.05f, RoomParts.RAIL_LENGTH, 0.05f, rotZ = -90f)
            "poster" -> {
                box("#ffffff", 0f, 0.95f, 0.012f, 0.62f, 0.82f, 0.025f, round = 0.01f, texture = "poster")
                sphere(CORAL, 0f, 1.72f, 0.025f, 0.05f, 0.05f, 0.03f)
            }
            "lava_lamp" -> lavaLamp()
            "mushroom_lamp" -> mushroomLamp()
            "string_lights" -> stringLights()
            "cloud_light" -> cloudLight()
            "microscope" -> microscope()
            "garden_bench" -> gardenBench()
            "forest_trail" -> trailSign()
            "clearing_log" -> log(1.3f)
            "pond_edge" -> pondEdge()
            "lab_bench" -> labBench()
            "lab_shelf" -> labShelf()
            "armchair" -> armchair()
            "floor_lamp" -> floorLamp()
            "weather_station" -> weatherStation()
            "heater" -> heater()
            "fire_pit" -> firePit()
            "wind_chime" -> windChime()
            "bird_feeder" -> birdFeeder(false)
            "bird_feeder_visited" -> birdFeeder(true)
            "moth_lamp" -> porchLantern(false)
            "moth_lamp_visited" -> porchLantern(true)
            "pond_patch_visited" -> { reeds(false); fireflies() }
            "planter" -> planter(false)
            "planter_ripe" -> planter(true)
            else -> {
                val bare = id.endsWith("_bare")
                when (id.removeSuffix("_bare")) {
                    "glowcap_patch" -> glowcaps(bare)
                    "moss_patch" -> mossStones(bare)
                    "fern_patch" -> ferns(bare)
                    "beetle_log" -> beetleLog(bare)
                    "pond_patch" -> reeds(bare)
                    "seedpod_bush" -> seedpodBush(bare)
                    else -> error("no clay model for '$id'")
                }
            }
        }
    }

    /** Props that differ from [RoomParts]' in clay, by name; the view falls back to the crayon export's for the rest. */
    fun props(): Map<String, List<Part>> = linkedMapOf(
        "guy_body" to parts { guyBody() },
        "guy_arm" to parts { sphere(BUTTER, 0f, -0.123f, 0f, 0.17f, 0.246f, 0.17f) },
        "guy_foot" to parts { sphere(BUTTER, 0f, -0.072f, 0f, 0.24f, 0.144f, 0.324f) },
        "guy_sprout" to parts { sprout() },
        "lantern" to parts { lantern() },
        "lantern_rotor" to parts {
            capsule(BRASS, 0f, 0f, 0f, 0.016f, 0.08f, 0.016f)
            box(TRIM, 0f, 0.075f, 0f, 0.3f, 0.008f, 0.035f, round = 0.003f)
        },
        "wall_clock" to parts {
            ring(WOOD_D, 0f, RoomParts.CLOCK_Y - 0.22f, 0.035f, 0.44f, 0.44f, 0.07f, hole = 0.84f, round = 0.03f)
            cylinder("#ffffff", 0f, RoomParts.CLOCK_Y, 0.005f, 0.38f, 0.05f, 0.38f, rotX = 90f, round = 0.01f, texture = "clock")
        },
        "shelf_boards" to parts {
            for (y in listOf(RoomParts.SHELF_LOW, RoomParts.SHELF_HIGH)) {
                box(WOOD, 0f, y, RoomParts.SHELF_DEPTH / 2, RoomParts.SHELF_WIDTH, 0.04f, RoomParts.SHELF_DEPTH, round = 0.015f)
                for (side in listOf(-1f, 1f)) box(WOOD_D, side * (RoomParts.SHELF_WIDTH / 2 - 0.1f), y - 0.1f, 0.05f, 0.04f, 0.1f, 0.1f, round = 0.015f)
            }
        },
    )

    // --- the guy -------------------------------------------------------------------------------

    /** His body's half width and half height; his feet' tops are [FEET] under the body's bottom. */
    const val GUY_RX = 0.33f
    const val GUY_RY = 0.4f
    /** How far his body's bottom is off the ground, his shoulders (from his middle), and how far out. */
    const val GUY_FEET = 0.1f
    const val GUY_ARM_Y = -0.04f
    const val GUY_ARM_X = 0.35f
    const val GUY_FOOT_Z = 0.05f

    /** A point on his body at height [dy] from its middle and [a] radians round from the front, [out] times the surface. */
    private fun on(a: Float, dy: Float, out: Float = 1f): Triple<Float, Float, Float> {
        val r = GUY_RX * sqrt(1f - (dy / GUY_RY) * (dy / GUY_RY)) * out
        return Triple(sin(a) * r, dy, cos(a) * r)
    }

    private fun PartList.guyBody() {
        // White, as his colour is all in the face texture's paint.
        sphere("#ffffff", 0f, -GUY_RY, 0f, GUY_RX * 2, GUY_RY * 2, GUY_RX * 2, texture = "face")
        // Goggles pushed up on his forehead, on a strap round his head.
        val gy = 0.245f
        val gr = GUY_RX * sqrt(1f - (gy / GUY_RY) * (gy / GUY_RY))
        cylinder(STRAP, 0f, gy - 0.02f, 0f, (gr + 0.012f) * 2, 0.04f, (gr + 0.012f) * 2, hole = 0.88f, round = 0.018f)
        for (k in listOf(-1f, 1f)) {
            val (x, y, z) = on(k * 0.36f, gy, 1.07f)
            val yaw = deg(k * 0.36f)
            ring(BRASS, x, y - 0.095f, z, 0.19f, 0.19f, 0.045f, hole = 0.6f, round = 0.022f, rotY = yaw, rotX = -32f)
            ring(LENS, x, y - 0.07f, z + 0.005f, 0.14f, 0.14f, 0.03f, hole = 0f, round = 0.01f, rotY = yaw, rotX = -32f)
        }
        // His field pack, on his back.
        box(PACK, 0f, -0.18f, -0.34f, 0.34f, 0.32f, 0.17f, round = 0.07f)
        box(PACK_DARK, 0f, 0.04f, -0.34f, 0.34f, 0.12f, 0.19f, round = 0.05f)
        cylinder(PEACH, -0.18f, 0.2f, -0.36f, 0.12f, 0.36f, 0.12f, rotZ = -90f, round = 0.05f)
        sphere(AMBER, 0.1f, -0.025f, -0.44f, 0.05f, 0.05f, 0.05f, glow = 1f)
    }

    private fun PartList.sprout() {
        cylinder(SPROUT_STEM, 0f, -0.02f, 0f, 0.028f, 0.19f, 0.032f, rotZ = -7f, round = 0.012f)
        sphere(SPROUT_LEAF, -0.075f, 0.17f, 0f, 0.24f, 0.058f, 0.135f, rotZ = 27f)
        sphere(SPROUT_LEAF, 0.1f, 0.19f, 0f, 0.24f, 0.058f, 0.135f, rotZ = -26f)
    }

    /** His lantern, a glowing globe in two brass hoops under a cap, centred on the origin; its rotor spins on top. */
    private fun PartList.lantern() {
        sphere(LANTERN, 0f, -0.085f, 0f, 0.17f, 0.17f, 0.17f, glow = 1.4f)
        ring(BRASS, 0f, -0.11f, 0f, 0.22f, 0.22f, 0.024f, hole = 0.89f, round = 0.012f)
        cylinder(BRASS, 0f, -0.012f, 0f, 0.244f, 0.024f, 0.244f, hole = 0.9f, round = 0.012f)
        dome(BRASS, 0f, 0.07f, 0f, 0.18f, 0.11f, 0.18f)
    }

    // --- furniture -----------------------------------------------------------------------------

    /** Its head against the back wall; a little longer than its tile, into the wall's side. */
    private fun PartList.bed() {
        box(WOOD, 0f, 0f, 0.1f, 0.96f, 0.26f, 1.2f, round = 0.07f)
        box(WOOD_D, 0f, 0f, -0.44f, 0.96f, 0.8f, 0.13f, round = 0.06f)
        box(LINEN, 0f, 0.24f, 0.12f, 0.86f, 0.15f, 1.08f, round = 0.07f)
        box(BLANKET, 0f, 0.33f, 0.33f, 0.9f, 0.12f, 0.68f, round = 0.06f)
        box(BLANKET_FOLD, 0f, 0.38f, -0.03f, 0.9f, 0.08f, 0.22f, round = 0.04f)
        box(LINEN, 0f, 0.38f, -0.29f, 0.54f, 0.14f, 0.28f, round = 0.07f)
    }

    /** The desk keeps [RoomParts]' height and the page where the view draws his sketch. */
    private fun PartList.desk() {
        for (sx in listOf(-0.42f, 0.42f)) for (sz in listOf(-0.25f, 0.25f)) cylinder(WOOD_D, sx, 0f, sz, 0.06f, 0.5f, 0.06f, taper = 0.85f, round = 0.02f)
        box(WOOD, 0f, 0.5f, 0f, 0.98f, 0.05f, 0.62f, round = 0.025f)
        box(PEACH, 0.24f, 0.34f, 0f, 0.42f, 0.15f, 0.56f, round = 0.03f)
        sphere(BRASS, 0.24f, 0.395f, 0.28f, 0.04f, 0.04f, 0.03f)
        box(PAPER, RoomParts.SKETCH_X, 0.55f, RoomParts.SKETCH_Z, 0.28f, 0.006f, 0.2f, round = 0.002f)
        cylinder(CORAL, 0.18f, 0.55f, 0.12f, 0.1f, 0.11f, 0.1f, taper = 0.9f, round = 0.02f)
        ring(CORAL, 0.245f, 0.57f, 0.12f, 0.07f, 0.07f, 0.02f, hole = 0.5f, rotY = 90f, round = 0.01f)
        // A little lamp: a glowing shade on a brass stem.
        cylinder(BRASS, -0.34f, 0.55f, -0.18f, 0.14f, 0.03f, 0.14f, round = 0.012f)
        cylinder(BRASS, -0.34f, 0.57f, -0.18f, 0.025f, 0.17f, 0.025f)
        dome(SHADE, -0.34f, 0.71f, -0.18f, 0.28f, 0.27f, 0.28f, glow = 1f)
    }

    /** He sits facing +X, toward the desk; the back is on -X. */
    private fun PartList.chair() {
        for (sx in listOf(-0.15f, 0.15f)) for (sz in listOf(-0.15f, 0.15f)) cylinder(WOOD_D, sx, 0f, sz, 0.045f, 0.3f, 0.045f, round = 0.015f)
        cylinder(WOOD, 0f, 0.28f, 0f, 0.44f, 0.06f, 0.44f, round = 0.03f)
        cylinder(CORAL, 0f, 0.33f, 0f, 0.38f, 0.06f, 0.38f, round = 0.03f)
        box(WOOD, -0.2f, 0.3f, 0f, 0.05f, 0.42f, 0.38f, round = 0.025f)
        capsule(WOOD_D, -0.2f, 0.72f, -0.21f, 0.06f, 0.42f, 0.06f, rotX = 90f)
    }

    /** As [RoomParts]' easel, so a canvas stands on its ledge where the view puts the painting. */
    private fun PartList.easel() {
        box(WOOD, -0.22f, 0f, 0.08f, 0.055f, 1.15f, 0.055f, rotZ = -8f, round = 0.025f)
        box(WOOD, 0.22f, 0f, 0.08f, 0.055f, 1.15f, 0.055f, rotZ = 8f, round = 0.025f)
        box(WOOD_D, 0f, 0f, -0.2f, 0.055f, 1.1f, 0.055f, rotX = -14f, round = 0.025f)
        box(WOOD_D, 0f, 0.42f, 0.12f, 0.6f, 0.05f, 0.1f, round = 0.025f)
        sphere(BRASS, 0f, 1.1f, 0.09f, 0.06f, 0.06f, 0.06f)
    }

    /** A cabinet with the turntable on top; the record is the view's ([RoomParts.disc]), at [RoomParts.DISC_X], [RoomParts.DISC_Y]. */
    private fun PartList.recordPlayer() {
        box(WOOD_D, 0f, 0f, 0f, 0.8f, 0.5f, 0.46f, round = 0.05f)
        box(PEACH, 0f, 0.07f, 0.215f, 0.68f, 0.32f, 0.04f, round = 0.03f)
        for (sx in listOf(-0.17f, 0.17f)) ring(SAGE, sx, 0.12f, 0.24f, 0.22f, 0.22f, 0.02f, hole = 0f, round = 0.008f)
        box(WOOD, 0f, 0.5f, 0f, 0.62f, 0.05f, 0.4f, round = 0.025f)
        cylinder(PLATTER, RoomParts.DISC_X, 0.545f, 0f, 0.32f, 0.012f, 0.32f, round = 0.004f)
        capsule(BRASS, 0.2f, 0.575f, -0.15f, 0.025f, 0.22f, 0.025f, rotX = 90f)
        sphere(BRASS, 0.2f, 0.56f, -0.16f, 0.06f, 0.04f, 0.06f)
    }

    /** A leafy pot plant, as the sketch's. */
    private fun PartList.plant() {
        cylinder(CORAL, 0f, 0f, 0f, 0.26f, 0.3f, 0.26f, taper = 1.35f, round = 0.03f)
        cylinder(SOIL, 0f, 0.27f, 0f, 0.3f, 0.03f, 0.3f, round = 0.012f)
        val n = 9
        for (i in 0 until n) {
            val a = i * 2 * PI.toFloat() / n + i
            val t = 0.5f + (i % 3) * 0.22f
            sphere(
                if (i % 2 == 0) LEAF else LEAF_LIGHT, 0.05f * cos(a), 0.27f, 0.05f * sin(a), 0.2f, 0.32f + 0.3f * t, 0.06f,
                rotY = deg(PI.toFloat() / 2 - a), rotX = deg(0.4f + 0.35f * t),
            )
        }
    }

    /** A brass-flapped hatch in the wall at hand height, with a little shelf under it for parcels. */
    private fun PartList.mailHatch() {
        box(TRIM, 0f, 0.5f, 0.02f, 0.68f, 0.54f, 0.05f, round = 0.03f)
        box(CORAL, 0f, 0.58f, 0.045f, 0.52f, 0.34f, 0.03f, round = 0.02f)
        box(INK, 0f, 0.84f, 0.05f, 0.32f, 0.035f, 0.02f, round = 0.012f)
        sphere(BRASS, 0f, 0.62f, 0.06f, 0.07f, 0.07f, 0.05f)
        box(WOOD, 0f, 0.42f, 0.12f, 0.74f, 0.05f, 0.24f, round = 0.02f)
        for (sx in listOf(-0.29f, 0.29f)) box(WOOD_D, sx, 0.28f, 0.05f, 0.045f, 0.14f, 0.09f, round = 0.02f)
    }

    // --- lamps: they glow at night, and the view lights the room from them -------------------------

    /** On a little bedside cube: a brass foot, a glowing glass and its blobs, a brass cap. */
    private fun PartList.lavaLamp() {
        box(WOOD, 0f, 0f, 0f, 0.5f, 0.46f, 0.46f, round = 0.05f)
        box(PEACH, 0f, 0.08f, 0.225f, 0.38f, 0.16f, 0.03f, round = 0.02f)
        sphere(BRASS, 0f, 0.14f, 0.245f, 0.04f, 0.04f, 0.03f)
        cylinder(BRASS, 0f, 0.46f, 0f, 0.18f, 0.1f, 0.18f, taper = 0.7f, round = 0.03f)
        cylinder(LAVA_GLASS, 0f, 0.56f, 0f, 0.15f, 0.3f, 0.15f, taper = 0.6f, round = 0.04f, glow = 0.8f)
        sphere(LAVA_BLOB, 0f, 0.6f, 0f, 0.09f, 0.1f, 0.09f, glow = 1f)
        sphere(LAVA_BLOB, 0.01f, 0.73f, 0f, 0.06f, 0.07f, 0.06f, glow = 1f)
        cylinder(BRASS, 0f, 0.86f, 0f, 0.1f, 0.07f, 0.1f, taper = 0.6f, round = 0.02f)
    }

    private fun PartList.mushroomLamp() {
        cylinder(WOOD_D, 0f, 0f, 0f, 0.3f, 0.05f, 0.3f, round = 0.02f)
        cylinder(TRIM, 0f, 0.05f, 0f, 0.13f, 0.34f, 0.13f, taper = 0.75f, round = 0.04f)
        dome(MUSHROOM, 0f, 0.36f, 0f, 0.46f, 0.36f, 0.46f, glow = 0.9f)
        for (k in 0 until 5) {
            val a = k * 2 * PI.toFloat() / 5 + 0.4f
            val r = if (k % 2 == 0) 0.13f else 0.09f
            sphere(TRIM, r * cos(a), 0.36f + 0.16f * sqrt(1f - (r / 0.23f) * (r / 0.23f)) - 0.01f, r * sin(a), 0.06f, 0.025f, 0.06f, glow = 0.4f)
        }
    }

    /** Lights strung along the top of the wall in two swags, glowing bulbs on a dark wire. */
    private fun PartList.stringLights() {
        val y = 2.0f
        val z = 0.04f
        var k = 0
        for ((x0, x1) in listOf(-0.95f to 0.05f, 0.05f to 1.05f)) {
            val n = 8
            val pts = (0..n).map { i -> val u = i.toFloat() / n; Pair(x0 + (x1 - x0) * u, y - 0.16f * sin(PI.toFloat() * u)) }
            for (i in 0 until n) segment(WIRE, pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second, z, 0.012f)
            for (p in pts.drop(1).dropLast(1)) sphere(BULBS[k++ % BULBS.size], p.first, p.second - 0.1f, z, 0.05f, 0.075f, 0.05f, glow = 1.2f)
        }
        for (x in listOf(-0.95f, 0.05f, 1.05f)) sphere(BRASS, x, y - 0.02f, 0.02f, 0.04f, 0.04f, 0.03f)
    }

    /** A puffy cloud night-light with a sleeping face, its cord looping down the wall. */
    private fun PartList.cloudLight() {
        val y = 1.2f
        sphere(CLOUD, -0.15f, y, 0.07f, 0.22f, 0.19f, 0.14f, glow = 0.6f)
        sphere(CLOUD, 0.15f, y, 0.07f, 0.22f, 0.18f, 0.14f, glow = 0.6f)
        sphere(CLOUD, -0.05f, y + 0.04f, 0.085f, 0.24f, 0.24f, 0.17f, glow = 0.6f)
        sphere(CLOUD, 0.07f, y + 0.03f, 0.085f, 0.22f, 0.22f, 0.16f, glow = 0.6f)
        for (side in listOf(-1f, 1f)) {
            ring(INK, side * 0.065f, y + 0.115f, 0.178f, 0.05f, 0.03f, 0.01f, hole = 0.6f, arc = 180f, round = 0.004f)
            sphere(BLUSH, side * 0.11f, y + 0.08f, 0.165f, 0.045f, 0.028f, 0.012f)
        }
        capsule(WIRE, 0.15f, y - 0.4f, 0.012f, 0.012f, 0.42f, 0.012f)
        box(TRIM, 0.15f, y - 0.47f, 0.014f, 0.06f, 0.08f, 0.028f, round = 0.01f)
    }

    /** A capsule from ([ax], [ay]) to ([bx], [by]) in the plane z = [z], [thick] across. */
    // --- outdoors: lookouts he explores from, patches he collects from, and the microscope --------

    /** A brass microscope on a little wooden stand, as the sketch's lab bench has. */
    private fun PartList.microscope() {
        box(WOOD_D, 0f, 0f, 0f, 0.62f, 0.5f, 0.46f, round = 0.04f)
        box(WOOD, 0f, 0.5f, 0f, 0.7f, 0.05f, 0.52f, round = 0.025f)
        box(INK, 0f, 0.55f, 0f, 0.24f, 0.03f, 0.2f, round = 0.012f)
        cylinder(BRASS, -0.02f, 0.58f, -0.06f, 0.05f, 0.34f, 0.05f, rotX = 12f)
        cylinder(TRIM, 0.0f, 0.78f, -0.02f, 0.09f, 0.26f, 0.09f, rotX = -30f, taper = 0.75f)
        cylinder(INK, 0.0f, 0.99f, -0.14f, 0.05f, 0.07f, 0.05f, rotX = -30f)
        box(LENS, 0f, 0.6f, 0.06f, 0.14f, 0.012f, 0.1f, round = 0.004f)
        for (sx in listOf(-0.16f, 0.18f)) cylinder(LENS, sx, 0.55f, 0.12f, 0.06f, 0.09f, 0.06f, taper = 0.85f, opacity = 0.7f)
    }

    /** A white bench with flasks and a glowing analyser screen, as the sketch's lab. */
    private fun PartList.labBench() {
        box(TRIM, 0f, 0f, 0f, 0.96f, 0.62f, 0.6f, round = 0.04f)
        box("#e9d2b6", 0f, 0.62f, 0f, 1.0f, 0.05f, 0.64f, round = 0.02f)
        for ((x, c) in listOf(-0.3f to "#bfeee0", -0.12f to "#f6c4cf")) {
            sphere(LENS, x, 0.67f, 0.08f, 0.14f, 0.14f, 0.14f, opacity = 0.6f)
            cylinder(c, x, 0.68f, 0.08f, 0.09f, 0.05f, 0.09f)
            cylinder(LENS, x, 0.79f, 0.08f, 0.04f, 0.1f, 0.04f, opacity = 0.6f)
        }
        box(INK, 0.24f, 0.67f, -0.12f, 0.34f, 0.26f, 0.2f, round = 0.03f)
        box(AMBER, 0.24f, 0.72f, -0.015f, 0.26f, 0.16f, 0.01f, round = 0.01f, glow = 0.8f)
    }

    private fun PartList.labShelf() {
        // Open at the front: a back, two sides, a top and boards, so the jars show.
        box(WOOD_D, 0f, 0f, -0.15f, 0.8f, 1.7f, 0.04f, round = 0.015f)
        for (sx in listOf(-0.38f, 0.38f)) box(WOOD_D, sx, 0f, 0f, 0.04f, 1.7f, 0.34f, round = 0.015f)
        box(WOOD_D, 0f, 1.66f, 0f, 0.8f, 0.04f, 0.34f, round = 0.015f)
        for (y in listOf(0.02f, 0.45f, 0.9f, 1.35f)) box(WOOD, 0f, y, 0.02f, 0.72f, 0.03f, 0.3f, round = 0.01f)
        val colors = listOf("#bfeee0", "#86b96a", "#6fb3c9", "#ffc47a")
        for (row in 0 until 3) for (i in 0 until 3) {
            val x = -0.24f + i * 0.24f
            val y = 0.48f + row * 0.45f
            cylinder(LENS, x, y, 0.06f, 0.12f, 0.16f, 0.12f, opacity = 0.5f)
            sphere(colors[(row + i) % colors.size], x, y + 0.02f, 0.06f, 0.08f, 0.08f, 0.08f)
        }
    }

    private fun PartList.armchair() {
        box(CORAL, 0f, 0f, 0f, 0.84f, 0.42f, 0.8f, round = 0.12f)
        box(CORAL, 0f, 0.42f, -0.3f, 0.84f, 0.5f, 0.2f, round = 0.1f)
        for (sx in listOf(-0.36f, 0.36f)) box(CORAL, sx, 0.42f, 0f, 0.14f, 0.2f, 0.7f, round = 0.07f)
        box(PEACH, 0f, 0.42f, 0.06f, 0.56f, 0.1f, 0.56f, round = 0.05f)
    }

    private fun PartList.floorLamp() {
        cylinder(INK, 0f, 0f, 0f, 0.34f, 0.04f, 0.34f, round = 0.015f)
        cylinder(BRASS, 0f, 0.04f, 0f, 0.04f, 1.36f, 0.04f)
        cylinder(SHADE, 0f, 1.3f, 0f, 0.46f, 0.34f, 0.46f, taper = 0.6f, glow = 1f)
    }

    private fun PartList.gardenBench() {
        for (sx in listOf(-0.42f, 0.42f)) box(WOOD_D, sx, 0f, 0f, 0.08f, 0.32f, 0.34f, round = 0.03f)
        box(WOOD, 0f, 0.32f, 0f, 1.0f, 0.06f, 0.38f, round = 0.03f)
        box(WOOD, 0f, 0.38f, -0.17f, 1.0f, 0.3f, 0.05f, round = 0.025f)
    }

    /**
     * The weather station: a white louvred box on a post (the thermometer's screen), a cup
     * anemometer on top, a rain gauge on an arm, and a little glowing dial on the front where its log shows.
     */
    private fun PartList.weatherStation() {
        cylinder(WOOD_D, 0f, 0f, 0f, 0.08f, 1.05f, 0.08f, round = 0.02f)
        box(TRIM, 0f, 0.82f, 0f, 0.42f, 0.36f, 0.34f, round = 0.03f)
        for (i in 0..3) box(CREAM, 0f, 0.86f + i * 0.075f, 0.172f, 0.36f, 0.025f, 0.012f, round = 0.006f)
        box(TRIM, 0f, 1.18f, 0f, 0.5f, 0.04f, 0.42f, round = 0.02f)
        box(INK, 0.12f, 0.9f, 0.18f, 0.1f, 0.08f, 0.01f, round = 0.01f)
        box(AMBER, 0.12f, 0.9f, 0.186f, 0.07f, 0.05f, 0.006f, round = 0.006f, glow = 0.7f)
        // The anemometer: a mast and three cups on spokes.
        cylinder(INK, 0f, 1.2f, 0f, 0.025f, 0.3f, 0.025f)
        for (k in 0..2) {
            val a = k * 2 * PI.toFloat() / 3
            val (cx, cz) = cos(a) * 0.14f to sin(a) * 0.14f
            capsule(INK, cx / 2, 1.5f, cz / 2, 0.015f, 0.14f, 0.015f, rotZ = 90f)
            sphere(CORAL, cx, 1.47f, cz, 0.07f, 0.06f, 0.07f)
        }
        // The rain gauge, on an arm off the post.
        box(WOOD_D, -0.2f, 0.55f, 0f, 0.28f, 0.04f, 0.05f, round = 0.015f)
        cylinder(LENS, -0.32f, 0.57f, 0f, 0.09f, 0.2f, 0.09f, opacity = 0.7f)
        cylinder(BLANKET, -0.32f, 0.58f, 0f, 0.07f, 0.06f, 0.07f)
    }

    /** A rounded radiator on little feet, its fins warm-glowing. */
    private fun PartList.heater() {
        for (sx in listOf(-0.24f, 0.24f)) box(INK, sx, 0f, 0f, 0.06f, 0.06f, 0.2f, round = 0.02f)
        box(CREAM, 0f, 0.06f, 0f, 0.62f, 0.5f, 0.16f, round = 0.05f)
        for (i in 0..4) box(CORAL, -0.22f + i * 0.11f, 0.12f, 0.085f, 0.05f, 0.36f, 0.01f, round = 0.02f, glow = 0.35f)
    }

    /** A ring of stones round a little fire. */
    private fun PartList.firePit() {
        for (k in 0 until 9) {
            val a = k * 2 * PI.toFloat() / 9
            sphere("#b8b0a8", 0.36f * cos(a), 0f, 0.36f * sin(a), 0.2f, 0.16f, 0.18f)
        }
        for (k in 0..2) capsule(WOOD_D, 0f, 0.06f, 0f, 0.06f, 0.42f, 0.06f, rotZ = 90f + k * 60f)
        dome("#ff9a5c", 0f, 0.05f, 0f, 0.3f, 0.4f, 0.3f, glow = 1f, opacity = 0.9f)
        dome(AMBER, 0f, 0.08f, 0f, 0.16f, 0.26f, 0.16f, glow = 1f)
    }

    /** A hook on a post with a few hanging tubes. */
    private fun PartList.windChime() {
        cylinder(WOOD_D, 0f, 0f, 0f, 0.06f, 1.5f, 0.06f, round = 0.02f)
        box(WOOD_D, 0.14f, 1.44f, 0f, 0.3f, 0.04f, 0.04f, round = 0.015f)
        cylinder(BRASS, 0.24f, 1.28f, 0f, 0.14f, 0.02f, 0.14f)
        for (k in 0 until 5) {
            val a = k * 2 * PI.toFloat() / 5
            cylinder(LENS, 0.24f + 0.05f * cos(a), 1.0f + (k % 3) * 0.04f, 0.05f * sin(a), 0.025f, 0.24f - (k % 3) * 0.04f, 0.025f)
        }
    }

    /** A little house of seed on a post; [birds] perch on it and peck below. */
    private fun PartList.birdFeeder(birds: Boolean) {
        cylinder(WOOD_D, 0f, 0f, 0f, 0.07f, 1.0f, 0.07f, round = 0.02f)
        box(WOOD, 0f, 1.0f, 0f, 0.44f, 0.04f, 0.34f, round = 0.015f)
        cylinder(LENS, 0f, 1.04f, 0f, 0.2f, 0.22f, 0.2f, opacity = 0.7f)
        cylinder(BUTTER, 0f, 1.04f, 0f, 0.16f, 0.12f, 0.16f)
        box(CORAL, 0f, 1.27f, 0f, 0.5f, 0.05f, 0.4f, round = 0.02f, rotZ = 0f)
        if (!birds) return
        for ((x, y, z, c) in listOf(Quad(-0.18f, 1.05f, 0.12f, "#7d9bc4"), Quad(0.2f, 1.05f, -0.1f, "#c98f6b"), Quad(0.32f, 0f, 0.3f, "#9aa86e"))) {
            sphere(c, x, y, z, 0.13f, 0.11f, 0.17f)
            sphere(c, x, y + 0.09f, z + 0.06f, 0.08f, 0.08f, 0.08f)
            sphere(BUTTER, x, y + 0.09f, z + 0.11f, 0.03f, 0.02f, 0.04f)
        }
    }

    private data class Quad(val x: Float, val y: Float, val z: Float, val c: String)

    /** A lantern on a post by the door; [moths] flutter round its glass. */
    private fun PartList.porchLantern(moths: Boolean) {
        cylinder(INK, 0f, 0f, 0f, 0.07f, 1.25f, 0.07f, round = 0.02f)
        box(INK, 0f, 1.25f, 0f, 0.24f, 0.04f, 0.24f, round = 0.015f)
        cylinder(SHADE, 0f, 1.29f, 0f, 0.18f, 0.24f, 0.18f, glow = 1f, opacity = 0.9f)
        cylinder(INK, 0f, 1.53f, 0f, 0.26f, 0.08f, 0.26f, taper = 0.5f)
        if (!moths) return
        for (k in 0 until 6) {
            val a = k * 2 * PI.toFloat() / 6 + 0.4f
            val r = 0.22f + (k % 2) * 0.1f
            val y = 1.25f + (k % 3) * 0.12f
            box("#efe6d8", r * cos(a), y, r * sin(a), 0.09f, 0.012f, 0.05f, rotY = deg(-a), rotZ = if (k % 2 == 0) 25f else -25f, round = 0.004f, glow = 0.2f)
        }
    }

    /** Fireflies over the reeds: points of glow at different heights. */
    private fun PartList.fireflies() {
        for (k in 0 until 9) {
            val a = k * 2.4f
            sphere("#e8ff9a", 0.4f * cos(a), 0.35f + (k % 4) * 0.18f, 0.35f * sin(a), 0.04f, 0.04f, 0.04f, glow = 1f)
        }
    }

    /** A wooden trough of soil with seedlings; [ripe], tomato vines with red fruit. */
    private fun PartList.planter(ripe: Boolean) {
        box(WOOD, 0f, 0f, 0f, 0.7f, 0.3f, 0.36f, round = 0.03f)
        box(SOIL, 0f, 0.28f, 0f, 0.62f, 0.03f, 0.3f, round = 0.01f)
        for (i in 0..2) {
            val x = -0.2f + i * 0.2f
            val h = if (ripe) 0.42f else 0.14f
            cylinder(LEAF, x, 0.3f, 0f, 0.03f, h, 0.03f)
            sphere(LEAF_LIGHT, x, 0.3f + h * 0.6f, 0f, 0.16f, if (ripe) 0.24f else 0.1f, 0.14f)
            if (ripe) for (j in 0..1) sphere(CORAL, x + 0.05f - j * 0.1f, 0.42f + j * 0.12f, 0.07f, 0.07f, 0.07f, 0.07f)
        }
    }

    /** A post with a little sign pointing into the trees. */
    private fun PartList.trailSign() {
        cylinder(WOOD_D, 0f, 0f, 0f, 0.07f, 1.0f, 0.07f, round = 0.02f)
        box(TRIM, 0.12f, 0.78f, 0f, 0.38f, 0.14f, 0.03f, round = 0.02f, rotY = 20f)
        box(SAGE, 0.12f, 0.82f, 0.018f, 0.24f, 0.04f, 0.01f, round = 0.005f, rotY = 20f)
        sphere(LEAF, -0.1f, 0f, 0.08f, 0.26f, 0.18f, 0.22f)
    }

    /** A log lying along X to sit on. */
    private fun PartList.log(length: Float) {
        cylinder(WOOD_D, 0f, 0.18f, 0f, 0.34f, length, 0.34f, rotZ = 90f, round = 0.05f)
    }

    /** Flat stones at the water's edge. */
    private fun PartList.pondEdge() {
        for ((x, z, s) in listOf(Triple(-0.2f, 0.05f, 0.42f), Triple(0.22f, -0.12f, 0.34f), Triple(0.12f, 0.24f, 0.28f))) {
            sphere("#d8d2c4", x, 0f, z, s, s * 0.35f, s * 0.8f)
        }
    }

    private fun PartList.glowcaps(bare: Boolean) {
        log(0.9f)
        if (bare) return
        for ((x, z, s) in listOf(Triple(-0.2f, 0.24f, 1f), Triple(0.1f, 0.26f, 0.7f), Triple(0.28f, 0.2f, 0.55f))) {
            cylinder(TRIM, x, 0f, z, 0.05f * s, 0.2f * s, 0.05f * s)
            dome("#bfeee0", x, 0.18f * s, z, 0.24f * s, 0.12f * s, 0.24f * s, glow = 0.6f)
        }
    }

    private fun PartList.mossStones(bare: Boolean) {
        sphere("#d8d2c4", 0f, 0f, 0f, 0.5f, 0.28f, 0.42f)
        sphere("#cfc8b8", 0.3f, 0f, 0.15f, 0.32f, 0.2f, 0.3f)
        if (bare) return
        sphere("#86b96a", -0.02f, 0.16f, 0f, 0.3f, 0.16f, 0.26f)
        for (i in 0 until 5) sphere("#a8d58a", -0.12f + i * 0.06f, 0.24f, 0.02f * (i % 2), 0.07f, 0.07f, 0.07f)
    }

    private fun PartList.ferns(bare: Boolean) {
        val n = if (bare) 3 else 7
        for (i in 0 until n) {
            val a = i * 2 * PI.toFloat() / n
            val color = if (bare) "#8aa88a" else if (i % 2 == 0) "#6fb3c9" else "#8fcbd8"
            sphere(color, 0.06f * cos(a), 0f, 0.06f * sin(a), 0.14f, (if (bare) 0.2f else 0.5f), 0.05f, rotY = deg(PI.toFloat() / 2 - a), rotX = 40f)
        }
    }

    private fun PartList.beetleLog(bare: Boolean) {
        log(1.0f)
        sphere(LEAF, -0.3f, 0.3f, 0.05f, 0.2f, 0.08f, 0.2f)
        if (bare) return
        sphere("#8f7fd6", 0.15f, 0.34f, 0.05f, 0.12f, 0.08f, 0.16f)
        sphere(INK, 0.15f, 0.36f, 0.13f, 0.06f, 0.05f, 0.05f)
    }

    private fun PartList.reeds(bare: Boolean) {
        val n = if (bare) 3 else 6
        for (i in 0 until n) {
            val x = -0.25f + i * 0.1f
            val h = 0.5f + (i % 3) * 0.18f
            cylinder("#7fae84", x, 0f, 0.04f * (i % 2), 0.025f, h, 0.025f, rotZ = (i - n / 2f) * 3f)
            if (!bare && i % 2 == 1) capsule("#a9805f", x, h - 0.02f, 0.04f * (i % 2), 0.05f, 0.14f, 0.05f)
        }
        if (!bare) cylinder("#9fd0d6", 0.32f, 0f, 0.1f, 0.12f, 0.14f, 0.12f, opacity = 0.75f)
    }

    private fun PartList.seedpodBush(bare: Boolean) {
        sphere(LEAF, 0f, 0f, 0f, 0.7f, 0.55f, 0.6f)
        sphere(LEAF_LIGHT, 0.18f, 0.22f, 0.08f, 0.4f, 0.36f, 0.36f)
        if (bare) return
        for ((x, y, z) in listOf(Triple(-0.22f, 0.36f, 0.2f), Triple(0.12f, 0.46f, 0.18f), Triple(0.28f, 0.28f, -0.1f))) {
            sphere(AMBER, x, y, z, 0.1f, 0.15f, 0.1f, glow = 0.4f)
        }
    }

    private fun PartList.segment(color: String, ax: Float, ay: Float, bx: Float, by: Float, z: Float, thick: Float) {
        val dx = bx - ax
        val dy = by - ay
        capsule(color, ax, ay, z, thick, hypot(dx, dy) + thick, thick, rotZ = deg(atan2(-dx, dy)))
    }

    private fun deg(rad: Float) = rad * 180f / PI.toFloat()

    // The Field Lab's colours: butter, cream, honey wood, coral, sage, brass, ink.
    const val BUTTER = "#ffe3a1"
    const val TRIM = "#fffaf0"
    const val CREAM = "#f6ead6"
    const val WOOD = "#d2a67c"
    const val WOOD_D = "#b98862"
    const val CORAL = "#e58573"
    const val SAGE = "#8fb8a0"
    const val BRASS = "#d9a95a"
    const val INK = "#4a3a4f"
    const val PEACH = "#f1c9b4"
    const val LINEN = "#fffaf0"
    const val BLANKET = "#9db8d8"
    const val BLANKET_FOLD = "#c5d6ea"
    const val PAPER = "#fdf9ef"
    const val SHADE = "#ffd9a0"
    const val PLATTER = "#d9dfe0"
    const val SOIL = "#8d6b55"
    const val LEAF = "#7fb58a"
    const val LEAF_LIGHT = "#9ccf9a"
    const val STRAP = "#8d6b55"
    const val LENS = "#bfe3ea"
    const val PACK = "#8fa98f"
    const val PACK_DARK = "#7c977d"
    const val AMBER = "#ffc47a"
    const val SPROUT_STEM = "#8ec79c"
    const val SPROUT_LEAF = "#a8dcb0"
    const val LANTERN = "#ffdf9a"
    const val LAVA_GLASS = "#f6c48e"
    const val LAVA_BLOB = "#e2725f"
    const val MUSHROOM = "#f2a48a"
    const val WIRE = "#55505f"
    const val CLOUD = "#fbf5ff"
    const val BLUSH = "#ff9fb4"
    val BULBS = listOf("#ffe2b0", "#ffd58a", "#ffc9a0", "#fff1c8")
}
