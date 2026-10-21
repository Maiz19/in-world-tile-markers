package com.hdworldmarkers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * Routes and steps as the game takes them, on the collision flags of your level. The server finds the route (the
 * client only shows its flag): a breadth-first search from your tile, at most 64 tiles each way, trying the steps west,
 * east, south, north and then the diagonals, until it stands on a tile from which the target can be used: of two sides
 * as many steps away, the one that order finds first. Without one, it ends on the visited tile
 * within 10 tiles of the target that lies nearest to it, then takes fewest steps, under 100. Of a route the game keeps
 * the tiles where it turns, at most 25, and walks from turn to turn: diagonally where it can, else along x, else y.
 *
 * Beyond the loaded area the client has no collision flags. For a predicted walk there (findBeyond, stepBeyond) they
 * are approximated from what a renderer's extended view shows: water and other blocked floor, walls, and objects.
 */
class RouteFinder
{
    /** Steps in the client's search order: west, east, south, north, south-west, south-east, north-west, north-east. */
    private static final int[] DX = {-1, 1, 0, 0, -1, 1, -1, 1}, DY = {0, 0, -1, 1, -1, -1, 1, 1};
    /**
     * For a step (dx, dy), at 3 * dx + dy + 4: the client's flags of the tile stepped onto that stop it. The tile is
     * blocked, or has a wall (or a wall's corner) on a side the step crosses.
     */
    private static final int[] STOP = {0x124010e, 0x1240108, 0x1240138, 0x1240102, 0, 0x1240120, 0x1240183, 0x1240180, 0x12401e0};
    /** The search window, centred on your tile, as cells x * SIZE + y. */
    private static final int SIZE = 128, CENTRE = 64 * SIZE + 64;
    /**
     * Lists the client's API does not offer (see THIRD_PARTY_NOTICES.md): per object the sides it cannot be used from
     * (bits north 1, east 2, south 4, west 8, unrotated; made from the game cache), and the NPCs that block walking
     * (Path Marker's).
     */
    private static final Map<Integer, Integer> BLOCKED_SIDES = read("loc_blocking.txt"), BLOCKING_NPCS = read("npc_blocking.txt");
    /** The objects that do not block walking, as runs of ids first=last (made from the game cache); newer objects block. */
    private static final NavigableMap<Integer, Integer> WALKABLE = new TreeMap<>(read("loc_walkable.txt"));
    private final int[] steps = new int[SIZE * SIZE], via = new int[SIZE * SIZE], queue = new int[SIZE * SIZE];
    private final List<WorldArea> blockers = new ArrayList<>();
    private int[][] flags;
    private int baseX, baseY, plane;
    /**
     * The extended scene as collision flags (ext[sceneX + extOffset][sceneY + extOffset]), approximated from its tiles;
     * EXT_UNKNOWN where no tile is loaded; null without an extended scene. Made again when the scene, area or level
     * changes, and every EXT_AGE loads (doors open and close).
     */
    private int[][] ext;
    private int extOffset, extAge;
    private Object extScene;
    private int extBaseX, extBaseY, extPlane;
    private static final int EXT_UNKNOWN = -2, EXT_AGE = 500;
    /** The client walks nowhere within this many tiles of the loaded area's edge; beyond it, the extended view decides. */
    private static final int BORDER = 6;
    /** While set (findBeyond, stepBeyond), the border and everything beyond take the extended view's flags. */
    private boolean borderless;

    /** Takes the collision flags of the level you are on; false while there are none. */
    boolean load(WorldView wv)
    {
        CollisionData[] maps = wv.getCollisionMaps();
        if (maps == null) { return false; }
        plane = wv.getPlane();
        flags = maps[plane].getFlags();
        baseX = wv.getBaseX();
        baseY = wv.getBaseY();
        extended(wv);
        return true;
    }

    /** Flags for the extended scene, as the client makes its own: floor, and the walls and objects that block walking. */
    private void extended(WorldView wv)
    {
        Scene scene = wv.getScene();
        Tile[][][] tiles = scene == null ? null : scene.getExtendedTiles();
        if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane].length <= flags.length) { ext = null; extScene = null; return; }
        boolean same = scene == extScene && baseX == extBaseX && baseY == extBaseY && plane == extPlane;
        if (same && ++extAge < EXT_AGE) { return; }
        extAge = 0; extScene = scene; extBaseX = baseX; extBaseY = baseY; extPlane = plane;
        byte[][][] settings = scene.getExtendedTileSettings();
        int size = tiles[plane].length;
        extOffset = (size - flags.length) / 2;
        if (ext == null || ext.length != size) { ext = new int[size][size]; }
        for (int[] row : ext) { Arrays.fill(row, EXT_UNKNOWN); }
        for (int x = 0; x < size; x++)
        {
            for (int y = 0; y < size; y++)
            {
                Tile t = tiles[plane][x][y];
                if (t == null) { continue; }
                // On a bridge (a pier) the floor is the level above's, as the client takes it: the water under a pier is
                // set blocked, the pier is not.
                boolean bridge = settings != null && settings.length > 1 && (settings[1][x][y] & 2) != 0;
                int level = bridge ? plane + 1 : plane;
                boolean blocked = settings != null && level < settings.length && (settings[level][x][y] & 1) != 0;
                // Open water mostly is not set blocked, yet the game keeps you off it: a whole tile of water blocks (a
                // shaped tile along the shore keeps its setting).
                SceneTilePaint paint = bridge ? null : t.getSceneTilePaint();
                ext[x][y] = blocked || paint != null && water(paint.getTexture()) ? 0x200000 : 0;
            }
        }
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int x = 0; x < size; x++)
        {
            for (int y = 0; y < size; y++)
            {
                Tile t = tiles[plane][x][y];
                if (t == null) { continue; }
                WallObject w = t.getWallObject();
                if (w != null && seen.add(w) && blocks(w.getId())) { wall(x, y, w.getConfig() & 0x1F, w.getConfig() >> 6 & 3); }
                for (GameObject o : t.getGameObjects())
                {
                    if (o == null || !seen.add(o) || !blocks(o.getId())) { continue; }
                    LocalPoint at = o.getLocalLocation();
                    if (at == null) { continue; }
                    int sw = Math.floorDiv(at.getX() - (o.sizeX() - 1) * 64, 128) + extOffset, sy = Math.floorDiv(at.getY() - (o.sizeY() - 1) * 64, 128) + extOffset;
                    for (int i = 0; i < o.sizeX(); i++) { for (int j = 0; j < o.sizeY(); j++) { block(sw + i, sy + j, 0x100); } }
                }
            }
        }
    }

    /** Whether an object of this id blocks walking: not in a run of WALKABLE. */
    private static boolean blocks(int id)
    {
        Map.Entry<Integer, Integer> run = WALKABLE.floorEntry(id);
        return run == null || id > run.getValue();
    }

    /** Whether a floor texture is one of the game's water textures: 1, 208 and 130 to 189. */
    private static boolean water(int texture) { return texture == 1 || texture == 208 || texture >= 130 && texture <= 189; }

    /** A wall as the client flags it: shape 0 a straight wall, 1 and 3 a corner, 2 two sides; rotation 0 west, 1 north, 2 east, 3 south. */
    private void wall(int x, int y, int shape, int rotation)
    {
        // Per rotation the flag on the wall's tile and the one on the neighbour it faces, straight walls and corners.
        int[] side = {0x80, 0x2, 0x8, 0x20}, facing = {0x8, 0x20, 0x80, 0x2}, dx = {-1, 0, 1, 0}, dy = {0, 1, 0, -1};
        int[] corner = {0x1, 0x4, 0x10, 0x40}, cornerFacing = {0x10, 0x40, 0x1, 0x4}, cdx = {-1, 1, 1, -1}, cdy = {1, 1, -1, -1};
        if (shape == 0 || shape == 2)
        {
            block(x, y, side[rotation]); block(x + dx[rotation], y + dy[rotation], facing[rotation]);
        }
        if (shape == 2)
        {
            int r = (rotation + 1) & 3;
            block(x, y, side[r]); block(x + dx[r], y + dy[r], facing[r]);
        }
        if (shape == 1 || shape == 3)
        {
            block(x, y, corner[rotation]); block(x + cdx[rotation], y + cdy[rotation], cornerFacing[rotation]);
        }
    }

    private void block(int x, int y, int bits)
    {
        if (x >= 0 && y >= 0 && x < ext.length && y < ext.length && ext[x][y] != EXT_UNKNOWN) { ext[x][y] |= bits; }
    }

    /** How far the extended view reaches beyond the loaded area, in tiles; 0 without one. */
    int extent() { return ext == null ? 0 : extOffset; }

    /** Whether the flags of this tile are known: in the loaded area, or shown by the extended view. */
    boolean known(WorldPoint p)
    {
        int x = p.getX() - baseX, y = p.getY() - baseY;
        if (x >= 0 && y >= 0 && x < flags.length && y < flags[x].length) { return true; }
        int ex = x + extOffset, ey = y + extOffset;
        return ext != null && ex >= 0 && ey >= 0 && ex < ext.length && ey < ext.length && ext[ex][ey] != EXT_UNKNOWN;
    }

    /** The point moved into the area the flags are known for: the loaded area and the extended view around it. */
    WorldPoint clampKnown(WorldPoint p)
    {
        int reach = extent();
        int x = Math.max(baseX - reach, Math.min(baseX + flags.length - 1 + reach, p.getX()));
        int y = Math.max(baseY - reach, Math.min(baseY + flags.length - 1 + reach, p.getY()));
        return new WorldPoint(x, y, p.getPlane());
    }

    /** As find, for a predicted walk: through the loaded area's border and on through the extended view. */
    List<WorldPoint> findBeyond(WorldPoint from, Target target)
    {
        borderless = true;
        try { return find(from, target); }
        finally { borderless = false; }
    }

    /** As step, for a predicted walk: through the loaded area's border and on through the extended view. */
    WorldPoint stepBeyond(WorldPoint at, WorldPoint to)
    {
        borderless = true;
        try { return step(at, to); }
        finally { borderless = false; }
    }

    /** The NPCs that block walking, where they stand now; the collision flags do not hold them. */
    void loadBlockers(WorldView wv)
    {
        blockers.clear();
        for (NPC npc : wv.npcs())
        {
            NPCComposition c = npc.getTransformedComposition();
            if (c != null && BLOCKING_NPCS.containsKey(c.getId())) { blockers.add(npc.getWorldArea()); }
        }
    }

    /**
     * The client's route from `from` to the target: the tiles where it turns, the last where it ends (none when it
     * ends where it starts); null when nothing near the target can be reached.
     */
    List<WorldPoint> find(WorldPoint from, Target target)
    {
        // Scene coordinates of the window's first cell, and the target in scene coordinates.
        int ox = from.getX() - baseX - 64, oy = from.getY() - baseY - 64;
        Target t = target.shifted(-baseX, -baseY);
        int end = search(ox, oy, t, Integer.MAX_VALUE);
        if (end < 0)
        {
            // Out of reach: the visited tile within 10 of the target nearest to it, then with fewest steps, under 100.
            int best = Integer.MAX_VALUE;
            for (int x = t.x - 10; x <= t.x + 10; x++)
            {
                for (int y = t.y - 10; y <= t.y + 10; y++)
                {
                    int wx = x - ox, wy = y - oy, c = wx * SIZE + wy;
                    if (wx < 0 || wy < 0 || wx >= SIZE || wy >= SIZE || steps[c] < 0 || steps[c] >= 100) { continue; }
                    int dx = Math.max(0, Math.max(t.x - x, x - t.x - t.width + 1)), dy = Math.max(0, Math.max(t.y - y, y - t.y - t.height + 1));
                    int score = (dx * dx + dy * dy) * 100 + steps[c];
                    if (score < best) { best = score; end = c; }
                }
            }
            if (end < 0) { return null; }
        }
        // Back from the end: each tile entered in another direction than the one after it is a turn.
        List<WorldPoint> turns = new ArrayList<>();
        for (int c = end, last = -1; c != CENTRE; c -= DX[via[c]] * SIZE + DY[via[c]])
        {
            if (via[c] != last) { turns.add(0, new WorldPoint(c / SIZE + ox + baseX, c % SIZE + oy + baseY, plane)); }
            last = via[c];
        }
        // The game walks the first 25 turns.
        while (turns.size() > 25) { turns.remove(25); }
        return turns;
    }

    /**
     * For a target used from a distance (Target.range): the turns cut where the walk along them stops, at the end of
     * the first game tick (a step walking, two running) that leaves you within range with a line of sight to it; none
     * when you already are. Other targets' turns as they are.
     */
    List<WorldPoint> approach(WorldPoint from, Target t, List<WorldPoint> turns, boolean running)
    {
        if (turns == null || t == null || t.range <= 1) { return turns; }
        if (inRange(from, t)) { return new ArrayList<>(); }
        List<WorldPoint> out = new ArrayList<>();
        WorldPoint at = from;
        int steps = 0;
        for (WorldPoint turn : turns)
        {
            while (at.getX() != turn.getX() || at.getY() != turn.getY())
            {
                WorldPoint next = step(at, turn);
                if (next == null) { return turns; }
                at = next;
                if (++steps % (running ? 2 : 1) == 0 && inRange(at, t)) { out.add(at); return out; }
            }
            out.add(turn);
        }
        return turns;
    }

    /** Whether the target can be used from p at a distance: not on it, within its range, with a line of sight. */
    private boolean inRange(WorldPoint p, Target t)
    {
        int dx = Math.max(t.x - p.getX(), p.getX() - t.x - t.width + 1), dy = Math.max(t.y - p.getY(), p.getY() - t.y - t.height + 1);
        if (dx <= 0 && dy <= 0 || Math.max(dx, dy) > t.range) { return false; }
        int x = p.getX() - baseX, y = p.getY() - baseY;
        for (int i = 0; i < t.width; i++)
        {
            for (int j = 0; j < t.height; j++) { if (sight(x, y, t.x - baseX + i, t.y - baseY + j)) { return true; } }
        }
        return false;
    }

    /**
     * The game's line of sight between scene tiles: along the longer axis a tile at a time, the other axis at the
     * line's height rounded; blocked by a tile that blocks it and by a wall that blocks it on a side the line crosses.
     */
    private boolean sight(int x0, int y0, int x1, int y1)
    {
        int dx = x1 - x0, dy = y1 - y0;
        // Per axis the walls the line crosses, on the tile it enters: west 0x10000, east 0x1000, south 0x4000, north 0x400.
        int xFlags = 0x20000 | (dx < 0 ? 0x1000 : 0x10000), yFlags = 0x20000 | (dy < 0 ? 0x400 : 0x4000);
        boolean alongX = Math.abs(dx) > Math.abs(dy);
        int steps = Math.abs(alongX ? dx : dy), along = alongX ? x0 : y0, step = (alongX ? dx : dy) < 0 ? -1 : 1;
        int across = ((alongX ? y0 : x0) << 16) + 0x8000 - ((alongX ? dy : dx) < 0 ? 1 : 0);
        int slope = steps == 0 ? 0 : ((alongX ? dy : dx) << 16) / steps;
        for (int k = 0; k < steps; k++)
        {
            along += step;
            // Unknown tiles (-1) block it too.
            int a = across >>> 16;
            if (((alongX ? flag(along, a) : flag(a, along)) & (alongX ? xFlags : yFlags)) != 0) { return false; }
            across += slope;
            int b = across >>> 16;
            if (b != a && ((alongX ? flag(along, b) : flag(b, along)) & (alongX ? yFlags : xFlags)) != 0) { return false; }
        }
        return true;
    }

    /** The scene tiles {x, y} you can walk to from scene tile (x, y) in at most `most` steps, by the collision flags alone. */
    List<int[]> reachable(int x, int y, int most)
    {
        search(x - 64, y - 64, null, most);
        List<int[]> out = new ArrayList<>(tail);
        for (int i = 0; i < tail; i++) { out.add(new int[]{queue[i] / SIZE + x - 64, queue[i] % SIZE + y - 64}); }
        return out;
    }

    /** Cells visited by the last search, in queue. */
    private int tail;

    /**
     * The game's breadth-first search from the window's centre, at most `most` steps: the cell from which t can be
     * used (-1: none, or no t); the cells visited are queue[0, tail). Of two cells as many steps away, the first the
     * search order finds: Sunstone rocks two tiles east and two south of you are mined from their north side, as the
     * game walks (east is tried before south), not their west side.
     */
    private int search(int ox, int oy, Target t, int most)
    {
        Arrays.fill(steps, -1);
        steps[CENTRE] = 0;
        queue[0] = CENTRE;
        tail = 1;
        for (int head = 0; head < tail; head++)
        {
            int c = queue[head], x = c / SIZE + ox, y = c % SIZE + oy;
            if (t != null && t.reachedFrom(x, y, flag(x, y))) { return c; }
            for (int d = 0; d < 8 && steps[c] < most; d++)
            {
                int wx = c / SIZE + DX[d], wy = c % SIZE + DY[d], n = wx * SIZE + wy;
                if (wx >= 0 && wy >= 0 && wx < SIZE && wy < SIZE && steps[n] < 0 && open(x, y, DX[d], DY[d]))
                {
                    steps[n] = steps[c] + 1;
                    via[n] = d;
                    queue[tail++] = n;
                }
            }
        }
        return -1;
    }

    /** One step from `at` towards `to` as the game moves you: diagonally if free, else along x, else along y; null when blocked. */
    WorldPoint step(WorldPoint at, WorldPoint to)
    {
        int dx = Integer.signum(to.getX() - at.getX()), dy = Integer.signum(to.getY() - at.getY());
        return free(at, dx, dy) ? at.dx(dx).dy(dy) : dx != 0 && free(at, dx, 0) ? at.dx(dx) : dy != 0 && free(at, 0, dy) ? at.dy(dy) : null;
    }

    /** A step the collision flags allow, onto (and diagonally past) no NPC that blocks walking. */
    private boolean free(WorldPoint at, int dx, int dy)
    {
        return open(at.getX() - baseX, at.getY() - baseY, dx, dy) && !blocked(at.dx(dx).dy(dy))
            && (dx == 0 || dy == 0 || !blocked(at.dx(dx)) && !blocked(at.dy(dy)));
    }

    private boolean blocked(WorldPoint p)
    {
        for (WorldArea a : blockers) { if (a.contains(p)) { return true; } }
        return false;
    }

    /** Whether the collision flags allow a step from scene tile (x, y) by (dx, dy); a diagonal also needs both straight steps. */
    private boolean open(int x, int y, int dx, int dy)
    {
        return (flag(x + dx, y + dy) & STOP[3 * dx + dy + 4]) == 0
            && (dx == 0 || dy == 0 || (flag(x + dx, y) & STOP[3 * dx + 4]) == 0 && (flag(x, y + dy) & STOP[dy + 4]) == 0);
    }

    /**
     * The flags of a scene tile; beyond the loaded area the extended view's where known, else every tile is blocked.
     * For a predicted walk (borderless) the loaded area's border takes the extended view's flags too.
     */
    private int flag(int x, int y)
    {
        boolean inner = x >= 0 && y >= 0 && x < flags.length && y < flags[x].length;
        if (inner && !(borderless && ext != null && (x < BORDER || y < BORDER || x >= flags.length - BORDER || y >= flags[x].length - BORDER)))
        { return flags[x][y]; }
        if (ext == null || !borderless && !inner) { return inner ? flags[x][y] : -1; }
        int ex = x + extOffset, ey = y + extOffset;
        if (ex < 0 || ey < 0 || ex >= ext.length || ey >= ext.length) { return -1; }
        int f = ext[ex][ey];
        return f == EXT_UNKNOWN ? -1 : f;
    }

    /** Lines "id=value", maybe followed by " // a comment"; empty when the bundled list cannot be read. */
    private static Map<Integer, Integer> read(String name)
    {
        Map<Integer, Integer> map = new HashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(RouteFinder.class.getResourceAsStream(name), StandardCharsets.UTF_8)))
        {
            in.lines().map(line -> line.split("[= ]")).forEach(kv -> map.put(Integer.valueOf(kv[0]), Integer.valueOf(kv[1])));
        }
        catch (IOException | RuntimeException ex)
        {
            map.clear();
        }
        return map;
    }

    /**
     * What a route leads to, from its south-west tile: a tile, an object's footprint with its shape and rotation, or a
     * character's footprint. Made in world coordinates; find moves it into the scene.
     */
    static final class Target
    {
        /** Shapes besides the client's object shapes (0 to 22). */
        static final int TILE = -1, ACTOR = -2;
        /** Per side of a target (west, north, east, south): the wall of a tile on that side that faces the target. */
        private static final int[] FACING = {0x8, 0x20, 0x80, 0x2};
        /** Flags of a blocked tile, which cannot reach along or around a wall. */
        private static final int BLOCKED = 0x12c0100;
        final int x, y, width, height, shape, rotation;
        /** Bit per side (west 1, north 2, east 4, south 8) from which the target cannot be used. */
        final int closedSides;
        /** A character to walk to, which moves. */
        final Actor actor;
        /** How far away it can be used from, with a line of sight; 1: only from beside it, by the rules of its kind. */
        int range = 1;

        Target(int x, int y, int width, int height, int shape, int rotation, int closedSides, Actor actor)
        {
            this.x = x; this.y = y; this.width = width; this.height = height;
            this.shape = shape; this.rotation = rotation; this.closedSides = closedSides; this.actor = actor;
        }

        static Target tile(WorldPoint p) { return new Target(p.getX(), p.getY(), 1, 1, TILE, 0, 0, null); }

        static Target of(Actor a)
        {
            WorldPoint p = a.getWorldLocation();
            NPCComposition c = a instanceof NPC ? ((NPC) a).getComposition() : null;
            int size = c != null ? c.getSize() : 1;
            Target t = new Target(p.getX(), p.getY(), size, size, ACTOR, 0, 0, a);
            // Bankers are used from two tiles away, across the bank booth between you. An NPC that changes form (such
            // as the Grand Exchange's bankers) has its options only in its current form.
            NPCComposition now = a instanceof NPC ? ((NPC) a).getTransformedComposition() : null;
            String[] actions = now != null ? now.getActions() : c != null ? c.getActions() : null;
            if (actions != null && Arrays.asList(actions).contains("Bank")) { t.range = 2; }
            return t;
        }

        /** An object with the client's config (shape in bits 0-4, rotation in bits 6-7). */
        static Target object(WorldPoint p, int width, int height, int config, int id)
        {
            int shape = config & 0x1F, rotation = config >> 6 & 3;
            // Only these shapes have sides that cannot be used; the list holds them for rotation 0, which turns with the object.
            int sides = shape == 10 || shape == 11 || shape == 22 ? BLOCKED_SIDES.getOrDefault(id, 0) : 0;
            // Shape 7 is shape 6 seen from the opposite corner.
            return new Target(p.getX(), p.getY(), width, height, shape, shape == 7 ? rotation + 2 & 3 : rotation,
                (sides << rotation + 1 | sides >> 3 - rotation) & 15, null);
        }

        Target shifted(int dx, int dy)
        {
            Target t = new Target(x + dx, y + dy, width, height, shape, rotation, closedSides, actor);
            t.range = range;
            return t;
        }

        boolean same(Target o)
        {
            return o != null && x == o.x && y == o.y && width == o.width && height == o.height && shape == o.shape && rotation == o.rotation;
        }

        /** Whether the target can be used from tile (x, y), which has these collision flags, by the client's rules for its kind. */
        boolean reachedFrom(int x, int y, int flags)
        {
            boolean alongX = x >= this.x && x < this.x + width, alongY = y >= this.y && y < this.y + height;
            if (alongX && alongY) { return shape != ACTOR; }
            // The side of the footprint (x, y) lies against: west, north, east or south.
            int side = alongY && x == this.x - 1 ? 0 : alongX && y == this.y + height ? 1 : alongY && x == this.x + width ? 2 : alongX && y == this.y - 1 ? 3 : -1;
            if (side < 0) { return false; }
            // A wall of (x, y) faces the target; for walls also a blocked tile counts. Turn: that side counted from the object's front.
            boolean wall = (flags & FACING[side]) != 0, closed = (flags & (FACING[side] | BLOCKED)) != 0;
            int turn = side - rotation & 3;
            switch (shape)
            {
                // A straight wall: from its front, or along it.
                case 0: return turn == 0 || turn % 2 == 1 && !closed;
                // An L-shaped wall: from its two fronts, or around it.
                case 2: return turn < 2 || !closed;
                // A diagonal wall decoration: from its two open sides.
                case 6: case 7: return turn > 1 && !wall;
                // Footprints: from any side without a wall in between that the object leaves open.
                case 8: case 9: case 10: case 11: case 22: case ACTOR: return !wall && (closedSides >> side & 1) == 0;
                // A tile, and the other shapes: only on it.
                default: return false;
            }
        }
    }
}
