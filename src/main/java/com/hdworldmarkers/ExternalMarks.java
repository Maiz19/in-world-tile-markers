package com.hdworldmarkers;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.events.ProfileChanged;

/**
 * Marks sent by other plugins, drawn by HD World Markers like its own. Plugins need no compile-time
 * dependency: they post a {@link PluginMessage} on the event bus with namespace
 * {@value #NAMESPACE}. Without HD World Markers installed the message simply goes unanswered.
 *
 * <ul>
 * <li>{@code tiles}: {@code owner} (String) and {@code tiles}, a list of maps with {@code point}
 * (WorldPoint, or {@code x}, {@code y}, {@code plane} numbers; in an instance, such as a player-owned house, as Ground
 * Markers saves it (its template) or as it is there (getWorldLocation)), {@code color}, optional {@code fill}
 * (Color or ARGB int), {@code width} (border pixels, default 2), {@code size} (tiles, default 1)
 * and {@code label}. Replaces all tiles of that owner.</li>
 * <li>{@code npcs}: {@code owner} and {@code npcs}, a list of maps with {@code npc} (NPC) or
 * {@code index} (NPC index), {@code style} ({@code hull}, {@code outline}, {@code clickbox},
 * {@code tile} or {@code truetile}, default hull), {@code color}, optional {@code fill} and
 * {@code width}. Replaces all NPC marks of that owner.</li>
 * <li>{@code pies}: {@code owner} and {@code pies}, a list of maps with {@code point} (as tiles), {@code color} (the
 * filled part), optional {@code border} (its ring) and {@code width}, {@code progress} (0 to 1) or {@code start} and
 * {@code end} (System.currentTimeMillis times: filled from start to end), {@code size} (diameter in local units, 128
 * a tile; default 64) and {@code height} (local units above the ground). Replaces all pies of that owner.</li>
 * <li>{@code lines}: {@code owner} and {@code lines}, a list of maps with {@code points} (a list of points as tiles:
 * WorldPoint, or maps with {@code x}, {@code y}, {@code plane}), {@code color} and {@code width} (border pixels, default
 * 2). A line on the ground through the tiles' centres, as a path: points next to each other (diagonals too) are
 * joined, a jump between others (a teleport) breaks it there. In an instance a point found in several places is left
 * out. Optional {@code arrows} (a number n): a small filled arrowhead on the ground every n tiles counted from the line's
 * end (so they stay put while the sender trims the start as the player walks), pointing the way the points go.
 * Replaces all lines of that owner; at most {@value #MAX_PER_OWNER} points in all.</li>
 * <li>{@code clear}: {@code owner}. Removes everything of that owner.</li>
 * </ul>
 * Marks stay until their owner replaces or clears them.
 */
@Singleton
final class ExternalMarks
{
    static final String NAMESPACE = "hd-world-markers";
    /** Per owner, so one plugin cannot flood the renderer. */
    static final int MAX_PER_OWNER = 1000;

    private final Client client;
    private final Map<String, List<Map<String, Object>>> tiles = new ConcurrentHashMap<>(), npcs = new ConcurrentHashMap<>(),
        pies = new ConcurrentHashMap<>(), lines = new ConcurrentHashMap<>();
    /**
     * Each owner's tiles and pies as markers for the scene they were made for, made again after new marks or a scene load:
     * every tick they were made anew (an instance's tiles searched its template chunks for each). Pies that fill by the
     * clock are made every tick.
     */
    private final Map<String, Made> tileMarkers = new ConcurrentHashMap<>(), pieMarkers = new ConcurrentHashMap<>(),
        lineMarkers = new ConcurrentHashMap<>();

    /**
     * Markers with the list and floor they were made for: kept while those are the owner's list and your floor. A list
     * replaced from another thread while a tick made its markers is then made again the next tick, rather than kept; up
     * or down stairs (no scene load) the other floor's points are made.
     */
    private static final class Made
    {
        final List<Map<String, Object>> from;
        final int plane;
        final List<Marker> markers;

        Made(List<Map<String, Object>> from, int plane, List<Marker> markers) { this.from = from; this.plane = plane; this.markers = markers; }

        boolean of(List<Map<String, Object>> list, int plane) { return from == list && this.plane == plane; }
    }

    @Inject
    ExternalMarks(Client client) { this.client = client; }

    void clear() { tiles.clear(); npcs.clear(); pies.clear(); lines.clear(); tileMarkers.clear(); pieMarkers.clear(); lineMarkers.clear(); }

    @Subscribe
    public void onProfileChanged(ProfileChanged event) { clear(); }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState state = event.getGameState();
        if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
        {
            clear();
        }
        // Another scene: the tiles are placed in it anew.
        else if (state == GameState.LOADING) { tileMarkers.clear(); pieMarkers.clear(); lineMarkers.clear(); }
    }

    @Subscribe
    public void onPluginMessage(PluginMessage message)
    {
        if (!NAMESPACE.equals(message.getNamespace()) || message.getData() == null) { return; }
        Object owner = message.getData().get("owner");
        if (!(owner instanceof String) || ((String) owner).isEmpty()) { return; }
        switch (message.getName())
        {
            case "tiles": tiles.put((String) owner, entries(message.getData().get("tiles"))); tileMarkers.remove(owner); break;
            case "npcs": npcs.put((String) owner, entries(message.getData().get("npcs"))); break;
            case "pies": pies.put((String) owner, entries(message.getData().get("pies"))); pieMarkers.remove(owner); break;
            case "lines": lines.put((String) owner, lines(message.getData().get("lines"))); lineMarkers.remove(owner); break;
            case "clear":
                tiles.remove(owner); npcs.remove(owner); pies.remove(owner); lines.remove(owner);
                tileMarkers.remove(owner); pieMarkers.remove(owner); lineMarkers.remove(owner);
                break;
            default: break;
        }
    }

    /** The owner's entries as an immutable copy: the sender may reuse its own lists. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Object value)
    {
        if (!(value instanceof Collection)) { return Collections.emptyList(); }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : (Collection<?>) value)
        {
            if (result.size() >= MAX_PER_OWNER) { break; }
            if (entry instanceof Map) { result.add(Collections.unmodifiableMap(new HashMap<>((Map<String, Object>) entry))); }
        }
        return Collections.unmodifiableList(result);
    }

    /** The owner's lines as an immutable copy, each with its points copied too: at most MAX_PER_OWNER points in all. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Object value)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        int left = MAX_PER_OWNER;
        for (Map<String, Object> line : entries(value))
        {
            if (!(line.get("points") instanceof Collection) || left <= 0) { continue; }
            List<Object> points = new ArrayList<>();
            for (Object p : (Collection<Object>) line.get("points")) { if (left-- > 0) { points.add(p instanceof Map ? new HashMap<>((Map<String, Object>) p) : p); } }
            Map<String, Object> copy = new HashMap<>(line);
            copy.put("points", Collections.unmodifiableList(points));
            result.add(Collections.unmodifiableMap(copy));
        }
        return Collections.unmodifiableList(result);
    }

    void collect(List<Marker> markerOut, List<ModelTarget> modelOut)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) { return; }
        int plane = wv.getPlane();
        for (Map.Entry<String, List<Map<String, Object>>> owner : tiles.entrySet())
        {
            List<Map<String, Object>> list = owner.getValue();
            Made made = tileMarkers.get(owner.getKey());
            if (made == null || !made.of(list, plane))
            {
                made = new Made(list, plane, tileMarkers(wv, owner.getKey(), list));
                tileMarkers.put(owner.getKey(), made);
            }
            onFloor(made.markers, plane, markerOut);
        }
        for (Map.Entry<String, List<Map<String, Object>>> owner : lines.entrySet())
        {
            List<Map<String, Object>> list = owner.getValue();
            Made made = lineMarkers.get(owner.getKey());
            if (made == null || !made.of(list, plane))
            {
                made = new Made(list, plane, lineMarkers(wv, owner.getKey(), list));
                lineMarkers.put(owner.getKey(), made);
            }
            onFloor(made.markers, plane, markerOut);
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, List<Map<String, Object>>> owner : pies.entrySet())
        {
            List<Map<String, Object>> list = owner.getValue();
            Made made = pieMarkers.get(owner.getKey());
            if (made == null || !made.of(list, plane) || list.stream().anyMatch(ExternalMarks::timed))
            {
                made = new Made(list, plane, pieMarkers(wv, owner.getKey(), list, now));
                pieMarkers.put(owner.getKey(), made);
            }
            onFloor(made.markers, plane, markerOut);
        }
        for (Map.Entry<String, List<Map<String, Object>>> owner : npcs.entrySet())
        {
            for (Map<String, Object> t : owner.getValue())
            {
                NPC npc = npc(wv, t);
                Color color = color(t.get("color"), null);
                if (npc == null || color == null) { continue; }
                Color fill = color(t.get("fill"), Marker.BLACK_FILL);
                int width = number(t.get("width"), 2);
                Object style = t.get("style");
                String key = "ext:" + owner.getKey() + ":npc:" + npc.getIndex() + ":" + style;
                NPCComposition composition = npc.getTransformedComposition();
                int size = composition == null ? 1 : Math.max(1, Math.min(64, composition.getSize()));
                if ("tile".equals(style) || "truetile".equals(style))
                {
                    LocalPoint local = "tile".equals(style) ? npc.getLocalLocation()
                        : LocalPoint.fromWorld(wv, npc.getWorldLocation());
                    if (local == null) { continue; }
                    if ("truetile".equals(style)) { local = local.plus((size - 1) * 64, (size - 1) * 64); }
                    Marker m = new Marker(key, local, wv.getPlane(), size, size, color, fill, width, null);
                    m.layer = Marker.EXTERNAL;
                    markerOut.add(m);
                }
                else if ("outline".equals(style)) { modelOut.add(ModelTarget.npcOutline(key, npc, color, width)); }
                else if ("clickbox".equals(style))
                {
                    modelOut.add(ModelTarget.npcClickbox(key, npc, color, fill, width, client));
                }
                else { modelOut.add(ModelTarget.npc(key, npc, color, fill, width)); }
            }
        }
    }

    /** The markers on the floor you are on, as for your own marks: another floor's would float over this one. */
    private static void onFloor(List<Marker> markers, int plane, List<Marker> out)
    {
        for (Marker m : markers) { if (m.plane == plane) { out.add(m); } }
    }

    /** An owner's tiles as markers in this world view. */
    private static List<Marker> tileMarkers(WorldView wv, String owner, List<Map<String, Object>> entries)
    {
        List<Marker> out = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> t : entries)
        {
            WorldPoint point = point(t);
            Color color = color(t.get("color"), null);
            if (point == null || color == null) { continue; }
            int size = Math.max(1, Math.min(64, number(t.get("size"), 1)));
            for (WorldPoint instance : places(wv, point))
            {
                LocalPoint local = LocalPoint.fromWorld(wv, instance);
                if (local == null) { continue; }
                // A size-n area is centered like NPC footprints: its south-west tile is the point.
                local = local.plus((size - 1) * 64, (size - 1) * 64);
                Object label = t.get("label");
                Marker m = new Marker("ext:" + owner + ":" + n++, local, instance.getPlane(), size, size, color,
                    color(t.get("fill"), Marker.BLACK_FILL), number(t.get("width"), 2), label instanceof String ? (String) label : null);
                m.layer = Marker.EXTERNAL;
                out.add(m);
            }
        }
        return out;
    }

    /**
     * An owner's lines as one marker per tile (Marker.path): from halfway to the tile before, through its centre, to
     * halfway to the next, each end aimed at that tile's centre, so the pieces meet without a gap and each tile is
     * culled on its own. Only neighbours on one floor are joined; a tile with neither is left out.
     */
    @SuppressWarnings("unchecked")
    private static List<Marker> lineMarkers(WorldView wv, String owner, List<Map<String, Object>> entries)
    {
        List<Marker> out = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> line : entries)
        {
            Color color = color(line.get("color"), null);
            if (color == null) { continue; }
            int width = number(line.get("width"), 2), arrows = Math.max(0, number(line.get("arrows"), 0));
            // Where each point is in this world view: one place, or null (none or several) to break the line there.
            List<WorldPoint> at = new ArrayList<>();
            for (Object p : (List<Object>) line.get("points"))
            {
                WorldPoint point = p instanceof WorldPoint ? (WorldPoint) p : p instanceof Map ? point((Map<String, Object>) p) : null;
                Collection<WorldPoint> places = point == null ? Collections.emptyList() : places(wv, point);
                WorldPoint place = places.size() == 1 ? places.iterator().next() : null;
                if (place == null || at.isEmpty() || !place.equals(at.get(at.size() - 1))) { at.add(place); }
            }
            for (int i = 0; i < at.size(); i++)
            {
                WorldPoint p = at.get(i), prev = i > 0 && joined(at.get(i - 1), p) ? at.get(i - 1) : null,
                    next = i + 1 < at.size() && joined(p, at.get(i + 1)) ? at.get(i + 1) : null;
                LocalPoint local = p == null || prev == null && next == null ? null : LocalPoint.fromWorld(wv, p);
                if (local == null) { continue; }
                int[] path = new int[2 * (2 + (prev != null ? 1 : 0) + 1 + (next != null ? 1 : 0))];
                int k = 0;
                // Without a tile before it starts at the centre: its aim the centre itself, for a square end.
                if (prev != null)
                {
                    int dx = prev.getX() - p.getX(), dy = prev.getY() - p.getY();
                    path[k++] = dx * 128; path[k++] = dy * 128; path[k++] = dx * 64; path[k++] = dy * 64;
                }
                else { k += 2; }
                k += 2;
                if (next != null)
                {
                    int dx = next.getX() - p.getX(), dy = next.getY() - p.getY();
                    path[k++] = dx * 64; path[k++] = dy * 64; path[k++] = dx * 128; path[k] = dy * 128;
                }
                Marker m = new Marker("ext:" + owner + ":line:" + n++, local, p.getPlane(), 1, 1, color, null, width, null);
                m.path = path;
                m.layer = Marker.EXTERNAL;
                out.add(m);
                // Counted from the line's far end, which stays as the sender trims the near end: the arrows stay put.
                if (arrows > 0 && next != null && (at.size() - 1 - i) % arrows == arrows / 2)
                {
                    Marker arrow = new Marker("ext:" + owner + ":arrow:" + n, local, p.getPlane(), 1, 1, color, color, 1, null);
                    arrow.area = arrow(next.getX() - p.getX(), next.getY() - p.getY());
                    arrow.layer = Marker.EXTERNAL;
                    out.add(arrow);
                }
            }
        }
        return out;
    }

    /** An arrowhead's size in local units (128 a tile): its tip ahead of the centre, its back behind, half its width. */
    private static final int TIP = 26, BACK = 22, HALF = 20;

    /** A filled arrowhead around the tile's centre pointing (dx, dy) (a step to a neighbour): tip, back left, back right. */
    static int[] arrow(int dx, int dy)
    {
        double length = Math.hypot(dx, dy), ux = dx / length, uy = dy / length;
        return new int[]{(int) Math.round(ux * TIP), (int) Math.round(uy * TIP),
            (int) Math.round(-ux * BACK - uy * HALF), (int) Math.round(-uy * BACK + ux * HALF),
            (int) Math.round(-ux * BACK + uy * HALF), (int) Math.round(-uy * BACK - ux * HALF)};
    }

    /** Whether a line joins these points: next to each other (diagonals too) on one floor. */
    private static boolean joined(WorldPoint a, WorldPoint b)
    {
        return a != null && b != null && a.getPlane() == b.getPlane() && Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY())) == 1;
    }

    /** Points around a circle, clockwise from the top. */
    private static final int ROUND = 48;

    /**
     * An owner's timer pies as RuneLite's ProgressPieComponent draws them, standing towards the camera: the filled part
     * clockwise from the top, then the ring. Sized in local units, so they grow and shrink with the world as the camera
     * zooms.
     */
    private static List<Marker> pieMarkers(WorldView wv, String owner, List<Map<String, Object>> entries, long now)
    {
        List<Marker> out = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> p : entries)
        {
            WorldPoint point = point(p);
            Color fill = color(p.get("color"), null), border = color(p.get("border"), null);
            if (point == null || fill == null && border == null) { continue; }
            double progress = progress(p, now);
            float radius = Math.max(8, Math.min(1024, number(p.get("size"), 64))) / 2f;
            int lift = number(p.get("height"), 0);
            for (WorldPoint place : places(wv, point))
            {
                LocalPoint local = LocalPoint.fromWorld(wv, place);
                if (local == null) { continue; }
                String key = "ext:" + owner + ":pie:" + n++;
                if (fill != null && progress > 0) { out.add(round(key, local, place.getPlane(), lift, radius, progress, null, fill, 0)); }
                if (border != null)
                {
                    out.add(round(key + ":ring", local, place.getPlane(), lift, radius, 1, border, null, number(p.get("width"), 1)));
                }
            }
        }
        return out;
    }

    /** A circle, or with part below 1 a pie's filled part from its centre: clockwise from the top, as on the screen. */
    private static Marker round(String key, LocalPoint at, int plane, int lift, float radius, double part, Color color, Color fill,
        double width)
    {
        boolean whole = part >= 1;
        int steps = whole ? ROUND : Math.max(1, (int) Math.ceil(part * ROUND)), n = whole ? ROUND : steps + 2;
        float[] x = new float[n], y = new float[n];
        // A part keeps its centre first, at 0.
        for (int i = 0, j = whole ? 0 : 1; j < n; i++, j++)
        {
            double angle = 2 * Math.PI * (whole ? (double) i / ROUND : part * i / steps);
            x[j] = (float) (Math.sin(angle) * radius);
            y[j] = (float) (-Math.cos(angle) * radius);
        }
        Marker m = new Marker(key, at, plane, 1, 1, color, fill, width, null);
        m.offX = x; m.offY = y; m.lift = lift; m.layer = Marker.PIE;
        return m;
    }

    /** Whether a pie fills by the clock (start and end) rather than by its progress. */
    private static boolean timed(Map<String, Object> p) { return p.get("start") instanceof Number && p.get("end") instanceof Number; }

    /** How full a pie is, 0 to 1: its progress, or how far now is from its start to its end. */
    private static double progress(Map<String, Object> p, long now)
    {
        double part;
        if (timed(p))
        {
            double start = ((Number) p.get("start")).doubleValue(), end = ((Number) p.get("end")).doubleValue();
            part = end > start ? (now - start) / (end - start) : 1;
        }
        else { part = p.get("progress") instanceof Number ? ((Number) p.get("progress")).doubleValue() : 0; }
        return part >= 1 ? 1 : part > 0 ? part : 0;
    }

    /** Where a world point is in this world view: in an instance every place its template chunk is, else the point itself. */
    private static Collection<WorldPoint> places(WorldView wv, WorldPoint point)
    {
        Collection<WorldPoint> places = WorldPoint.toLocalInstance(wv, point);
        return places.isEmpty() ? Collections.singletonList(point) : places;
    }

    private static WorldPoint point(Map<String, Object> t)
    {
        Object p = t.get("point");
        if (p instanceof WorldPoint) { return (WorldPoint) p; }
        if (t.get("x") instanceof Number && t.get("y") instanceof Number)
        {
            return new WorldPoint(number(t.get("x"), 0), number(t.get("y"), 0), number(t.get("plane"), 0));
        }
        return null;
    }

    private static NPC npc(WorldView wv, Map<String, Object> t)
    {
        Object npc = t.get("npc");
        if (npc instanceof NPC) { return ((NPC) npc).getWorldView() == wv ? (NPC) npc : null; }
        Object index = t.get("index");
        return index instanceof Number ? wv.npcs().byIndex(((Number) index).intValue()) : null;
    }

    private static Color color(Object value, Color fallback)
    {
        if (value instanceof Color) { return (Color) value; }
        if (value instanceof Number) { return new Color(((Number) value).intValue(), true); }
        return fallback;
    }

    private static int number(Object value, int fallback)
    {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

}
