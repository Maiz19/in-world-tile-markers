# In-World Tile Markers guide

[Back to overview](../README.md)

Normal overlay lines are drawn on top of the game image, so Stretched Mode scales them up and they turn blurry. In-World Tile Markers builds its markers from flat shapes inside the game scene instead. With GPU or an HD renderer they are drawn at the scene's own resolution, with border widths kept in screen pixels. Without GPU, and for anything that cannot go into the scene, the normal 2D drawing is used.

## What it draws

**Your own markers**

- **Ground Markers:** your saved marks, including imported tiles, in Ground Markers' colours, border width and fill. Labels and the minimap stay with Ground Markers.
- **Object Markers:** every marked object in its own style (hull, clickbox, tile or outline) and colours.
- **NPC Indicators:** its tagged NPCs and NPC list, with its per-NPC colours and styles.
- **Tile indicators:** the destination, hovered and true tile, each with corners-only, fade-out and colour options.
- **Paths:** the path you are walking and the path to the hovered tile, in the game world and on the minimap. Running tiles you only pass get their own colour. After a click beyond the loaded area (an HD renderer's extended view), the destination and a predicted path are shown too.

**Other plugins' marks**

The tile highlights, clickboxes, hulls, path lines and timer pies these plugins draw in the world are drawn in the scene as well, in their own colours and by their own settings:

- RuneLite: Agility, Blast Furnace, Blast Mine, Cannon, Fishing, Ground Items, Herbiboar, Implings, Kourend Library, Mage Training Arena, Mining, Motherlode Mine, NPC Aggression Timer, Party, Pest Control, Player Indicators (not in PvP worlds or the Wilderness), Pyramid Plunder, Runecraft and Woodcutting.
- Plugin Hub: Advanced Mining, Better NPC Highlight (the NPCs in its name lists and your Slayer task), Clue Details, Game Tick Information, Mahogany Homes, Motherlode Mine Improved, Port Tasks, Quest Helper, Remaining Amethyst, Rogues' Den, Rooftop Agility Improved, Sailing, Shortest Path, Star Info, Stealing Artefacts, Stop Misclicking Tiles, The Gauntlet and Tile Packs.

While In-World Tile Markers draws a plugin's marks, that plugin's own drawing of those marks pauses, so nothing is drawn twice. Its text, icons, timers, menus, panels and settings keep working, and its own drawing returns as soon as In-World Tile Markers stops. This is off by default: turn on **Other plugins' marks** to use it.

**Extend plugin ranges** keeps the marks of plugins that only draw near you (Agility, Blast Furnace, Pyramid Plunder, Tile Packs, NPC Aggression Timer) up to the draw distance, once you have seen them.

## Limits

- The scene drawing needs GPU or an HD renderer.
- Outlines other plugins draw with RuneLite's outline renderer (for example Quest Helper's and Better NPC Highlight's outline styles) stay 2D: that renderer draws straight into the image. Their hull and clickbox styles are drawn in the scene.
- More than 1,500 tiles, or more than 256 hulls, clickboxes and outlines, fall back to 2D.
- A floor that is an object rather than terrain (some docks) can cover tiles when **Through walls** is off.
- With an HD renderer's "Shadow transparency" on, marks drawn through walls cast shadows that move with the camera. In-World Tile Markers says so once in the chat box.

## For plugin developers

Other plugins can have In-World Tile Markers draw their tiles and NPC highlights without depending on it: post a `PluginMessage` with namespace `in-world-tile-markers`. Without In-World Tile Markers installed, nothing happens.

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
eventBus.post(new PluginMessage("in-world-tile-markers", "tiles", data));
```

| Message | Data | Effect |
|---|---|---|
| `tiles` | `owner`, `tiles` (list of maps as above) | Replaces all tiles of `owner`. |
| `npcs` | `owner`, `npcs`: maps with `npc` (the NPC) or `index`, `style` (`hull`, `outline`, `clickbox`, `tile` or `truetile`), `color`, optional `fill` and `width` | Replaces all NPC marks of `owner`. |
| `clear` | `owner` | Removes everything of `owner`. |

Marks stay until their owner replaces or clears them, at most 1,000 per owner. They are also cleared on logout, world hop, connection loss, profile change and when In-World Tile Markers stops. Send `clear` in your plugin's `shutDown`, and send your marks again after logging in.
