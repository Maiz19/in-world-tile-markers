package com.hdworldmarkers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * Routes and steps as the game takes them, on the collision flags of your level. A route is the client's
 * breadth-first search from your tile, at most 64 tiles each way, trying the steps west, east, south, north and then
 * the diagonals, until it stands on a tile from which the target can be used. Without one, it ends on the visited tile
 * within 10 tiles of the target that lies nearest to it, then takes fewest steps, under 100. Of a route the game keeps
 * the tiles where it turns, at most 25, and walks from turn to turn: diagonally where it can, else along x, else y.
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
     * Path Marker's lists (see THIRD_PARTY_NOTICES.md), which the client's API does not offer: per object the sides it
     * cannot be used from (bits north 1, east 2, south 4, west 8, unrotated), and the NPCs that block walking.
     */
    private static final Map<Integer, Integer> BLOCKED_SIDES = read("loc_blocking.txt"), BLOCKING_NPCS = read("npc_blocking.txt");
    private final int[] steps = new int[SIZE * SIZE], via = new int[SIZE * SIZE], queue = new int[SIZE * SIZE];
    private final List<WorldArea> blockers = new ArrayList<>();
    private int[][] flags;
    private int baseX, baseY, plane;

    /** Takes the collision flags of the level you are on; false while there are none. */
    boolean load(WorldView wv)
    {
        CollisionData[] maps = wv.getCollisionMaps();
        if (maps == null) { return false; }
        plane = wv.getPlane();
        flags = maps[plane].getFlags();
        baseX = wv.getBaseX();
        baseY = wv.getBaseY();
        return true;
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
     * The client's breadth-first search from the window's centre, at most `most` steps: the cell from which t can be
     * used (-1: none, or no t); the cells visited are queue[0, tail).
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

    /** The flags of a scene tile; beyond the loaded area every tile is blocked. */
    private int flag(int x, int y)
    {
        return x >= 0 && y >= 0 && x < flags.length && y < flags[x].length ? flags[x][y] : -1;
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

        Target(int x, int y, int width, int height, int shape, int rotation, int closedSides, Actor actor)
        {
            this.x = x; this.y = y; this.width = width; this.height = height;
            this.shape = shape; this.rotation = rotation; this.closedSides = closedSides; this.actor = actor;
        }

        static Target tile(WorldPoint p) { return new Target(p.getX(), p.getY(), 1, 1, TILE, 0, 0, null); }

        static Target of(Actor a)
        {
            WorldPoint p = a.getWorldLocation();
            int size = a instanceof NPC ? ((NPC) a).getComposition().getSize() : 1;
            return new Target(p.getX(), p.getY(), size, size, ACTOR, 0, 0, a);
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

        Target shifted(int dx, int dy) { return new Target(x + dx, y + dy, width, height, shape, rotation, closedSides, actor); }

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
