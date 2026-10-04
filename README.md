# In-World Tile Markers

Draws tile, object, NPC and path markers in the game world instead of on the interface. The interface is scaled along with the rest of the client (Stretched Mode, client scaling), which makes normal markers thick and blurry. Markers in the world are rendered with the scene at your full resolution, so they stay sharp.

Needs the GPU plugin or 117 HD. Without it, markers are drawn the normal way.

Left: RuneLite's normal markers in Stretched Mode. Right: In-World Tile Markers.

| Before | After |
|---|---|
| ![Tree hull before](docs/images/tree-before.png) | ![Tree hull after](docs/images/tree-after.png) |
| ![Tiles before](docs/images/tiles-before.png) | ![Tiles after](docs/images/tiles-after.png) |
| ![Clickbox before](docs/images/clickbox-before.png) | ![Clickbox after](docs/images/clickbox-after.png) |

Mark tiles, objects and NPCs with Shift + right-click, as with RuneLite's Ground Markers, Object Markers and NPC Indicators. Your marks from those plugins are copied over on the first start, and the packs you turned on in Tile Packs are drawn too. It also draws agility obstacles, shortcuts and marks of grace, your destination, hovered and current tile and the path you walk, and other plugins can send it marks to draw.

[Guide](docs/GUIDE.md) · [Credits](THIRD_PARTY_NOTICES.md) · [License](LICENSE)
