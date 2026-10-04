# In-World Tile Markers guide

[Back to overview](../README.md)

Normal overlay lines are drawn on top of the game image, so Stretched Mode scales them up and they turn blurry. In-World Tile Markers builds its markers from flat shapes inside the game scene instead. With GPU or an HD renderer they are drawn at the scene's own resolution, with border widths kept in screen pixels. Without GPU, and for anything that cannot go into the scene, the normal 2D drawing is used.

## Marking

Hold Shift and right-click:

- **a tile:** Mark or Unmark. A marked tile also has Label and Color (its own colour, or Reset).
- **an object:** Mark object or Unmark object. A marked object also has Mark border color, Mark fill color and Mark style (hull, outline, clickbox, tile).
- **an NPC:** Tag-All or Un-tag-All, for every NPC with that name. A tagged NPC also has Tag color and Tag style (hull, tile, true tile, south-west tile, south-west true tile, outline), for its name. Names can also be typed into **NPC names**, separated by commas; `*` matches any text (`goblin*`).

The settings hold the colours and styles of marks without their own. These are the same options RuneLite's Ground Markers, Object Markers and NPC Indicators add. With those plugins on as well, both sets of options show.

### Your marks from Ground Markers, Object Markers and NPC Indicators

The first time In-World Tile Markers starts in a RuneLite profile, it takes over your tiles from Ground Markers, your objects from Object Markers and your names from NPC Indicators, with their own colours and styles. It also takes over the options you changed in those plugins, and in Tile Indicators while it is on, unless you changed the same option here. Their settings are only read, never changed. Turn those plugins off afterwards, or everything is drawn twice.

NPC Indicators keeps colours and styles per NPC; here they are kept per name.

**Sync** on the world map orb's right-click menu copies what you marked in them since. It only adds: a mark you removed here comes back if the other plugin still has it.

### Sharing tiles

**Export In-World Tile Markers** on the world map orb copies the tiles of the area you are in to the clipboard, in Ground Markers' format. **Import In-World Tile Markers** adds tiles from the clipboard, from this export or Ground Markers' own. **Clear In-World Tile Markers** removes the tiles of the area you are in. **Import, export, sync, clear** turns these options off.

### Tile Packs

The packs you turned on in the Tile Packs plugin are drawn too (option **Tile Packs**). Turn the Tile Packs plugin off once your packs are chosen: they stay chosen and its own drawing stops. Turn it on again to change them. Your own custom packs always show; packs added to Tile Packs later need an update of In-World Tile Markers.

## What it draws

- **Your marks:** tiles with their labels, objects and tagged NPCs.
- **Tile indicators:** the destination, hovered and true tile, each with corners-only, fade-out and colour options.
- **Paths:** the path you are walking and the path to the hovered tile, in the game world and on the minimap. Running tiles you only pass get their own colour. After a click beyond the loaded area (an HD renderer's extended view), the destination and a predicted path are shown too.
- **Marks other plugins send**, see below.

## Limits

- The scene drawing needs GPU or an HD renderer.
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
