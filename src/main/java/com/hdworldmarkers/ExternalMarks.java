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
        pies = new ConcurrentHashMap<>();
    /**
     * Each owner's tiles and pies as markers for the scene they were made for, made again after new marks or a scene load:
     * every tick they were made anew (an instance's tiles searched its template chunks for each). Pies that fill by the
     * clock are made every tick.
     */
    private final Map<String, Made> tileMarkers = new ConcurrentHashMap<>(), pieMarkers = new ConcurrentHashMap<>();

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

    void clear() { tiles.clear(); npcs.clear(); pies.clear(); tileMarkers.clear(); pieMarkers.clear(); }

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
        else if (state == GameState.LOADING) { tileMarkers.clear(); pieMarkers.clear(); }
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
            case "clear": tiles.remove(owner); npcs.remove(owner); pies.remove(owner); tileMarkers.remove(owner); pieMarkers.remove(owner); break;
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
