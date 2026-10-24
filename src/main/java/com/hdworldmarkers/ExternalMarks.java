package com.hdworldmarkers;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.util.Text;

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
 * <li>{@code npcs}, {@code players}, {@code objects}, {@code items}: {@code owner} and a list of that name, of maps
 * with what to highlight ({@code npc} (NPC) or {@code index}; {@code player} (Player) or {@code name}; {@code object}
 * (TileObject) or {@code point} and {@code id}; {@code point} and {@code id} of a ground item), {@code style} or
 * {@code styles} (a list) of {@code outline}, {@code hull}, {@code clickbox}, {@code tile}, {@code truetile},
 * {@code swtile} and {@code swtruetile} (default hull), {@code color}, optional {@code fill} and {@code width}, drawn as
 * Interact highlight draws them (Highlights). Each replaces all marks of that owner of its kind.</li>
 * <li>{@code pies}: {@code owner} and {@code pies}, a list of maps with {@code point} (as tiles), {@code color} (the
 * filled part), optional {@code border} (its ring) and {@code width}, {@code progress} (0 to 1) or {@code start} and
 * {@code end} (System.currentTimeMillis times: filled from start to end), {@code size} (diameter in local units, 128
 * a tile; default 64) and {@code height} (local units above the ground). Instead of a point a pie can follow an NPC
 * or player ({@code npc} or {@code index}, {@code player} or {@code name}), by default at the top of its model.
 * Replaces all pies of that owner.</li>
 * <li>{@code texts}: {@code owner} and {@code texts}, a list of maps with {@code text}, {@code color} and where (a
 * point, or an NPC or player as for pies), optional {@code height}. Text on its own, as a tile's label. Replaces all
 * texts of that owner.</li>
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
@RequiredArgsConstructor(onConstructor_ = @Inject)
final class ExternalMarks
{
    static final String NAMESPACE = "hd-world-markers";
    /** Per owner, so one plugin cannot flood the renderer. */
    static final int MAX_PER_OWNER = 1000;

    private final Client client;
    private final Map<String, List<Map<String, Object>>> tiles = new ConcurrentHashMap<>(), pies = new ConcurrentHashMap<>(),
        lines = new ConcurrentHashMap<>(), texts = new ConcurrentHashMap<>();
    /** Per kind of highlight (npcs, players, objects, items), each owner's list. */
    private final Map<String, Map<String, List<Map<String, Object>>>> highlights = new ConcurrentHashMap<>();
    private static final List<String> HIGHLIGHTS = Arrays.asList("npcs", "players", "objects", "items");
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
    @RequiredArgsConstructor
    private static final class Made
    {
        final List<Map<String, Object>> from;
        final int plane;
        final List<Marker> markers;

        boolean of(List<Map<String, Object>> list, int plane) { return from == list && this.plane == plane; }
    }

    void clear() { tiles.clear(); highlights.clear(); pies.clear(); lines.clear(); texts.clear(); tileMarkers.clear(); pieMarkers.clear(); lineMarkers.clear(); }

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
        Map<String, Object> data = message.getData();
        if (!NAMESPACE.equals(message.getNamespace()) || data == null || !(data.get("owner") instanceof String)) { return; }
        String owner = (String) data.get("owner");
        if (owner.isEmpty()) { return; }
        switch (message.getName())
        {
            case "tiles": tiles.put(owner, entries(data.get("tiles"))); tileMarkers.remove(owner); break;
            case "npcs": case "players": case "objects": case "items":
                highlights.computeIfAbsent(message.getName(), k -> new ConcurrentHashMap<>()).put(owner, entries(data.get(message.getName())));
                break;
            case "pies": pies.put(owner, entries(data.get("pies"))); pieMarkers.remove(owner); break;
            case "texts": texts.put(owner, entries(data.get("texts"))); break;
            case "lines": lines.put(owner, lines(data.get("lines"))); lineMarkers.remove(owner); break;
            case "clear":
                tiles.remove(owner); pies.remove(owner); lines.remove(owner); texts.remove(owner);
                for (Map<String, List<Map<String, Object>>> kind : highlights.values()) { kind.remove(owner); }
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

    /** Each owner's marks of one kind as markers on this floor, made again when its list or the plane changed, or always says so. */
    private static void made(Map<String, List<Map<String, Object>>> kind, Map<String, Made> cache, int plane, Predicate<List<Map<String, Object>>> always,
        BiFunction<String, List<Map<String, Object>>, List<Marker>> make, List<Marker> out)
    {
        for (Map.Entry<String, List<Map<String, Object>>> owner : kind.entrySet())
        {
            List<Map<String, Object>> list = owner.getValue();
            Made made = cache.get(owner.getKey());
            if (made == null || !made.of(list, plane) || always.test(list))
            {
                made = new Made(list, plane, make.apply(owner.getKey(), list));
                cache.put(owner.getKey(), made);
            }
            onFloor(made.markers, plane, out);
        }
    }

    /** The marks of the moment: tiles, lines and pies as markers, highlights' models; outlines in other world views (a boat) to elsewhereOut. */
    void collect(List<Marker> markerOut, List<ModelTarget> modelOut, List<ModelTarget> elsewhereOut)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) { return; }
        int plane = wv.getPlane();
        made(tiles, tileMarkers, plane, l -> false, (o, l) -> tileMarkers(wv, o, l), markerOut);
        made(lines, lineMarkers, plane, l -> false, (o, l) -> lineMarkers(wv, o, l), markerOut);
        long now = System.currentTimeMillis();
        // Pies that fill by the clock or follow a character are made anew each time.
        made(pies, pieMarkers, plane, l -> l.stream().anyMatch(p -> timed(p) || follows(p)), (o, l) -> pieMarkers(wv, o, l, now), markerOut);
        for (Map.Entry<String, List<Map<String, Object>>> owner : texts.entrySet())
        {
            int n = 0;
            for (Map<String, Object> t : owner.getValue())
            {
                Color color = color(t.get("color"), null);
                Object text = t.get("text");
                if (color == null || !(text instanceof String) || ((String) text).isEmpty()) { continue; }
                for (Place at : places(wv, t))
                {
                    // No border and no fill: only the label, drawn as a tile's.
                    Marker m = new Marker("ext:" + owner.getKey() + ":text:" + n++, at.point, at.plane, 1, 1, color, null, 0, (String) text);
                    m.lift = number(t.get("height"), at.lift);
                    m.follow = at.actor;
                    m.layer = Marker.EXTERNAL;
                    if (m.plane == plane || at.actor != null) { markerOut.add(m); }
                }
            }
        }
        for (String kind : HIGHLIGHTS)
        {
            Map<String, List<Map<String, Object>>> owners = highlights.get(kind);
            if (owners == null) { continue; }
            for (Map.Entry<String, List<Map<String, Object>>> owner : owners.entrySet())
            {
                Set<String> keys = new HashSet<>();
                for (Map<String, Object> t : owner.getValue())
                {
                    Color color = color(t.get("color"), null);
                    if (color == null) { continue; }
                    Color fill = color(t.get("fill"), Marker.BLACK_FILL);
                    int width = number(t.get("width"), 2), styles = styles(t);
                    for (Object thing : things(kind, wv, t))
                    {
                        String key = "ext:" + owner.getKey() + ":" + Highlights.key(thing);
                        // The same thing twice in a list keeps both.
                        if (!keys.add(key)) { key += ":" + keys.size(); keys.add(key); }
                        Highlights.models(modelOut, elsewhereOut, wv, client, key, thing, styles, color, fill, width);
                        Highlights.tiles(markerOut, Marker.EXTERNAL, key, thing, styles, color, fill, width);
                    }
                }
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
            Color fill = color(p.get("color"), null), border = color(p.get("border"), null);
            if (fill == null && border == null) { continue; }
            double progress = progress(p, now);
            float radius = Math.max(8, Math.min(1024, number(p.get("size"), 64))) / 2f;
            for (Place at : places(wv, p))
            {
                int lift = number(p.get("height"), at.lift);
                String key = "ext:" + owner + ":pie:" + n++;
                if (fill != null && progress > 0) { out.add(round(key, at, lift, radius, progress, null, fill, 0)); }
                if (border != null) { out.add(round(key + ":ring", at, lift, radius, 1, border, null, number(p.get("width"), 1))); }
            }
        }
        return out;
    }

    /** A circle, or with part below 1 a pie's filled part from its centre: clockwise from the top, as on the screen. */
    private static Marker round(String key, Place at, int lift, float radius, double part, Color color, Color fill, double width)
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
        Marker m = new Marker(key, at.point, at.plane, 1, 1, color, fill, width, null);
        m.offX = x; m.offY = y; m.lift = lift; m.follow = at.actor; m.layer = Marker.PIE;
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

    /** A highlight's styles: style, or styles (a list); hull when neither names one. */
    private static int styles(Map<String, Object> t)
    {
        int bits = 0;
        if (t.get("style") instanceof String) { bits |= Highlights.named((String) t.get("style")); }
        if (t.get("styles") instanceof Collection) { for (Object s : (Collection<?>) t.get("styles")) { if (s instanceof String) { bits |= Highlights.named((String) s); } } }
        return t.get("style") == null && t.get("styles") == null ? Highlights.HULL : bits;
    }

    /** What a highlight is of, where it is now: none while it is not there; an object or item in every place an instance shows its tile. */
    private List<Object> things(String kind, WorldView wv, Map<String, Object> t)
    {
        List<Object> out = new ArrayList<>();
        switch (kind)
        {
            case "npcs": case "players":
                Actor a = actor(wv, t);
                if (a != null && ("npcs".equals(kind) ? a instanceof NPC : a instanceof Player)) { out.add(a); }
                break;
            default:
                Object object = t.get("object"), id = t.get("id");
                if ("objects".equals(kind) && object instanceof TileObject) { if (here((TileObject) object)) { out.add(object); } break; }
                WorldPoint point = point(t);
                if (point == null || !(id instanceof Number)) { break; }
                for (WorldPoint place : places(wv, point))
                {
                    Tile tile = tile(wv, place);
                    Object found = "objects".equals(kind) ? MouseTargets.object(client, tile, ((Number) id).intValue()) : MouseTargets.item(tile, ((Number) id).intValue());
                    if (found != null) { out.add(found); }
                }
        }
        return out;
    }

    /** The NPC (npc or index) or player (player or name) a map names, while it is there; null for none. */
    private static Actor actor(WorldView wv, Map<String, Object> t)
    {
        Object npc = t.get("npc"), index = t.get("index"), player = t.get("player"), name = t.get("name");
        if (npc instanceof NPC) { return here((NPC) npc) ? (NPC) npc : null; }
        if (index instanceof Number) { return wv.npcs().byIndex(((Number) index).intValue()); }
        if (player instanceof Player) { return here((Player) player) ? (Player) player : null; }
        return name instanceof String ? player(wv, Text.standardize((String) name)) : null;
    }

    /** Whether a pie or text follows a character rather than standing on a point. */
    private static boolean follows(Map<String, Object> t) { return t.get("npc") != null || t.get("index") != null || t.get("player") != null || t.get("name") != null; }

    /** Where a pie or text stands: its point, its plane, its height above the ground by default, and the character it follows. */
    @RequiredArgsConstructor
    private static final class Place
    {
        final LocalPoint point;
        final int plane, lift;

        /** The character it follows (Marker.follow), else null. */
        final Actor actor;
    }

    /**
     * Where a pie or text stands now: on the character it follows, above its head as the game's overhead text; else
     * on its point, in every place an instance shows it.
     */
    private static List<Place> places(WorldView wv, Map<String, Object> t)
    {
        List<Place> out = new ArrayList<>();
        if (follows(t))
        {
            Actor a = actor(wv, t);
            LocalPoint local = a == null ? null : a.getLocalLocation();
            if (local != null) { out.add(new Place(local, a.getWorldView().getPlane(), a.getLogicalHeight() + 40, a)); }
            return out;
        }
        WorldPoint point = point(t);
        if (point == null) { return out; }
        for (WorldPoint place : places(wv, point))
        {
            LocalPoint local = LocalPoint.fromWorld(wv, place);
            if (local != null) { out.add(new Place(local, place.getPlane(), 0, null)); }
        }
        return out;
    }

    /** The player of this standardized name, in your world view or one within it (a boat). */
    private static Player player(WorldView wv, String name)
    {
        for (Player p : wv.players()) { if (p != null && name.equals(Text.standardize(p.getName()))) { return p; } }
        Iterable<? extends WorldView> subs = wv.worldViews();
        for (WorldView sub : subs == null ? Collections.<WorldView>emptyList() : subs) { Player p = player(sub, name); if (p != null) { return p; } }
        return null;
    }

    /** Whether a character sent as itself is still there: a despawned one is not drawn where it was. */
    private static boolean here(NPC n) { WorldView wv = n.getWorldView(); return wv != null && wv.npcs().byIndex(n.getIndex()) == n; }

    private static boolean here(Player p) { WorldView wv = p.getWorldView(); return wv != null && wv.players().byIndex(p.getId()) == p; }

    /** Whether an object sent as itself is still on its tile. */
    private static boolean here(TileObject o)
    {
        WorldView wv = o.getWorldView();
        Tile tile = wv == null ? null : tile(wv, o.getWorldLocation());
        if (tile == null) { return false; }
        for (GameObject g : tile.getGameObjects()) { if (g == o) { return true; } }
        return tile.getWallObject() == o || tile.getDecorativeObject() == o || tile.getGroundObject() == o;
    }

    /** The scene tile of a world point, also in the extended tiles around the loaded area. */
    private static Tile tile(WorldView wv, WorldPoint p) { return MouseTargets.tile(wv, p.getX() - wv.getBaseX(), p.getY() - wv.getBaseY(), p.getPlane()); }

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
