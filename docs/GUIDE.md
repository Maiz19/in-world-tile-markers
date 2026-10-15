# HD World Markers guide

[Back to overview](../README.md)

Normal overlay lines are drawn on top of the game image, so Stretched Mode scales them up and they turn blurry. HD World Markers builds its markers from flat shapes inside the game scene instead. With GPU or an HD renderer they are drawn at the scene's own resolution, with border widths kept in screen pixels. Without GPU, and for anything that cannot go into the scene, the normal 2D drawing is used.

## Marking

Hold Shift and right-click:

- **a tile:** Mark or Unmark. A marked tile also has Label and Color (its own colour, or Reset).
- **a tile, for a line:** Add line draws a line along one side of the tile, the side chosen under **Line markers**, in the **Line color** and **Line width** there. While Shift stays held, the mouse wheel turns the line you just added to its next side. Remove line takes away the line added last on that tile. **Extend line**, on a tile in line with the line you added or turned last, puts the same line on every tile between them, up to 64 tiles. Each line keeps the colour and width it was added with.
- **an object:** Mark object or Unmark object. A marked object also has Mark border color, Mark fill color and Mark style (hull, outline, clickbox, tile).
- **an NPC:** Tag-All or Un-tag-All, for every NPC with that name. Tag-All adds the name to the lists of the styles picked in **Tag styles** (at first true tile and south-west true tile; Ctrl + click picks more than one). Changing Tag styles later leaves the names you tagged as they are. A tagged NPC also has Tag color and Tag style for its name: Tag style adds the name to one style's list or takes it out. With **Draw NPCs in front of marks** on (under NPCs, off by default, as it costs frame rate), tagged NPCs are kept in front of the marks, as your own character is with **Player in front of marks**, so their own tile marks lie under them; every other NPC has In front or Not in front to be kept in front too without a tag. This costs frame rate, about a millisecond per such NPC on screen, so turn it on only for the few NPCs it matters for; at most the 8 nearest are kept in front. Their names are under **In front names**.

With **Slayer task** on, the NPCs of your Slayer task are highlighted too, as the Slayer plugin finds them (it must be on), in the **Slayer task styles** and the **Slayer task color**.

The settings hold the colours and styles of marks without their own. Each NPC style (hull, tile, true tile, south-west tile, south-west true tile, outline, clickbox) has its list of names and its colours under **NPC styles**. Names can be typed into the lists too, separated by commas; `*` matches any text (`goblin*`). A name a pattern matches has Tag color and Tag style, but no Tag-All or Un-tag-All: change the pattern in its list. With **Remember tile colors** off, every tile is drawn in the tile colour, as in Ground Markers. These are the same options RuneLite's Ground Markers, Object Markers and NPC Indicators add. With those plugins on as well, both sets of options show.

### Your marks from other plugins

The first time HD World Markers starts in a RuneLite profile, it takes over your tiles from Ground Markers, your lines from Line Markers (those of its groups that show), your objects from Object Markers, and your NPC names from NPC Indicators and from Better NPC Highlight when it is installed (on or off). Each name goes only into the lists of the styles it was drawn with there: from Better NPC Highlight the name and ID lists of each style it has on (an ID becomes the NPC's name), from NPC Indicators the styles it has on, or an NPC's own Tag style. It also takes over the options you changed in those plugins (for NPC colours, from Better NPC Highlight when it is installed, else from NPC Indicators), in Agility, and in Tile Indicators while it is on. Their settings are only read, never changed. Turn those plugins off afterwards, or everything is drawn twice: a chat message names every plugin that still draws the same marks (the option **Double drawing warning** turns it off).

NPC Indicators keeps colours and styles per NPC; here they are kept per name.

While they stay installed, what you mark or unmark in them afterwards is marked or unmarked here at once (option **Follow other plugins**, on by default): a tile added in Ground Markers shows here right away, a tile removed there goes here too. **Sync** on the world map orb's right-click menu copies everything they have: marks missing here are added, and a mark here takes the colour, label or style it has there now. Sync never removes anything. Its chat message says what was copied and what each plugin holds.

### Sharing tiles

**Export HD World Markers** on the world map orb copies the tiles of the area you are in to the clipboard, in Ground Markers' format. **Import HD World Markers** adds tiles from the clipboard, from this export or Ground Markers' own. **Clear HD World Markers** removes the tiles of the area you are in. **Import, export, sync, clear** turns these options off.

### Tile Packs

The packs you turned on in the Tile Packs plugin are drawn too (option **Tile Packs**). Turn the Tile Packs plugin off once your packs are chosen: they stay chosen and its own drawing stops. Turn it on again to change them. To keep its panel at hand instead, make its own drawing invisible: in Tile Packs, turn on Override Color Active with a fully transparent color, set Fill Opacity to 0 and turn off Show Labels. Your own custom packs always show; packs added to Tile Packs later need an update of HD World Markers.

### Agility

Agility course obstacles, shortcuts (those above your level in orange), Prifddinas portals, traps, marks of grace and the Werewolf course's stick are highlighted as the Agility plugin does, with its options under **Agility**. Turn off the Agility plugin's own highlights (each option that highlights or shows an overlay), or they are drawn twice; its lap counter keeps working.

## What it draws

- **Your marks:** tiles with their labels, lines along tile sides, objects and tagged NPCs.
- **Agility:** obstacles, shortcuts and marks of grace.
- **Tile indicators:** the destination, hovered and true tile, each with corners-only, fade-out and colour options.
- **Paths:** the path you are walking, and the path to the hovered tile once you turn it on under **Hover path**, in the game world or on the minimap. Running tiles you only pass get their own colour. After a click beyond the loaded area (an HD renderer's extended view), the destination and a predicted path are shown too: through the extended view's terrain, around its water, walls and objects as far as it is loaded, then straight on. The client itself has no collision data there, so that part stays a prediction.
- **Marks other plugins send**, see below.

## Limits

- The scene drawing needs GPU or an HD renderer.
- Only the nearest 1,500 tiles and the nearest 256 hulls, clickboxes and outlines are drawn in the scene; the rest are drawn in 2D.
- A floor that is an object rather than terrain (some docks) can cover tiles when **Through walls** is off.
- With an HD renderer's "Shadow transparency" on, marks drawn through walls cast shadows that move with the camera. HD World Markers says so once in the chat box.

## For plugin developers

Other plugins can have HD World Markers draw their tiles, lines, timer pies and NPC highlights without depending on it: post a `PluginMessage` with namespace `hd-world-markers`. Without HD World Markers installed, nothing happens.

```java
Map<String, Object> tile = new HashMap<>();
tile.put("point", new WorldPoint(3222, 3218, 0)); // or "x", "y", "plane"
tile.put("color", Color.CYAN);                    // Color or ARGB int
tile.put("fill", new Color(0, 255, 255, 40));     // optional, default black at alpha 50
tile.put("width", 2);                             // optional border width in pixels
tile.put("size", 1);                              // optional, n x n tiles from the south-west tile
tile.put("label", "Safespot");                    // optional

Map<String, Object> data = new HashMap<>();
data.put("owner", "my-plugin");                   // your plugin's id
data.put("tiles", List.of(tile));
eventBus.post(new PluginMessage("hd-world-markers", "tiles", data));
```

| Message | Data | Effect |
|---|---|---|
| `tiles` | `owner`, `tiles` (list of maps as above) | Replaces all tiles of `owner`. |
| `npcs` | `owner`, `npcs`: maps with `npc` (the NPC) or `index`, `style` (`hull`, `outline`, `clickbox`, `tile` or `truetile`), `color`, optional `fill` and `width` | Replaces all NPC marks of `owner`. |
| `pies` | `owner`, `pies`: maps with `point` (or `x`, `y`, `plane`), `color` (the filled part), optional `border` (its ring) and `width`, `progress` (0 to 1) or `start` and `end` (`System.currentTimeMillis()` times; it fills from start to end by itself), `size` (diameter in local units, 128 is a tile; default 64) and `height` (local units above the ground) | Replaces all pies of `owner`. A pie fills clockwise from the top, as RuneLite's progress pies, and grows and shrinks with the world as you zoom. |
| `lines` | `owner`, `lines`: maps with `points` (a list of `WorldPoint`s, or maps with `x`, `y`, `plane`), `color`, optional `width` (pixels, default 2) and optional `arrows` (a number n: an arrowhead every n tiles, pointing the way the points go) | Replaces all lines of `owner`. A line runs on the ground through the tiles' centres, as a route: points next to each other (diagonals too, same plane) are joined; any other jump, such as a teleport, breaks the line there. Only the floor you are on is drawn. In an instance a point found in several places is left out. At most 1,000 points per owner in all. |
| `clear` | `owner` | Removes everything of `owner`. |

Marks stay until their owner replaces or clears them, at most 1,000 per owner (for lines: points). They are also cleared on logout, world hop, connection loss, profile change and when HD World Markers stops. Send `clear` in your plugin's `shutDown`, and send your marks again after logging in.
