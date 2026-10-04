# Third-party notices

## RuneLite

- Source: https://github.com/runelite/runelite (tag `runelite-parent-1.12.39`; `Marking.java` tag `runelite-parent-1.13.1`)
- License: BSD 2-Clause, copyright (c) 2017-2018 and 2021 Adam, (c) 2018 Tomas Slusny, TheLonelyDev and James Swindle, (c) 2019 Abex, and the RuneLite contributors. Full text in `src/main/resources/META-INF/LICENSE-runelite`, included in the JAR.

These files adapt parts of RuneLite; each names its origin in its header:

- `FloatClickbox.java`: the clickbox of `Perspective.getClickbox`, `RectangleUnion.union` and `SimplePolygon.intersectWithConvex`, computed in floats.
- `ModelShapes.java` and `Terrain.java`: the projection of `Perspective.localToCanvasGpu` / `modelToCanvas`, and the height interpolation of `Perspective.getTileHeight`.
- `Marking.java`: the Shift + right-click options of Ground Markers, Object Markers and NPC Indicators with their colour and style menus, and Ground Markers' import, export and clear (its saved tile format). Taking over the marks and options those plugins saved only reads their settings.
- `MarkerSources.java`: Ground Markers' point loading and NPC Indicators' name matching, for In-World Tile Markers' own saved marks.
- `ObjectMarkerSource.java`: Object Markers' matching and display rules, for In-World Tile Markers' own saved marks.
- `InWorldTileMarkersConfig.java`: the option names and descriptions of Tile Indicators, and option names of Ground Markers, Object Markers and NPC Indicators.

## Path Marker

- Author: GeChallengeM
- Source: https://github.com/GeChallengeM/path-marker (commit `495f3594bf697a1b9a5313802f731b2c85a6e37e`)
- License: BSD 2-Clause, copyright (c) 2022 GeChallengeM. Full text in `src/main/resources/META-INF/LICENSE-path-marker`, included in the JAR.

`src/main/resources/com/inworldtilemarkers/loc_blocking.txt` (per object, the sides it cannot be used from) and `npc_blocking.txt` (NPCs that block walking) are Path Marker's lists of that commit, unchanged; the client's API offers neither. The path options in `InWorldTileMarkersConfig.java` keep the option names and descriptions of its `PathMarkerConfig`.

The path code, `PathTracker.java` and `RouteFinder.java`, is In-World Tile Markers' own, written with Path Marker as the reference for its features and for the game's rules it follows (the client's route search, reach rules per object shape, and how the game steps along a route).

## Tile Packs

- Author: TrevorMDev
- Source: https://github.com/TrevorMDev/tile-packs (commit `8091ee842642c34fb88bb79c61e104500e91bd96`)
- License: BSD 2-Clause, copyright (c) 2022-2024 Trevor (TrevorMDev). Full text in `src/main/resources/META-INF/LICENSE-tile-packs`, included in the JAR.

`src/main/resources/com/inworldtilemarkers/tilePacks.jsonc` is the pack list of that commit, unchanged. `TilePackSource.java` adapts its pack loading (`TilePackManager`, `PointManager`): Tile Packs' settings are only read (the packs turned on, custom packs), and the tiles are drawn by In-World Tile Markers. Packs added to Tile Packs after that commit are missing until the file is updated; custom packs are always current.

## Corner Tile Indicators

- Author: geheur
- Source: https://github.com/geheur/Corner-Tile-Indicators (commit `37a631af5fc9463c1412cc796ff5ca47f5d07cc8`)
- License: BSD 2-Clause, copyright (c) 2023 geheur; its copyright line is in `LICENSE`.

The corner style of the tile indicators follows its `renderPolygonCorners` (the 2D corners in `IndicatorOverlay.java` and the corner lines in `SceneShapeRenderer.java`), and the corner and fade options in `InWorldTileMarkersConfig.java` keep its option names and descriptions.
