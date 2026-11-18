# How your world works

The hand-written game guide: how each part of the world works, in plain words, for what lives in
code or across several content files. He reads it himself (the `search_docs` and `read_docs` looks,
agent/.../BedroomTools.kt) and the oracle answers from it (agent/.../memory/Oracle.kt, WorldDocs).

Format: each `## id: title` heading starts one section. A heading may end with `[when: a, b]`: the
section is in the docs only once every one of those verb or item ids is in his world (a book can
teach them), so the guide never tells him what he hasn't found yet. Write to him as "you". Keep
numbers out: prices, rates, thresholds and rewards are in the generated sections (`rules:...`,
`verb:...`, `item:...`, `book:...`), named at a section's end, so they can't drift.

## guide:start: how to use these docs, and the shape of a day
These docs are how your world works. Search them with a few words (search_docs), or with no words
for the list of every section, then open the ones you need (read_docs). The `guide:` sections say
how things work in words; the `rules:` sections hold the numbers; there is a `verb:` section for each
thing you can do, an `item:` section for each thing you can own, and a `book:` section for each book.
A day goes like this: your needs fall as time passes, so you eat, sleep and keep yourself from
getting bored; you earn money by making and selling things; and you spend it on food, materials and
things that make your life better. Everything you do takes game time, and walking takes a game minute
a tile. You can only do what a verb does (guide:no-verb).

## guide:needs: your five needs and what moves them
You have five needs, each from 0 to 100. Energy falls while you're awake and comes back while you
sleep. Fullness falls all the time, more slowly asleep, and comes back when you eat. Stimulation is
how interested you are: it drains while you're awake, faster the higher it is, and comes back from
doing things that are new to you, hobbies and making things (guide:fun). Frustration rises when
something you try fails and fades with time and sleep (guide:frustration). Mood is the summary: it
drifts toward a level set by your comfort, your boredom and your frustration (guide:mood). When
energy or fullness is low, mood falls instead of drifting. Below the work floor you're too tired or
too hungry to work (a verb marked `work`, such as sketching). Numbers: rules:needs.

## guide:mood: what sets your mood
Your mood drifts each hour toward a level: the baseline plus the comfort of what you can see
(guide:comfort), less a little for each point you're bored below the boredom line, less a little for
each point of frustration above its line. Activities also add mood when they finish (reading, music,
watching visitors, playing guitar), more the more skilled you are at them and the fresher they are to
you. Being low on energy or fullness pulls mood down every hour until you eat or rest. Making art
costs a little mood, and below a certain mood you're too low to make art or invent. Numbers:
rules:needs, rules:frustration, rules:skills.

## guide:night: night, day, sleep and naps
Night runs from evening to the small hours (rules:night). Go to bed at night and you sleep until
morning or until your energy is full, whichever comes first; you wake early if you get too hungry. By
day sleep is only a short nap, and only when you're exhausted. Sleep also eases frustration. Sleep is
spoiled where your bed is cold or noisy: your energy then comes back more slowly. A heater near the bed
keeps a cold night out, and music playing loud nearby spoils your rest (guide:warmth, guide:sound). At night
it's dark, and you can't make out what isn't lit (guide:light). Numbers: rules:night, rules:sleep,
verb:sleep.

## guide:food: eating, meals and the oats
Eating a meal at your desk fills you up, and a food you like (its qualities) also lifts your mood
while you eat it. Meals are bought from the shop, a pack at a time. The oats in the cupboard never run
out and cost nothing, so you can never be left with no way to eat, but you can only eat them when
you're quite hungry, they fill you less than a meal, and a diet of only oats wears thin and bores you.
What's left of a meal is a food scrap, which a compost bin can turn into compost (guide:machines). Food you
invent can be reordered from the shop. Numbers: verb:eat, verb:eat_oats, item:meal.

## guide:money: earning and spending
You start with a little money. You earn it by selling: your art at the mail hatch (selling sells
every piece you're carrying at once); what your machines make, such as pots from a kiln (guide:wares); samples you
collected outdoors; and materials you don't need, back to the shop for about half what they cost.
You spend it on shopping trips (guide:shopping): food, books, records, furniture, materials, plans
for new rooms, and machines. Art keeps you fed; machines are how you get ahead. Money can't come from
nothing: no verb and no code makes it except by selling something. Numbers: item:sketch,
item:painting, rules:market, rules:invent.

## guide:shopping: shopping trips and what the shop sells
You buy things on a shopping trip at the mail hatch: you look through the whole catalog, what you can
afford and what you can't, and choose up to a few things to buy, plus something you're saving for if
you like. You pay for each as the trip finishes, and it's checked like any purchase: you can't buy
what you can't afford. You can't go on two trips in a row. The shop holds back things that are no use
to you yet: a thing whose every use happens at something you don't own (a canvas without an easel, a
record without a record player) isn't sold to you until you own that thing; the thing it needs is
always sold, so there's always a way in. Things a book teaches aren't in the catalog until you've
finished that book. Numbers: rules:shop, verb:shop.

## guide:making-art: how a piece of art gets made
When you make a piece (sketching at your desk, or painting at an easel), you draw it yourself, one
choice at a time. First you pick its subject, from a few of your own; one you've never drawn
is new to you. Then you pick a box of crayons, then each stroke: the background, the
subject's shape, then details. Its title comes from the subject you picked. You can't choose what to
draw before you start; you choose while drawing. You need light to draw by, and you're too low to make
art when your mood is very low. The better your art skill, the more strokes you get and the more the
piece sells for (guide:skills). Numbers: verb:sketch, item:sketch.

## guide:painting: painting at the easel [when: paint]
Painting is sketching's bigger sibling: it's done at an easel, uses up a canvas each time, takes
longer and gives you a bigger canvas and more strokes, so a painting sells for more than a sketch and
practises art faster. Numbers: verb:paint, item:painting, item:canvas, item:easel.

## guide:hanging: hanging art on the wall
Any piece you made, a sketch or a painting, can be hung on the picture rail instead of sold. Every
hung piece raises your comfort by the same amount while you can see it (near, and lit), whatever it
shows, up to the gallery's limit. Nothing takes a hung piece down, so it's never sold. Numbers:
rules:gallery.

## guide:fun: what's fun: new things, wearing thin and boredom
Stimulation comes from doing things that are unfamiliar, judged by what they're like, not what
they're called: the verb, the kind of verb, the place, the thing you do it with, that thing's group
and qualities, the skill it practises, what it makes, and the sky when you're outdoors. Each time you
meet one of these it gets more familiar, and familiarity fades by half every few days. What you do
pays its mood and stimulation at how fresh it is, a fresh finish pays a little extra, and a stale one
drains stimulation a little. So the same thing every day stays familiar, and a fourth warm lamp is
hardly new: to have fun, change what you do, where you do it and what you do it with. Upkeep (eating,
sleeping, selling, hanging, shopping) doesn't count either way. When you're bored, what you've done a lot
in the last few days is refused until you're less bored or a day or two has passed since you last did
it (so a daily habit waits, but every other day is fine); what feeds you or rests you never is, nor
anything you've never done, and work stays open while you need money for food. A new thing for your
room interests you for a few days, while you can see it. Your status says what you've done most lately
and what you haven't done for days or ever. Numbers: rules:familiarity, rules:novelty,
rules:habituation, rules:needs.

## guide:frustration: frustration
Frustration rises each time something you try fails: it's refused, or cut short because what it
needed was gone, or a design you made isn't built. Finishing anything that isn't routine eases it a
little, and it fades by the hour, faster while you sleep. Above a line, it pulls down the mood you
settle to. So trying things you can't do yet costs you: check why a thing is locked before you try
it. Numbers: rules:frustration.

## guide:comfort: comfort: what you can see from where you stand
Comfort is what you can see from where you stand: each thing in the place you're in counts in full
close by and less further off, up to a limit, and in full only when it's well lit, less in dim light,
not at all in the dark. What a thing is worth to you is its qualities times how much you like each
(your affinities). Hung art counts where it hangs, your collection of samples where its shelf is, a
room's own qualities while you're in it, and something nice you can hear (a wind chime) as if you saw
it. Asleep, what's round your bed counts, lit or not. You get used to what you have: a thing's comfort
falls over a couple of weeks to part of what it was, so a room of old things won't keep your mood at
the top. Where you stand and what's lit matter: a lamp beside your favourite things makes them count.
Numbers: rules:comfort, rules:qualities, rules:collection, rules:gallery.

## guide:light: light: daylight, windows, lamps and the dark
Light runs from 0 (black) to about 10 (noon outdoors). Outdoors it's daylight, by the time of day and
the sky. Indoors a little of the daylight reaches everywhere, and more comes in through the windows,
reaching a few tiles in. Things that give light (lamps, the desk's little lamp, glowing things) light
round them; walls and trees block light, and a light in a room lights only that room. You carry a
small light of your own, enough to see what's right beside you, but it doesn't count for what a verb
needs. Some verbs need light where they're done (reading, sketching, inventing, exploring): they're
locked there until it's light enough, and of the places they can be done you go to the nearest lit
one. At night you only see what's lit; things you know are there but can't see are remembered, not
seen. A lamp you can switch runs on a timer, coming on in the evening and going off at night, unless
you switch it on or off yourself; then it stays that way until you switch it again or put it back on
its timer. Numbers: rules:light.

## guide:warmth: warmth: the cold, heaters, fires and blankets
Warmth is degrees where you stand. Outdoors it's the weather's temperature. The house keeps its own
warmth but loses some of the difference to outside. A heater or a fire pit warms round it, fading a
little way off; walls stop it, and a heater in a room warms only that room. A blanket you own keeps
you warm anywhere outdoors. Where it's too cold, your mood and energy fall each hour, some verbs
(exploring) are locked, and sleep there doesn't rest you as well. Numbers: rules:warmth, rules:sleep.

## guide:sound: sound and music
Sound runs from 0 to 10. Music starts when you put a record on the record player; it plays for a
while in the background, lifting your mood and interest as you do other things, as long as you can
hear it. Walls let some sound through and it carries out of a room, so a record in the bedroom is faint
in the garden. Listening sits you down with whatever is playing, so it's only there while music has
time left. A loud place keeps you from sleeping well and keeps birds away. Each record is its own,
so a new record is fresh even when the player isn't. Numbers: rules:sound, verb:play_record,
verb:listen.

## guide:guitar: playing the guitar [when: play_guitar]
Playing the guitar in your chair practises music and lifts your mood and interest; the more music
skill you have, the more it pays. Numbers: verb:play_guitar.

## guide:reading: books and what they teach
You read a book a chapter a session, in bed or in your chair, where there's light enough to read. Each
new chapter raises the book's skills and you remember what happened in it. Finishing the last chapter
of some books teaches you things that weren't in your world before: new verbs and the things they use,
which then appear in the shop. A book's section says what it teaches. Reading wears as a whole, so a
pile of new books isn't a pile of fresh hobbies; a book's own qualities still pay as you read it.
Numbers: verb:read, and each book:... section.

## guide:skills: skills: art, music, nature and tinkering
Skills start at 0 and have no cap. Practising raises them: art by sketching and painting, music by
playing guitar, nature by exploring, watching visitors and studying samples, tinkering by mending and
taking apart machines; reading raises the skills of the book. Every skill makes the verbs that practise
it pay more mood. Art skill also makes your art sell for more and gives you more strokes to draw with.
Tinkering makes each mend take off more wear. Nature and music deepen how much you like natural and
lively things, so what you own of those comforts you more. Numbers: rules:skills.

## guide:outdoors: outdoors: places, exploring and collecting
Outside your bedroom's front door is the land: the grass round the house, the garden, the forest, the
clearing in it and the pond. One tile is one metre and a game minute's walk. Go to a place just to be
there, or let a verb walk you where it's done. Each place has a lookout you explore it from; exploring
is a pleasure in itself, practises nature and reveals the patches where things grow there, which are
hidden until then. Collecting takes a sample from a patch you've found, and the patch is bare for a
while after, growing back faster in the rain. Exploring needs daylight and warmth; collecting needs
light. Numbers: verb:explore, verb:collect, world:visitors.

## guide:samples: samples and your collection
Samples you collect outdoors, and what visitors leave behind (feathers, moths, fireflies), are kept: each kind you have adds comfort
from its shelf in your room, up to a few kinds. Studied under a microscope, a sample practises nature.
Sold at the hatch, it fetches its price; selling art leaves your samples alone. Numbers:
rules:collection, verb:study_sample, verb:sell_sample.

## guide:weather: the weather
The sky changes once a game hour: clear, cloud, rain or fog. Rain comes out of cloud, so a clear spell
clouds over first, and rain lasts a few hours. Fog forms in the small hours and burns off by
mid-morning. It's coldest before dawn and warmest in the afternoon, and a clear night is colder. You
see the sky now, and feel how warm it is when you're out in it, but not what it did while you weren't
looking or what's coming. A weather station logs the weather where it stands every hour, and you read
the last day of it standing at it: it's the one way to know what happened while you were away, and the
air pressure it logs falls for a few hours before rain, the one sign of rain coming. Numbers:
verb:check_station, item:weather_station.

## guide:visitors: visitors: birds, moths and fireflies
Visitors come to things that draw them (a bird feeder, a lamp left lit, the reeds at the pond), when
the conditions there suit them: the time of day, the light, the warmth, the quiet, the rain. So where
you put such a thing decides who comes and when. Watching them there takes only the time, practises
nature, and they leave something behind for your collection, then stay away a while. A weather
station near a lure logs how many came each hour. Numbers: world:visitors, verb:watch.

## guide:growing: plants and planters
A planter grows toward its harvest for each hour its soil is damp and the sun on it is strong enough:
daylight and the light through the windows count, lamps don't. So put it by a window or outdoors on a
clear day, not in a dim corner. Soil dries out by the hour; rain wets it outdoors, and indoors only
watering keeps it damp (you need a watering can). When it's grown, harvest it. Numbers:
rules:growing, verb:water_plant, verb:harvest.

## guide:machines: machines: feeding, emptying, wear and mending
A machine runs a recipe: a batch takes its inputs and, after some hours of working, makes its outputs.
Nothing comes from nothing. A machine works only while it has what a batch takes, room for what it
makes, and what it needs where it stands (a raised bed grows by daylight). A machine takes what it
needs from what another machine nearby in the same place made; anything else you carry over yourself:
tending fills a machine with what it takes that you have and takes out what it made. The compost bin
turns food scraps into compost, and the raised bed beside it grows cherry tomatoes from the compost.
Every batch wears a machine; past halfway it works slower, and when fully worn it jams and stops until
you mend it with a spare part (you can mend one once it's a
little worn). Mending practises tinkering, so each mend takes off more. Anything of
yours with code is a machine too and wears as its code runs. Numbers: rules:machines, verb:tend,
verb:repair.

## guide:wares: the kiln, wares and the market
A kiln fires clay you buy into pots that sell at the hatch for far more than the clay cost: it's how you
get ahead, as long as you keep it fed with clay, empty it and mend it. What machines make to sell
(wares) sells for less the more of one kind you sell: each one sold lowers what the next fetches, never
below a floor, and the price climbs back day by day. So a second kiln earns less than the first, and a
new kind of ware pays. Art and samples aren't wares. Numbers: rules:market, rules:machines, item:kiln,
item:clay_pot.

## guide:inventing: inventing at the workbench
At the workbench you design one new thing of your own and build it, once a day: what it's for (one
use: to look at, to play as a record, to read as a book, to eat, or to sell as a ware), up to a few
qualities, and a look made of shapes. You never set its price: a thing to look at, play or sell is
built from materials you buy (wood, metal, copper wire, glass), a part of its look taking a unit of its
material for its size and its main material more for each point of quality; food and books cost dollars
instead. Code in a thing needs copper wire for its wiring, and code that moves a part or runs a recipe
needs metal for a mechanism. The build takes the materials from what you have; without them nothing is
built. A design outside the rules isn't built, costs only the time, and frustrates you. A thing to look
at takes one of the room's few spare spots, else the free spot nearest the workbench. A thing made to
sell sells once. You can take apart a thing you invented for half its materials back, which frees its
spot; materials you don't need sell back to the shop for about half what they cost. Numbers:
rules:invent, verb:invent, verb:dismantle, verb:sell_materials.

## guide:code: writing code: your own verbs and what your things do
At your desk you can write code: new verbs of your own, and what your things do by themselves. A verb
is a function that runs when you finish it (and one that says why it can't be done); a thing's
behavior is functions run while you own it, at certain moments (each game hour, every so many minutes,
when you arrive or leave, finish a verb, sleep or wake, or on a timer). A thing's code reaches only its own place, and what it does where you aren't you hear
of when you next go there. Code makes nothing from nothing: money comes from selling things, things
from machines fed with what their recipes take, and code that runs wears the thing it's in. Code that
throws an error fails the verb with that error. Naming one of your verbs rewrites it. The world API
your code can use is in rules:code. Numbers: rules:code, verb:devise.

## guide:rooms: building rooms onto the house
The house can grow. A room's plan is bought on a shopping trip; then you build it on a site next to the
house, which takes a few hours' work. A room comes with its own furniture, and its qualities count
toward your comfort while you're in it. Some sites need another room to stand first. Building a plan
that already stands moves it to the new site. Numbers: verb:build_room, item:lab_plan,
item:sunroom_plan.

## guide:placing: moving your things
You can put one of your things somewhere else, indoors or out, in any free spot: you walk to it, carry
it there and set it down, which takes a few minutes besides the walks. Some things only go in a wall
spot. Where a thing stands matters: comfort counts what you can see lit from where you stand, a planter
needs sun, a lure decides where visitors come, a heater warms only round it, and a weather station logs
the weather where it is.

## guide:letters: letters
People outside your world can write to you; their letters wait at the mail hatch, and you open one at
a time. You can write back to someone who has written to you, a letter at your desk that goes through
the hatch when you finish: at most one a day to each person, and only a couple in a row before they
write back. Letters are routine, so they never wear thin. Numbers: rules:letters, verb:read_mail,
verb:write_letter.

## guide:looking: looking, remembering and finding out
Looking takes no game time. Look around to see where you are and what's round you. Recall searches your
memories. Ask, when it's offered, puts a question to someone who knows how the world works. These docs
(search_docs, read_docs) are how the world works, written down: read them yourself when you want the
detail. Search, when it's offered, looks things up on the real internet, outside your world; it doesn't
change anything in your room.

## guide:discovery: what you can't see yet
The shop only sells what you could use now; something that's no use without another thing appears once
you own that thing. Books can teach you verbs and things you don't know yet; a book's section names what
it teaches, and finishing the book shows them to you. Patches outdoors are hidden until you explore
their place. Locked verbs are listed with why they're locked: the reason says what's missing.

## guide:no-verb: what you can't do
You can only do what a verb does. If no verb does something (take art down, cook, leave the land), you
can't do it, unless you write a verb of your own for it in code (guide:code), and even then code can't
make money or things from nothing.
