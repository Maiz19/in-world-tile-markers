# Changelog

## 1.5.0

- **Interact highlight** (new, off by default): outlines, hulls, clickboxes or tiles on what you hover and interact with.
- **Text**: pick font, size and edge; HD text stays sharp in stretched mode.
- **Paths**: the active path stays while the destination tile shows; predicted walks avoid water and fences; Gauntlet paths skip unopened rooms.
- **Attack paths** stop at your weapon's reach, follow a moving target and no longer jump when you eat, pray or switch. Telekinetic Grab no longer walks to the item.
- **Hover path**: *Show on minimap* is gone; *Display* and *Draw location(s)* decide where it shows.
- **Outlines** no longer flicker, sit right on docks and decks, and only cover what the game draws.
- **Through walls** works as the game draws things, object marks too.
- **Sailing**: marks on a boat are drawn sharp in the world.
- **Faster**: outlines on several CPU threads (General > Outline threads), and less work zoomed in.
- Right-click options start with **HD**. More options for plugin developers.

## 1.3.1

- Fixed active pathing to clicked objects in extended chunks.
- Improvements to active path and destination tile.

## 1.3.0

- Slayer task has its own section, with a fill color and border width of its own next to the styles and border color. They apply to every style your task's NPCs get, unless their name has a Tag color. Your Slayer settings stay as they were.

## 1.2.0

- Other plugins can send routes as lines (`lines`, optional arrows): a line on the ground through the tiles' centres, broken where the route jumps, such as at a teleport. See the guide, For plugin developers.
- Clicking an object no longer sends the active path onto the object for a moment: the flag the client puts on what you click is ignored until the game's own flag says where you walk.
- Sides objects cannot be used from: the list is made from the game cache, with the objects of the last years (Varlamore and on) that it lacked, and the ones the game changed since.
- Defaults: Slayer task styles is true tile, and the Add line option is off.

## 1.1.0

- Draw NPCs in front of marks (off by default, under NPCs; it costs frame rate): tagged NPCs are kept in front of the marks, as your own character is, so their tile marks lie under them; Shift + right-click an NPC, In front, does the same for an untagged NPC. It costs about a millisecond per such NPC on screen.
- Player in front of marks: marks are no longer cut away under your feet where the ground hides the model's soles.
- Follow other plugins (on by default, under Tile markers): a tile, line, object or NPC name you add or remove in Ground Markers, Line Markers, Object Markers, NPC Indicators or Better NPC Highlight is added or removed here at once, as the old plugin showed their marks live; Sync is for what was marked while this was off.
- Sync and the first start take NPC names from NPC Indicators and Better NPC Highlight both, and Better NPC Highlight's ID lists as the NPCs' names; before, with Better NPC Highlight installed, NPC Indicators' tags were skipped and ID lists were never taken.
- Predicted walk beyond the loaded area: the path and its tiles follow the extended view's heights and go around its water, walls and objects as far as it is loaded, instead of a flat straight line.
- Line markers: Shift + right-click a tile, Add line, draws a line along one of its sides, as the Line Markers plugin does; the mouse wheel turns a new line while Shift is held, Remove line takes it away, Extend line fills the tiles up to another tile in line with it. Lines from Line Markers are copied over on the first start and with Sync. Options under Line markers.

## 1.0.0

First release.
