# Changelog

## 1.4.0

- Interact highlight (under its own section): the NPC, object or ground item the mouse is on, and the one you clicked or interact with, as RuneLite's Interact Highlight, each in the styles you pick (outline, hull, clickbox, tile, true tile), with a border and fill color for each. Hover highlights only what a click would choose (a drink on a table, not the table). Where it draws the same style as a mark of the same thing (a marked object's hull and its hover hull), only Interact highlight's shows. Hide in combat turns it off while you fight, and for NPCs you can attack. Players are never highlighted, as in RuneLite's Interact Highlight. No style is picked at first, so it is off until you pick one.
- A new outline no longer shows as a hull border for a frame first.
- Predicted walk: a click on an object, NPC, item or player, or a Walk here that meets nothing, ends the last prediction (its far-away destination and second path stayed), and only one path shows at a time.
- Remove line shows on every tile with a line, also with Add line option off, so lines copied from Line Markers can always be removed; Clear lines on the world map orb removes the lines of the area you are in.
- The settings sections start collapsed, except General and Tile markers, and the NPC styles' name lists have a section of their own, NPC names.
- The right-click options start with HD (HD Mark, HD Tag-All, HD Mark object, HD Add line, ...), so they are easy to tell from those of RuneLite's marking plugins used alongside.
- For plugin developers: highlight players, objects and ground items too (messages `players`, `objects`, `items`), in every style of Interact highlight (outline, hull, clickbox, tile, true tile and the south-west tiles), several per thing with `styles`. Timer pies can follow an NPC or player, above its head, and `texts` puts text on its own on a tile or above a character. NPC highlights get the south-west styles, an outline on a boat, and an NPC sent as itself is not drawn after it despawns. The guide lists every field with its default.
- Outlines no longer jump or flicker: an outline now colours what lies outside the model within the border's width of it, as RuneLite's own outlines do. Its border no longer runs across a narrow gap onto the model beyond, or over the model around a hole, so it changes only as much as the model does. Thin parts (hair, a pipe, a rope) stay one piece as they move.
- Hulls, outlines and clickboxes of NPCs and players sit where the game draws them: they were a little too low on a dock or deck, most visible from a low camera.
- Predicted walk in extended chunks: no longer over open water, along piers again, and around objects without a name that block walking (fences); objects that do not block walking are walked through. Which objects block is a list made from the game cache.

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
