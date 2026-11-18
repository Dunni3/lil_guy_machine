"""World sketches for the lil guy: a throwaway Flask page of canvas mock-ups.

Run: uv run --with flask python world_sketches/app.py  (then open http://127.0.0.1:5057)
"""

from flask import Flask, render_template

app = Flask(__name__)

SECTIONS = [
    {
        "title": "Round two: one house, one world",
        "intro": "Your picks, joined up. Whatever you build in the first sketch stands in the second, because both draw the same room data.",
        "sketches": [
            {
                "id": "snapon",
                "title": "Snap-on dioramas",
                "wide": True,
                "tags": ["isometric", "clay", "snap-on rooms"],
                "palette": ["#f9cfdc", "#ffe9b8", "#cdbaf0", "#b5e5cd", "#b8d7f4", "#3a3866"],
                "blurb": "One room at a time, big enough to decorate. The room you're in fills the frame and "
                         "its neighbours flatten into floor plans with door labels, so rooms never tangle. "
                         "Whole house flattens everything into a plan for choosing where the next room snaps on.",
                "ideas": [
                    "Furniture sits on a tile grid in each room, which suits a closed choice: which item, which open tile, and why.",
                    "Rooms and furniture are lists of boxes, so they could live in content YAML like items do.",
                    "The view is one idea: a height per room, animated. On the TV it maps onto the D-pad: move between rooms, or pull back to the plan.",
                ],
                "try": "Pick furniture and click a tile; click its card again to turn it or put it down. With nothing held, click furniture to pick it up. Door labels change rooms, and Whole house adds them.",
            },
            {
                "id": "world3d",
                "kind": "world3d",
                "title": "A real 3D world",
                "wide": True,
                "tags": ["three.js", "heightfield", "bent horizon", "day and night"],
                "palette": ["#bfe3b4", "#efcfa8", "#4f9a6c", "#8fd0e6", "#f8bfd0", "#ffe3a1"],
                "blurb": "A walkable valley with a town, a lake, a hill, woods and an orchard. The slider bends "
                         "the same land from flat to a small globe under an ordinary sky, so you can keep "
                         "the round-world feel with no space in it. Keep walking and you come back around. "
                         "Go inside and you get the same big isometric rooms you built, furniture included.",
                "ideas": [
                    "The wire format can stay as it is: models already say do(verb) and move_to(entity), and the sim does the walking. The line at the bottom shows each one.",
                    "The land is a height per tile, so the sim stays a grid with A*. It only needs one height lookup and a list of places.",
                    "The bend is one vertex shader trick, and it also hides how little world there is: only what's near him needs drawing.",
                ],
                "try": "Click the ground to walk, drag to look around, or use WASD after clicking in. Scroll zooms, so try flat, then globe, zoomed out.",
            },
        ],
    },
    {
        "title": "Rooms he can buy",
        "intro": "Three ways his place could grow past the bedroom.",
        "sketches": [
            {
                "id": "dollhouse",
                "title": "Crayon dollhouse",
                "tags": ["crayon", "cutaway", "buy a room"],
                "palette": ["#f8bfd0", "#b5e5cd", "#ffd8c0", "#cdbaf0", "#d6ecf7", "#3d3a66"],
                "blurb": "Today's bedroom, pulled back into a whole-house cutaway. Rooms he hasn't bought "
                         "are pencil sketches with a price tag. Buying one colors it in with a crayon wipe, "
                         "and he climbs the ladder to try it out.",
                "ideas": [
                    "Rooms are content: a rooms.yaml with price, tier and the verbs each one opens "
                    "(kitchen: cook, studio: paint big, attic: stargaze).",
                    "Same camera as the 3D cutaway now, just pulled back: zoom to his room while he's busy, "
                    "the whole house when he's idle.",
                    "The pencil rooms are visible goals. 'Saving for the studio' is something his self-model can say.",
                ],
                "try": "Click a price tag once he can afford it. He earns as you watch.",
            },
            {
                "id": "dioramas",
                "title": "Pocket dioramas",
                "tags": ["isometric", "clay", "floating"],
                "palette": ["#f8bfd0", "#cdbaf0", "#b5e5cd", "#ffe3a1", "#3a3866", "#b8d7f4"],
                "blurb": "Each room is its own floating diorama, like a shelf of terrariums, and he hops "
                         "between them. A new room pops in as its own island, so there's no house layout to "
                         "solve and each room can have a completely different theme.",
                "ideas": [
                    "Rooms with no adjacency rules are cheap to author and easy to grow to twenty.",
                    "Clay look: flat pastel faces, three tones per box, no textures, so it should suit the TV's weak GPU (untested).",
                    "Themed rooms with a need attached: the bath eases frustration, the observatory feeds stimulation.",
                ],
                "try": "Click a dashed slot to add a room.",
            },
            {
                "id": "catalog",
                "title": "Rooms by mail (riso)",
                "tags": ["risograph", "floor plan", "two inks"],
                "palette": ["#ff48b0", "#0078bf", "#ffe800", "#f6f0e2"],
                "blurb": "A mail-order catalog printed in two riso inks. Rooms are snap-together modules with "
                         "real footprints. Ordering one stamps it onto his floor plan against a free wall, "
                         "with a door, and he walks over through the doors.",
                "ideas": [
                    "The floor plan is a small puzzle: the writer model could choose where a room goes and say why.",
                    "Catalog copy is good LLM work: each room's blurb in his voice when it arrives.",
                    "A riso look for the paper UI (menus, dev pages) while the 3D room stays crayon.",
                ],
                "try": "Order a room from the catalog on the left.",
            },
        ],
    },
    {
        "title": "Out the front door",
        "intro": "Five takes on an outdoors he can explore.",
        "sketches": [
            {
                "id": "meadow",
                "title": "Crayon meadow walk",
                "tags": ["side-scroller", "parallax", "time of day"],
                "palette": ["#a9d3f2", "#cdbaf0", "#b5e5cd", "#ffd8c0", "#2a2547"],
                "blurb": "Outside the front door the world is one long walk: parallax crayon hills, a path, "
                         "and landmarks spaced along it. He stops at each one for a thought. The light runs "
                         "through a whole day, with fireflies at night.",
                "ideas": [
                    "A 1D world is the cheapest outdoors: a place is a position on the path, so go_to(place) is one number.",
                    "Walking costs game time, so distance is a real trade against energy.",
                    "Verbs live at landmarks: fish at the pond, shop at the stall, and the bus stop leads to town (a new scene).",
                ],
                "try": "Just watch. A full day takes about a minute.",
            },
            {
                "id": "island",
                "title": "Pocket island (pixel)",
                "tags": ["top-down", "pixel art", "hub and spokes"],
                "palette": ["#3b6ea8", "#f2d49b", "#7fc17a", "#e86a5c", "#fff1d6"],
                "blurb": "A small top-down island at 180×110 pixels, scaled up sharp. Places are nodes on "
                         "dirt paths that all run through home, so the map is also the menu: choosing where "
                         "to go is a closed choice, and working out the route costs nothing.",
                "ideas": [
                    "Nodes and paths fit the menu tree Jev already walks: go out, then a place, then a verb.",
                    "Places unlock like tiers: the dock with a boat ticket, the woods after the field guide.",
                    "Everything here is generated by code, and a small canvas scaled up should be gentle on the TV (untested).",
                ],
                "try": "Click a place to send him there.",
            },
            {
                "id": "papercut",
                "title": "Paper puppet street",
                "tags": ["papercut", "dusk", "shoebox theater"],
                "palette": ["#c9b3dd", "#9cc7b8", "#ffe3a1", "#f8bfd0", "#e46f7a"],
                "blurb": "A shoebox theater of layered paper cutouts: a skyline, hills, a little street of "
                         "shops, and him as a puppet on a stick. The layers slide past each other as you "
                         "move, and the windows light up at dusk.",
                "ideas": [
                    "Each shop is a room he can walk into; the bakery and the record shop become sections of the shop's catalogue.",
                    "Just flat polygons with drop shadows. Bake each layer's shadow once and sliding the layers should stay cheap (untested).",
                    "On the TV the parallax could follow the D-pad or drift by itself.",
                ],
                "try": "Move the mouse across it.",
            },
            {
                "id": "lantern",
                "title": "Lantern night walk",
                "tags": ["ink wash", "lighting", "discovery"],
                "palette": ["#14122a", "#2a2550", "#ffcf6b", "#6b9b5e", "#d9534f"],
                "blurb": "Everything is ink in the dark, and only what his lantern touches has color. Stone "
                         "lanterns he passes light up and stay lit, so the map fills in as he explores. "
                         "You can watch the exploring happen.",
                "ideas": [
                    "Fog of war that doubles as memory: lit places are ones he remembers, fading ones he's forgetting.",
                    "A night walk as a verb: calming, good for mood, costs energy.",
                    "The lighting is a full-screen darkness layer with holes punched in it, plus glows. At about "
                    "7 ms per full-screen layer on the Streamer, this is the sketch most likely to need a cheaper trick.",
                ],
                "try": "Move the mouse left and right to walk him.",
            },
            {
                "id": "planet",
                "title": "Tiny planet",
                "tags": ["crayon", "wraparound", "space"],
                "palette": ["#bfe3b4", "#ffe3a1", "#1f1b3a", "#cdbaf0", "#f8bfd0"],
                "blurb": "His whole world is a planet he can walk around in twenty seconds: house, pond, "
                         "tree, lamp, rocket. The sun lights one side, and the lamp comes on as he walks into "
                         "night. New places go on the moons.",
                "ideas": [
                    "A world that wraps has no edges to author. Keep walking and you're home again.",
                    "Moons are places to buy: the library moon, the garden moon, reached by rocket (a trip, like shopping).",
                    "The whole world fits on one screen, which makes a good TV screensaver.",
                ],
                "try": "Click to make him stop or go.",
            },
        ],
    },
]


@app.route("/")
def index():
    return render_template("index.html", sections=SECTIONS)


if __name__ == "__main__":
    app.run(host="127.0.0.1", port=5057, debug=True)
