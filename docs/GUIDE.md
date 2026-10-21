# HD World Markers guide

[Back to overview](../README.md)

Normal overlay lines are drawn on top of the game image, so Stretched Mode scales them up and they turn blurry. HD World Markers builds its markers from flat shapes inside the game scene instead. With GPU or an HD renderer they are drawn at the scene's own resolution, with border widths kept in screen pixels. Without GPU, and for anything that cannot go into the scene, the normal 2D drawing is used.

## Marking

Hold Shift and right-click. The options start with HD, so they stand apart from other plugins' options:

- **a tile:** HD Mark or HD Unmark. A marked tile also has HD Label and HD Color (its own colour, or Reset).
- **a tile, for a line:** with **Add line option** on, HD Add line draws a line along one side of the tile, the side chosen under **Line markers**, in the **Line color** and **Line width** there. While Shift stays held, the mouse wheel turns the line you just added to its next side. HD Remove line takes away the line added last on that tile; it shows on every tile with a line, also with Add line option off, so lines copied from Line Markers can always be removed. **HD Extend line**, on a tile in line with the line you added or turned last, puts the same line on every tile between them, up to 64 tiles. Each line keeps the colour and width it was added with.
- **an object:** HD Mark object or HD Unmark object. A marked object also has HD Mark border color, HD Mark fill color and HD Mark style (hull, outline, clickbox, tile).
- **an NPC:** HD Tag-All or HD Un-tag-All, for every NPC with that name. HD Tag-All adds the name to the lists of the styles picked in **Tag styles** (at first true tile and south-west true tile; Ctrl + click picks more than one). Changing Tag styles later leaves the names you tagged as they are. A tagged NPC also has HD Tag color and HD Tag style for its name: HD Tag style adds the name to one style's list or takes it out. With **Draw NPCs in front of marks** on (under NPCs, off by default, as it costs frame rate), tagged NPCs are kept in front of the marks, as your own character is with **Player in front of marks**, so their own tile marks lie under them; every other NPC has HD In front or HD Not in front to be kept in front too without a tag. This costs frame rate, about a millisecond per such NPC on screen, so turn it on only for the few NPCs it matters for; at most the 8 nearest are kept in front. Their names are under **In front names**.

With **Slayer task** on, the NPCs of your Slayer task are highlighted too, as the Slayer plugin finds them (it must be on), in the **Slayer task styles** and the **Slayer task color**.

The settings hold the colours and styles of marks without their own. Each NPC style (hull, tile, true tile, south-west tile, south-west true tile, outline, clickbox) has its colours under **NPC styles** and its list of names under **NPC names** (a section of its own, so the lists stay folded away). Names can be typed into the lists too, separated by commas; `*` matches any text (`goblin*`). A name a pattern matches has HD Tag color and HD Tag style, but no HD Tag-All or HD Un-tag-All: change the pattern in its list. With **Remember tile colors** off, every tile is drawn in the tile colour, as in Ground Markers. These are the same options RuneLite's Ground Markers, Object Markers and NPC Indicators add. With those plugins on as well, both sets of options show; ours start with HD.

### Your marks from other plugins

The first time HD World Markers starts in a RuneLite profile, it takes over your tiles from Ground Markers, your lines from Line Markers (those of its groups that show), your objects from Object Markers, and your NPC names from NPC Indicators and from Better NPC Highlight when it is installed (on or off). Each name goes only into the lists of the styles it was drawn with there: from Better NPC Highlight the name and ID lists of each style it has on (an ID becomes the NPC's name), from NPC Indicators the styles it has on, or an NPC's own Tag style. It also takes over the options you changed in those plugins (for NPC colours, from Better NPC Highlight when it is installed, else from NPC Indicators), in Agility, and in Tile Indicators while it is on. Their settings are only read, never changed. Turn those plugins off afterwards, or everything is drawn twice: a chat message names every plugin that still draws the same marks (the option **Double drawing warning** turns it off).

NPC Indicators keeps colours and styles per NPC; here they are kept per name.

While they stay installed, what you mark or unmark in them afterwards is marked or unmarked here at once (option **Follow other plugins**, on by default): a tile added in Ground Markers shows here right away, a tile removed there goes here too. **Sync** on the world map orb's right-click menu copies everything they have: marks missing here are added, and a mark here takes the colour, label or style it has there now. Sync never removes anything. Its chat message says what was copied and what each plugin holds.

### Sharing tiles

**Export HD World Markers** on the world map orb copies the tiles of the area you are in to the clipboard, in Ground Markers' format. **Import HD World Markers** adds tiles from the clipboard, from this export or Ground Markers' own. **Clear HD World Markers** removes the tiles of the area you are in, **Clear lines HD World Markers** its lines. **Import, export, sync, clear** turns these options off.

### Tile Packs

The packs you turned on in the Tile Packs plugin are drawn too (option **Tile Packs**). Turn the Tile Packs plugin off once your packs are chosen: they stay chosen and its own drawing stops. Turn it on again to change them. To keep its panel at hand instead, make its own drawing invisible: in Tile Packs, turn on Override Color Active with a fully transparent color, set Fill Opacity to 0 and turn off Show Labels. Your own custom packs always show; packs added to Tile Packs later need an update of HD World Markers.

### Agility

Agility course obstacles, shortcuts (those above your level in orange), Prifddinas portals, traps, marks of grace and the Werewolf course's stick are highlighted as the Agility plugin does, with its options under **Agility**. Turn off the Agility plugin's own highlights (each option that highlights or shows an overlay), or they are drawn twice; its lap counter keeps working.

## What it draws

- **Your marks:** tiles with their labels, lines along tile sides, objects and tagged NPCs.
- **Agility:** obstacles, shortcuts and marks of grace.
- **Tile indicators:** the destination, hovered and true tile, each with corners-only, fade-out and colour options.
- **Interact highlight:** as RuneLite's Interact Highlight, the NPC, object or ground item the mouse is on (never a player) (**Hover styles**) and the one you clicked or interact with (**Interact styles**), which flashes white as you click. Pick the styles for each: outline, hull, clickbox, tile and true tile (Ctrl + click picks more than one), each with its own border and fill colour; none picked turns it off. Hover highlights only what a click would choose (the top option of the menu, or the option the mouse is on with the menu open), not what is under it: Take on a drink on a table highlights the drink, not the table. Where it draws the same style as your own mark of that thing (a marked object's hull and a hover hull), only the Interact highlight shows. **Hide in combat** hides it while you are in combat (you hit or were hit in the last 6 seconds, or you or an NPC is attacking), and on NPCs you can attack. It is off until you turn on **Interact highlight** at the top of its section; both start as an outline. Turn off RuneLite's Interact Highlight once you use it, or both are drawn.
- **Paths:** the path you are walking, and the path to the hovered tile once you turn it on under **Hover path**, in the game world or on the minimap. Running tiles you only pass get their own colour. After a click beyond the loaded area (an HD renderer's extended view), the destination and a predicted path are shown too: through the extended view's terrain, around its water, walls and objects as far as it is loaded, then straight on. The client itself has no collision data there, so that part stays a prediction.
- **Marks other plugins send**, see below.

## Limits

- The scene drawing needs GPU or an HD renderer.
- Only the nearest 1,500 tiles and the nearest 256 hulls, clickboxes and outlines are drawn in the scene; the rest are drawn in 2D.
- A floor that is an object rather than terrain (some docks) can cover tiles when **Through walls** is off.
- With an HD renderer's "Shadow transparency" on, marks drawn through walls cast shadows that move with the camera. HD World Markers says so once in the chat box.

## For plugin developers

Other plugins can have HD World Markers draw their tiles, lines, timer pies and texts, and highlight NPCs, players, objects and ground items, without depending on it: post a `PluginMessage` with namespace `hd-world-markers`. Without HD World Markers installed, nothing happens.

```java
Map<String, Object> tile = new HashMap<>();
tile.put("point", new WorldPoint(3222, 3218, 0)); // or "x", "y", "plane"
tile.put("color", Color.CYAN);                    // a Color, or an ARGB int with its alpha: 0xFF00FFFF
tile.put("fill", new Color(0, 255, 255, 40));     // optional
tile.put("label", "Safespot");                    // optional

Map<String, Object> data = new HashMap<>();
data.put("owner", "my-plugin");                   // your plugin's id
data.put("tiles", List.of(tile));
eventBus.post(new PluginMessage("hd-world-markers", "tiles", data));
```

Every message has `owner`, a string of your own (a message without one is ignored), and replaces only that owner's marks of its own kind: new `tiles` leave your `lines` as they are. Marks you can send:

| Message | List | Effect |
|---|---|---|
| `tiles` | `tiles` | Replaces all tiles of `owner`. |
| `npcs` | `npcs` | Replaces all NPC highlights of `owner`. |
| `players` | `players` | Replaces all player highlights of `owner`. |
| `objects` | `objects` | Replaces all object highlights of `owner`. |
| `items` | `items` | Replaces all ground item highlights of `owner`. |
| `pies` | `pies` | Replaces all pies of `owner`. |
| `lines` | `lines` | Replaces all lines of `owner`. |
| `texts` | `texts` | Replaces all texts of `owner`. |
| `clear` | none | Removes everything of `owner`. |

Each list holds maps; a map without its required fields is skipped. Colours are a `Color` or an ARGB int: an int without alpha (`0x00FFFF`) is fully see-through, so write `0xFF00FFFF`. Numbers may be any `Number`. A point is a `WorldPoint` under `point`, or numbers `x`, `y` and `plane` (default 0). In an instance (a player-owned house, a raid) a point can be where it is now (`getWorldLocation`) or its template tile, as Ground Markers saves it; a template tile is drawn in every place the instance shows it.

**Tiles**

| Field | | Default |
|---|---|---|
| `point` | required | |
| `color` | required: the border | |
| `fill` | optional | black at alpha 50 |
| `width` | optional: border width in pixels | 2 |
| `size` | optional: n x n tiles from the south-west tile, at most 64 | 1 |
| `label` | optional: text on the tile | none |

**NPCs, players, objects and ground items**

These four are drawn as Interact highlight draws them, in any of its styles. Each map names one thing:

| Message | Field | |
|---|---|---|
| `npcs` | `npc` or `index` | the `NPC`, or its index |
| `players` | `player` or `name` | the `Player`, or its name, in any case |
| `objects` | `object`, or `point` and `id` | the `TileObject`, or its tile and object id: a game object, wall, decoration or ground object, also when it shows another form (a door that opens) |
| `items` | `point` and `id` | the tile and item id of a ground item, when it shows (one of the top three of its pile) |

And how to draw it:

| Field | | Default |
|---|---|---|
| `style` or `styles` | one style, or a list of them: `outline`, `hull`, `clickbox`, `tile`, `truetile`, `swtile`, `swtruetile` | `hull` |
| `color` | required: the border | |
| `fill` | optional, not for `outline` | black at alpha 50 |
| `width` | optional: border width in pixels | 2 |

`tile` follows an NPC or player as it walks; `truetile` is the tile the server has it on; `swtile` and `swtruetile` are the south-west tile of those (one tile, for a large NPC). An object or item has one tile style: its footprint. A thing sent as itself is drawn while it is there: a despawned NPC or player, or an object gone from its tile, is not. One sent by index, name or tile and id is looked up anew all the time, so it is found again when it comes back. In an instance a tile and id can be the template tile or the tile as it is there, as for tiles. On a boat or other world view of its own only the outline is drawn.

**Timer pies**

| Field | | Default |
|---|---|---|
| `point`, or what it follows | required: a point, or an NPC (`npc` or `index`) or player (`player` or `name`) to follow | |
| `color` | the filled part; `color`, `border` or both | none |
| `border` | the ring around it | none |
| `width` | optional: the ring's width in pixels | 1 |
| `progress` | 0 to 1, or instead `start` and `end` | 0 |
| `start`, `end` | `System.currentTimeMillis()` times: the pie fills from start to end by itself | |
| `size` | optional: diameter in local units (128 is a tile), 8 to 1024 | 64 |
| `height` | optional: local units above the ground | 0; following a character, above its head |

A pie fills clockwise from the top, as RuneLite's progress pies, stands towards the camera and grows and shrinks with the world as you zoom. One that follows an NPC or player walks with it and is gone while the character is.

**Texts**

| Field | | Default |
|---|---|---|
| `point`, or what it follows | required: as for pies | |
| `text` | required | |
| `color` | required: the text's colour | |
| `height` | optional: local units above the ground | 0; following a character, above its head |

Text on its own, drawn as a tile's label, without a tile under it.

**Lines**

| Field | | Default |
|---|---|---|
| `points` | required: a list of points, in walking order | |
| `color` | required | |
| `width` | optional: width in pixels | 2 |
| `arrows` | optional: a number n, an arrowhead every n tiles | none |

A line runs on the ground through the tiles' centres, as a route: points next to each other (diagonals too, same plane) are joined; any other jump, such as a teleport, breaks the line there. Arrowheads point the way the points go and are counted from the line's end, so they stay put as you trim the route's start while the player walks. In an instance a point found in several places is left out. At most 1,000 points per owner in all lines.

**Limits and lifetime**

- At most 1,000 marks per owner and kind (for lines: points); the rest is left out.
- Only the floor you are on is drawn, as for your own marks.
- HD World Markers keeps a copy of what you send, so you may change or reuse your lists after posting. You can post from any thread.
- Marks stay until their owner replaces or clears them. They are also cleared on logout, world hop, connection loss, profile change and when HD World Markers stops. Send `clear` in your plugin's `shutDown`, and send your marks again after logging in.
