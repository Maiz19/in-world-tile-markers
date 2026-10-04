package com.inworldtilemarkers;

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
 * Marks sent by other plugins, drawn by In-World Tile Markers like its own. Plugins need no compile-time
 * dependency: they post a {@link PluginMessage} on the event bus with namespace
 * {@value #NAMESPACE}. Without In-World Tile Markers installed the message simply goes unanswered.
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
 * <li>{@code clear}: {@code owner}. Removes everything of that owner.</li>
 * </ul>
 * Marks stay until their owner replaces or clears them.
 */
@Singleton
final class ExternalMarks
{
    static final String NAMESPACE = "in-world-tile-markers";
    /** Per owner, so one plugin cannot flood the renderer. */
    static final int MAX_PER_OWNER = 1000;
    private static final Color DEFAULT_FILL = new Color(0, 0, 0, 50);

    private final Client client;
    private final Map<String, List<Map<String, Object>>> tiles = new ConcurrentHashMap<>(), npcs = new ConcurrentHashMap<>();

    @Inject
    ExternalMarks(Client client) { this.client = client; }

    void clear() { tiles.clear(); npcs.clear(); }

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
    }

    @Subscribe
    public void onPluginMessage(PluginMessage message)
    {
        if (!NAMESPACE.equals(message.getNamespace()) || message.getData() == null) { return; }
        Object owner = message.getData().get("owner");
        if (!(owner instanceof String) || ((String) owner).isEmpty()) { return; }
        switch (message.getName())
        {
            case "tiles": tiles.put((String) owner, entries(message.getData().get("tiles"))); break;
            case "npcs": npcs.put((String) owner, entries(message.getData().get("npcs"))); break;
            case "clear": tiles.remove(owner); npcs.remove(owner); break;
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
        for (Map.Entry<String, List<Map<String, Object>>> owner : tiles.entrySet())
        {
            int n = 0;
            for (Map<String, Object> t : owner.getValue())
            {
                WorldPoint point = point(t);
                Color color = color(t.get("color"), null);
                if (point == null || color == null) { continue; }
                int size = Math.max(1, Math.min(64, number(t.get("size"), 1)));
                Collection<WorldPoint> places = WorldPoint.toLocalInstance(wv, point);
                // In an instance, a point no template chunk holds is taken as it is there.
                if (places.isEmpty()) { places = Collections.singletonList(point); }
                for (WorldPoint instance : places)
                {
                    LocalPoint local = LocalPoint.fromWorld(wv, instance);
                    if (local == null) { continue; }
                    // A size-n area is centered like NPC footprints: its south-west tile is the point.
                    local = local.plus((size - 1) * 64, (size - 1) * 64);
                    Object label = t.get("label");
                    Marker m = new Marker("ext:" + owner.getKey() + ":" + n++, local, instance.getPlane(), size, size, color,
                        color(t.get("fill"), DEFAULT_FILL), number(t.get("width"), 2), label instanceof String ? (String) label : null, false);
                    m.layer = Marker.EXTERNAL;
                    markerOut.add(m);
                }
            }
        }
        for (Map.Entry<String, List<Map<String, Object>>> owner : npcs.entrySet())
        {
            for (Map<String, Object> t : owner.getValue())
            {
                NPC npc = npc(wv, t);
                Color color = color(t.get("color"), null);
                if (npc == null || color == null) { continue; }
                Color fill = color(t.get("fill"), DEFAULT_FILL);
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
                    Marker m = new Marker(key, local, wv.getPlane(), size, size, color, fill, width, null, false);
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
