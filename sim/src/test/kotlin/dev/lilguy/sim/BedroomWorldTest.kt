package dev.lilguy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BedroomWorldTest {
    /** The shipped file gates nothing; the tier mechanism is tested on what used to be tier 1. */
    private val tier1 = setOf(
        "canvas", "easel", "record_player", "jazz_record", "ambient_record", "folk_record", "novel", "poetry_book",
        "field_guide", "plant", "poster", "rug", "painting", "paint", "read", "listen", "play_record",
        "study", "drawing_book", "music_book", "guitar", "play_guitar",
    )
    /** Books here teach nothing, so painting and the guitar are there from the start; [shipped] is the file as it is. */
    private val content = BedroomContent.default().let { c ->
        c.copy(
            items = c.items.map { if (it.id in tier1) it.copy(tier = 1) else it },
            verbs = c.verbs.map { if (it.id in tier1) it.copy(tier = 1) else it },
            books = c.books.map { it.copy(teaches = emptyList()) },
        )
    }
    private val shipped = BedroomContent.default()

    /** The engine tests run on everything in the file, gated or not; [granted] narrows it. */
    private fun world(
        money: Int = content.start.money,
        needs: NeedLevels = content.start.needs,
        items: Map<String, Int> = content.start.items,
        granted: Set<String> = content.gated,
        time: String = content.start.time,
    ) = BedroomWorld.create(content.copy(start = content.start.copy(money = money, needs = needs, items = items, time = time)), granted = granted)

    /** Submits [intent], steps until it ends, and returns its handle. */
    private fun BedroomWorld.run(intent: Intent, maxTicks: Int = 20_000): IntentHandle {
        val handle = submit(agentId, intent)
        repeat(maxTicks) {
            step()
            val s = handle.status
            if (s is IntentStatus.Succeeded || s is IntentStatus.Failed || s is IntentStatus.Interrupted) return handle
        }
        error("$intent still ${handle.status} after $maxTicks ticks")
    }

    private fun BedroomWorld.obs() = observe(agentId)

    private fun failure(handle: IntentHandle) = assertIs<IntentStatus.Failed>(handle.status).reason

    private fun BedroomWorld.activity(id: String) = obs().activities!!.first { it.id == id }

    @Test
    fun `starts with the plan's day 1`() {
        val obs = world().obs()
        assertEquals(ClockReading(1, "08:00"), obs.clock)
        assertEquals(20, obs.money)
        assertEquals(NeedLevels(80, 70, 60), obs.needs)
        assertEquals(mapOf("bed" to 1, "desk" to 1, "chair" to 1, "sketchbook" to 1, "meal" to 2), obs.owns)
        assertEquals(emptyList(), obs.holding)
        assertEquals(setOf("mail_hatch", "picture_rail", "bed", "desk", "chair"), obs.nearby.map { it.id }.toSet())
        assertTrue(obs.localMap.any { 'b' in it } && obs.localMap.none { 'e' in it }, "the easel slot is empty until bought")
    }

    @Test
    fun `tier 0 is the small world, and gated content comes in only when granted`() {
        val w = world(granted = emptySet())
        val obs = w.obs()
        assertEquals(listOf("eat", "sketch", "sell_art", "hang_art", "sleep", "think"), obs.activities!!.map { it.id })
        assertEquals(listOf("meal"), obs.shop!!.map { it.id })
        assertEquals("you have no sketch to sell; sketch makes some", w.activity("sell_art").locked)
        assertTrue("no activity called 'paint'" in failure(w.run(Intent.Do("paint"))))
        assertTrue("'easel' isn't for sale" in failure(w.run(Intent.Buy("easel"))))
        assertTrue("e easel" in w.mapLegend, "an empty slot still has its name")
        assertTrue("paint" in content.gated && "easel" in content.gated && "meal" !in content.gated)

        // A grant has to bring what it needs with it, and only gated ids can be granted.
        val painting = setOf("paint", "easel", "canvas", "painting")
        assertEquals(listOf("eat", "sketch", "paint", "sell_art", "hang_art", "sleep", "think"), world(money = 500, granted = painting).obs().activities!!.map { it.id })
        assertTrue("canvas" in assertFailsWith<IllegalArgumentException> { content.unlocked(setOf("paint", "easel")) }.message!!)
        assertTrue("meal" in assertFailsWith<IllegalArgumentException> { content.unlocked(setOf("meal")) }.message!!)
    }

    @Test
    fun `locked activities say why`() {
        val w = world()
        assertEquals("needs an easel ($17)", w.activity("paint").locked)
        assertEquals("you have no art to sell; sketch or paint makes some", w.activity("sell_art").locked)
        assertEquals("needs a book ($12)", w.activity("read").locked)
        assertEquals("needs a record player ($150)", w.activity("listen").locked)
        assertEquals("not bedtime yet; by day only a nap, when exhausted (energy 80)", w.activity("sleep").locked)
        assertNull(w.activity("sketch").locked)
        assertEquals("1h", w.activity("sketch").takes)
        assertEquals("1 sketch, sells for $3; art skill +0.1; energy -3; mood -4; stimulation +3; new to you: stimulation +8, mood +3", w.activity("sketch").gives)
        assertEquals("uses a meal (you have 2); fullness +45", w.activity("eat").gives)
        assertEquals("needs a record ($20)", w.activity("play_record").locked)

        assertEquals("can't paint: needs an easel ($17)", failure(w.run(Intent.Do("paint"))))
        assertTrue("no activity called 'dance'" in failure(w.run(Intent.Do("dance"))))
    }

    @Test
    fun `sketching takes an hour at the desk and makes a sketch`() {
        val w = world()
        val start = w.tick
        assertIs<IntentStatus.Succeeded>(w.run(Intent.Do("sketch")).status)
        val obs = w.obs()
        assertEquals(listOf("sketch"), obs.holding)
        val minutes = (w.tick - start) / w.clock.ticksPerMinute
        assertTrue(minutes in 60..75, "walk plus an hour, took $minutes min")
        val desk = obs.nearby.first { it.id == "desk" }
        assertTrue(desk.inReach)
        // Awake decay over the walk and the hour, plus the sketch's -3.
        assertTrue(obs.needs!!.energy in 72..73, "energy ${obs.needs!!.energy}")
        assertTrue(obs.recentEvents.last().endsWith("finished sketch: +1 sketch, art skill 0.1 (+0.1), energy -3, mood -4, stimulation +3, new to you: stimulation +8, mood +3"), obs.recentEvents.last())
        assertEquals("$3 for the 1 sketch you hold", w.activity("sell_art").gives)
    }

    @Test
    fun `selling art and buying go through the ledger`() {
        val w = world()
        w.run(Intent.Do("sketch"))
        w.run(Intent.Do("sketch"))
        assertIs<IntentStatus.Succeeded>(w.run(Intent.Do("sell_art")).status)
        assertEquals(26, w.ledger.balance)
        assertEquals(emptyList(), w.obs().holding)

        assertIs<IntentStatus.Succeeded>(w.run(Intent.Buy("meal")).status)
        assertEquals(2, w.ledger.balance)
        assertEquals(5, w.count("meal"))
        assertEquals(listOf(6, -24), w.ledger.transactions.map { it.amount })
        assertEquals(listOf("sold 2 sketches", "bought 3 meals"), w.ledger.transactions.map { it.reason })
        assertEquals(20 + w.ledger.transactions.sumOf { it.amount }, w.ledger.balance)
        assertTrue(w.obs().recentEvents.any { "bought 3 meals for $24 (money $2)" in it })
        assertEquals(w.ledger.transactions, w.snapshot().life!!.transactions)
    }

    @Test
    fun `the shop lists prices, affordability and what an item is for`() {
        val w = world(money = 10)
        val shop = w.obs().shop!!.associateBy { it.id }
        assertEquals(ShopOption("meal", 24, false, "3 meals; for eat"), shop["meal"])
        assertEquals(ShopOption("easel", 17, false, "for paint (3h: 1 painting, sells for $30; uses a canvas), which also needs a canvas ($15)"), shop["easel"])
        assertEquals("comfort +4: mood settles 4 higher; natural, calm", shop.getValue("plant").gives)
        assertEquals("the easel costs $17 and you have $10", failure(w.run(Intent.Buy("easel"))))
        assertTrue("isn't for sale" in failure(w.run(Intent.Buy("bed"))))

        val rich = world(money = 500)
        rich.run(Intent.Buy("easel"))
        assertEquals("you already have the easel", failure(rich.run(Intent.Buy("easel"))))
        assertTrue(rich.obs().shop!!.none { it.id == "easel" }, "one of a kind: gone from the shop once owned")
        assertEquals("needs a canvas ($15)", rich.activity("paint").locked)
        assertEquals("unlocks paint (3h: 1 painting, sells for $30; uses a canvas)", rich.obs().shop!!.first { it.id == "canvas" }.gives)
        rich.run(Intent.Buy("canvas"))
        assertIs<IntentStatus.Succeeded>(rich.run(Intent.Do("paint")).status)
        assertEquals(listOf("painting"), rich.obs().holding)
        assertEquals(0, rich.count("canvas"))
        rich.run(Intent.Do("sell_art"))
        assertEquals(500 - 17 - 15 + 30, rich.ledger.balance)
    }

    @Test
    fun `needs follow the hourly rules`() {
        val w = world()
        w.run(Intent.Wait(GridWorld.MAX_WAIT)) // 40 game minutes
        while (w.clock.reading(w.tick) != ClockReading(1, "09:00")) w.step()
        assertEquals(NeedLevels(76, 65, 57, 54), w.obs().needs)
        val reasons = w.needs.changes.map { it.need to it.reason }.toSet()
        assertEquals(setOf(Need.Energy to "awake", Need.Stimulation to "awake", Need.Fullness to "hunger", Need.Mood to "drift toward 50"), reasons)
        val sums = w.needs.changes.groupBy { it.need }.mapValues { (_, c) -> c.sumOf { it.delta } }
        assertEquals(-4.0, sums.getValue(Need.Energy), 1e-6)
        assertEquals(-5.0, sums.getValue(Need.Fullness), 1e-6)
    }

    @Test
    fun `low energy or fullness pulls mood down and blocks work`() {
        val w = world(needs = NeedLevels(energy = 4, fullness = 50, mood = 50))
        assertEquals("too tired to work (energy 4)", w.activity("sketch").locked)
        assertEquals("can't sketch: too tired to work (energy 4)", failure(w.run(Intent.Do("sketch"))))
        repeat(w.clock.ticks(60)) { w.step() }
        // Low energy: mood falls 5 an hour instead of drifting.
        assertTrue(w.obs().needs!!.mood in 44..45, "mood ${w.obs().needs!!.mood}")

        val hungry = world(needs = NeedLevels(energy = 60, fullness = 3, mood = 50))
        assertEquals("too hungry to work (fullness 3)", hungry.activity("sketch").locked)
        assertNull(hungry.activity("eat").locked)
        hungry.run(Intent.Do("eat"))
        assertTrue(hungry.obs().needs!!.fullness >= 45)
        assertEquals(1, hungry.count("meal"))
    }

    @Test
    fun `at night sleep lasts until 07 00 or full energy, by day it's a short nap when exhausted`() {
        // By day: refused until he's exhausted, then a nap of at most 90 minutes, not until 07:00.
        assertEquals("not bedtime yet; by day only a nap, when exhausted (energy 30)", world(needs = NeedLevels(energy = 30, fullness = 90, mood = 50)).activity("sleep").locked)
        val nap = world(needs = NeedLevels(energy = 20, fullness = 90, mood = 50))
        assertEquals("up to 1h 30m or energy 100, or fullness below 10, about 1h 30m", nap.activity("sleep").takes)
        nap.run(Intent.Do("sleep"))
        assertTrue(nap.clock.reading(nap.tick).time in "09:30".."09:40", "woke at ${nap.clock.reading(nap.tick).time}")
        assertTrue(nap.obs().needs!!.energy in 36..38, "18 energy from the nap, less the walk to bed: ${nap.obs().needs!!.energy}")

        // At night: until full energy, or 07:00.
        val w = world(needs = NeedLevels(energy = 30, fullness = 90, mood = 50), time = "21:00")
        assertEquals("until 07:00 or energy 100, or fullness below 10, about 5h 50m", w.activity("sleep").takes)
        w.run(Intent.Do("sleep"))
        assertEquals(100, w.obs().needs!!.energy)
        val woke = w.clock.reading(w.tick)
        assertEquals(2, woke.day)
        assertTrue(woke.time in "02:45".."03:05", "woke at ${woke.time}")
        assertEquals("not tired (energy 100)", w.activity("sleep").locked)

        // Going to bed at 03:00 with energy 24, he wakes at 07:00 before reaching 100.
        val late = world(needs = NeedLevels(energy = 100, fullness = 100, mood = 50))
        while (late.clock.reading(late.tick) != ClockReading(2, "02:00")) late.step()
        late.run(Intent.Do("eat")) // or hunger would wake him first
        while (late.clock.reading(late.tick) != ClockReading(2, "03:00")) late.step()
        assertEquals(24, late.obs().needs!!.energy)
        late.run(Intent.Do("sleep"))
        assertEquals(ClockReading(2, "07:00"), late.clock.reading(late.tick))
        assertTrue(late.obs().needs!!.energy in 66..72, "energy ${late.obs().needs!!.energy}")
    }

    @Test
    fun `hunger wakes him up`() {
        val w = world(needs = NeedLevels(energy = 10, fullness = 20, mood = 50), time = "22:00")
        w.run(Intent.Do("sleep"))
        assertEquals(9, w.obs().needs!!.fullness)
        assertTrue(w.obs().needs!!.energy < 100)
        assertTrue(w.obs().recentEvents.last().endsWith("stopped early: fullness 9"), w.obs().recentEvents.last())
    }

    @Test
    fun `a record on the player plays music in the background`() {
        val w = world(money = 500)
        w.run(Intent.Buy("record_player"))
        assertEquals("needs a record ($20)", w.activity("listen").locked)
        w.run(Intent.Buy("jazz_record"))
        assertEquals(
            ActivityOption("play_record", "jazz_record", "record_player", "1 min", "you like it: mood +2; music for 45 min: mood +2/h, stimulation +6/h; new to you: stimulation +8, mood +3"),
            w.activity("play_record"),
        )
        assertTrue("do(verb, what, on)" in failure(w.run(Intent.Use("jazz_record", on = "record_player"))))
        assertTrue("isn't one" in failure(w.run(Intent.Do("play_record", what = "bed"))))
        assertIs<IntentStatus.Succeeded>(w.run(Intent.Do("play_record", what = "jazz_record")).status)
        assertTrue(w.obs().music!!.startsWith("jazz_record, 4"), w.obs().music)
        assertEquals("playing jazz_record", w.obs().nearby.first { it.id == "record_player" }.state)

        // Music plays while he does something else, and its mood shows in the need log.
        w.run(Intent.Do("sketch"))
        assertNull(w.obs().music)
        while (w.clock.minuteOfDay(w.tick) % 60 != 0) w.step()
        assertTrue(w.needs.changes.any { it.reason == "music" && it.delta > 0 })
        assertTrue(w.obs().recentEvents.any { "the jazz record finished" in it } || w.snapshot().events.any { "the jazz record finished" in it.text })
    }

    @Test
    fun `decor raises the mood baseline`() {
        val w = world(money = 500)
        assertEquals(50.0, w.snapshot().life!!.moodTarget)
        w.run(Intent.Buy("rug"))
        w.run(Intent.Buy("plant"))
        assertEquals(60.0, w.snapshot().life!!.moodTarget)
        assertTrue(w.obs().nearby.any { it.id == "rug" } && w.obs().localMap.any { 'r' in it })
        // The plant blocks its tile; the rug doesn't.
        val plant = w.obs().nearby.first { it.id == "plant" }
        assertTrue(!w.passable(plant.x, plant.y))
        val rug = w.obs().nearby.first { it.id == "rug" }
        assertTrue(w.passable(rug.x, rug.y))
    }

    @Test
    fun `the first time pays more, repeating pays less, and boredom lowers the mood he settles to`() {
        val w = world()
        w.run(Intent.Do("sketch"))
        assertTrue(w.obs().recentEvents.last().endsWith("new to you: stimulation +8, mood +3"), w.obs().recentEvents.last())
        assertTrue("done a lot lately: gains at 80%" in w.activity("sketch").gives!!, w.activity("sketch").gives)
        w.run(Intent.Do("sketch"))
        val again = w.obs().recentEvents.last()
        assertTrue("new to you" !in again && "done a lot lately, so it paid 80%, stimulation -1" in again, again)
        // Eating is routine: never new, never worn.
        w.run(Intent.Do("eat"))
        assertTrue(w.obs().recentEvents.last().let { "finished eat: used 1 meal, fullness +" in it && "new" !in it && "lately" !in it }, w.obs().recentEvents.last())

        assertEquals(34.0, world(needs = NeedLevels(80, 70, 50, stimulation = 0)).snapshot().life!!.moodTarget)
    }

    @Test
    fun `content keeps to the vocabulary and each verb's contract, and his affinities set what things are worth`() {
        fun broken(c: BedroomContent) = assertFailsWith<IllegalArgumentException> { c.unlocked(c.gated) }.message!!
        fun withItem(id: String, change: (ItemSpec) -> ItemSpec) = content.copy(items = content.items.map { if (it.id == id) change(it) else it })
        fun withActivity(id: String, change: (VerbSpec) -> VerbSpec) = content.copy(verbs = content.verbs.map { if (it.id == id) change(it) else it })
        assertTrue("isn't in vocabulary.qualities" in broken(withItem("rug") { it.copy(qualities = mapOf("shiny" to 1.0)) }))
        assertTrue("isn't in vocabulary.affords" in broken(withItem("novel") { it.copy(affords = mapOf("edible?" to AffordanceParams())) }))
        assertEquals("item sketch, sellable: needs price from 1 to 10000", broken(withItem("sketch") { it.copy(affords = it.affords + ("sellable" to AffordanceParams())) }))
        assertEquals("verb read: a generative verb needs produce; a generative verb can't progress; a generative verb can't enjoy; a generative verb can't write", broken(withActivity("read") { it.copy(kind = VerbKind.Generative) }))
        assertEquals("verb listen: a consumptive verb needs an affordance in requires, args or a consume", broken(withActivity("listen") { it.copy(args = mapOf("what" to "jazz_record"), effects = it.effects.take(1)) }))
        // Kinds list what they allow: an op no kind lists for it is refused.
        assertEquals("verb think: an expressive verb can't produce", broken(withActivity("think") { it.copy(effects = it.effects + ProduceOp(mapOf("sketch" to 1))) }))

        val fond = content.withPersonality(content.personality.copy(affinities = content.personality.affinities + mapOf("cozy" to 2.0, "lively" to -1.0)))
        val w = BedroomWorld.create(fond.copy(start = fond.start.copy(money = 500)), granted = content.gated)
        assertEquals("comfort +9: mood settles 9 higher; cozy, soft, warm", w.obs().shop!!.first { it.id == "rug" }.gives)
        w.run(Intent.Buy("record_player"))
        w.run(Intent.Buy("jazz_record"))
        assertTrue(w.activity("play_record").gives!!.startsWith("you don't like it: mood -1;"), w.activity("play_record").gives)
    }

    @Test
    fun `a book is read a chapter a session, studying raises its skill, and finishing it teaches what it hid`() {
        val w = BedroomWorld.create(shipped.copy(start = shipped.start.copy(money = 500)))
        assertTrue(w.obs().activities!!.none { it.id == "paint" } && w.obs().shop!!.none { it.id == "easel" }, "painting is unknown until learned")
        assertTrue("isn't for sale" in failure(w.run(Intent.Buy("easel"))))
        assertEquals(10, w.obs().needs!!.frustration, "a failure frustrates him")
        assertTrue("finishing it teaches you to paint, and about the easel and the canvas" in w.obs().shop!!.first { it.id == "drawing_book" }.gives)
        // Screens see the whole catalog and every verb, hidden ones with the book that teaches them.
        val life = w.snapshot().life!!
        assertEquals("the drawing book, \"Drawing What You See\"", life.catalog.first { it.id == "easel" }.taughtBy)
        assertEquals("the drawing book, \"Drawing What You See\"", life.verbs.first { it.id == "paint" }.taughtBy)

        w.run(Intent.Buy("drawing_book"))
        assertTrue("chapter 1 of 4 of \"Drawing What You See\"; art skill +0.5" in w.activity("study").gives!!, w.activity("study").gives)
        repeat(3) { assertIs<IntentStatus.Succeeded>(w.run(Intent.Do("study", what = "drawing_book")).status) }
        assertEquals(1.5, w.obs().skills!!["art"])
        assertEquals(3, w.snapshot().life!!.done["study"]?.count)
        assertEquals(BookView("drawing_book", "Drawing What You See", 3, 4, listOf("art"), "to paint, and about the easel and the canvas", shipped.book("drawing_book")!!.chapters[3]), w.obs().books!!.single())
        w.run(Intent.Do("read", what = "drawing_book"))
        val done = w.obs().recentEvents.last()
        assertTrue("chapter 4 of 4" in done && "finished \"Drawing What You See\", you learned to paint, and about the easel and the canvas" in done, done)
        assertEquals(1.5, w.obs().skills!!["art"], "reading is for the story; studying raises the skill")

        assertEquals("you've finished \"Drawing What You See\"", w.activity("study").locked)
        assertTrue("rereads" in w.activity("read").gives!!)
        // Painting is in his world now, and his art skill raises what a piece sells for: $30 at 1.5 is $37.
        assertEquals("needs an easel ($17)", w.activity("paint").locked)
        assertEquals("for paint (3h: 1 painting, sells for $37; uses a canvas), which also needs a canvas ($15)", w.obs().shop!!.first { it.id == "easel" }.gives)
        assertTrue(w.knownContent().verb("paint") != null && shipped.known(emptySet()).verb("paint") == null)
        assertEquals(null, w.snapshot().life!!.catalog.first { it.id == "easel" }.taughtBy)
    }

    @Test
    fun `pick_up does nothing here but other intents still work`() {
        val w = world()
        assertTrue("nothing to pick up" in failure(w.run(Intent.PickUp("bed"))))
        assertIs<IntentStatus.Succeeded>(w.run(Intent.MoveTo(Target.Entity("bed"))).status)
        assertTrue(w.obs().nearby.first { it.id == "bed" }.distance < 0.1f)
        assertIs<IntentStatus.Succeeded>(w.run(Intent.Say("morning")).status)
        assertTrue("wall or furniture" in failure(w.run(Intent.MoveTo(Target.Tile(0, 0)))))
    }

    @Test
    fun `the key-and-door room rejects the new intents`() {
        val room = RoomWorld.create()
        val handle = room.submit(room.agentId, Intent.Do("sketch"))
        room.step()
        assertEquals("there are no activities in this room", failure(handle))
        assertNull(room.observe(room.agentId).clock)
    }
}
