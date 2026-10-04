package com.inworldtilemarkers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Another plugin whose marks In-World Tile Markers draws in the scene, found by its name. While it does, that plugin's
 * matching overlays (in its own package) are paused (taken out of the overlay manager), so nothing is drawn twice, and
 * they are added back afterwards. Only overlays the plugin itself still shows are added back.
 */
final class SourcePlugin
{
    private final OverlayManager overlays;
    private final PluginManager plugins;
    private final String name;
    /** The plugin's package: its overlays are found there, wherever they moved inside it. */
    private String pkg = "";
    private final Predicate<Overlay> match;
    private final List<Overlay> paused = new ArrayList<>();
    private boolean drawing;
    /** Whether the plugin would show an overlay itself (its own feature toggles). */
    Predicate<Overlay> stillShown = o -> true;
    private final Source[] sources;

    /** What In-World Tile Markers draws for the plugin, each tick, while all its sources are usable. */
    interface Source
    {
        void collect(List<Marker> tiles, List<ModelTarget> models);

        default boolean usable() { return true; }
    }

    /** name: the plugin's name as in the plugin list, or its class name. */
    SourcePlugin(OverlayManager overlays, PluginManager plugins, String name, Predicate<Overlay> match, Source... sources)
    {
        this.overlays = overlays; this.plugins = plugins; this.name = name; this.match = match; this.sources = sources;
    }

    /** The overlays a plugin draws in the world (dynamic, under the interface); not its panels, minimap or world map. */
    static boolean inWorld(Overlay o)
    {
        return o.getPosition() == OverlayPosition.DYNAMIC && (o.getLayer() == OverlayLayer.ABOVE_SCENE || o.getLayer() == OverlayLayer.UNDER_WIDGETS);
    }

    /** Draws the plugin's marks while wanted, it runs and its sources are usable; otherwise its own overlays draw. */
    void collect(boolean wanted, List<Marker> tiles, List<ModelTarget> models)
    {
        for (Source s : sources) { wanted &= s.usable(); }
        update(wanted);
        if (drawing) { for (Source s : sources) { s.collect(tiles, models); } }
    }

    /** Runs the paused overlays once on their captures (IndicatorOverlay). */
    void render(java.awt.Graphics2D g)
    {
        for (Source s : sources) { if (drawing && s instanceof TileCapture) { ((TileCapture) s).render(paused, g); } }
    }

    /** Bumped when plugins are loaded, unloaded, started or stopped, or settings change: the plugin is looked up again. */
    private static volatile int generation;
    private int foundIn = -1;
    /** The generation the overlays were last looked for in (-1: look now), and client ticks since. */
    private int scannedIn = -1, sinceScan;
    private static final int RESCAN_TICKS = 50;
    /** The plugin, and the instance of it whose overlays are paused: a reloaded plugin (an update) has new ones. */
    private Plugin plugin, pausedFor;

    static void pluginsChanged() { generation++; }

    /** Whether the plugin runs; looked up once per change of the plugin list. */
    boolean running()
    {
        int now = generation;
        if (foundIn != now)
        {
            plugin = null;
            for (Plugin p : plugins.getPlugins())
            {
                if (name.equals(p.getName()) || name.equals(p.getClass().getName())) { plugin = p; break; }
            }
            pkg = plugin == null ? "" : plugin.getClass().getName().substring(0, plugin.getClass().getName().lastIndexOf('.') + 1);
            foundIn = now;
        }
        return plugin != null && plugins.isPluginActive(plugin);
    }

    /** Whether In-World Tile Markers draws the plugin's marks this tick. */
    boolean drawing() { return drawing; }

    void update(boolean wanted)
    {
        boolean running = running();
        drawing = wanted && running;
        if (drawing)
        {
            if (pausedFor != plugin) { paused.clear(); scannedIn = -1; }
            pausedFor = plugin;
            // Looked for after a change of plugins or settings, and once a second for an overlay added otherwise: every
            // overlay of every plugin each client tick is costly.
            if (scannedIn != generation || ++sinceScan >= RESCAN_TICKS)
            {
                scannedIn = generation;
                sinceScan = 0;
                List<Overlay> found = new ArrayList<>();
                overlays.anyMatch(o -> { if (o.getClass().getName().startsWith(pkg) && match.test(o)) { found.add(o); } return false; });
                for (Overlay o : found) { overlays.remove(o); if (!paused.contains(o)) { paused.add(o); } }
            }
            // A feature the plugin turned off meanwhile stays off (Sailing removes such overlays).
            paused.removeIf(o -> !stillShown.test(o));
        }
        else { restore(running); }
    }

    TileCapture capture() { return (TileCapture) sources[0]; }

    private void restore(boolean running)
    {
        drawing = false;
        scannedIn = -1;
        // A stopped plugin removed its overlays itself; only the same running instance gets them back.
        if (running && pausedFor == plugin) { for (Overlay o : paused) { if (stillShown.test(o)) { overlays.add(o); } } }
        paused.clear();
    }

    void reset()
    {
        restore(running());
        for (Source s : sources) { if (s instanceof TileCapture) { ((TileCapture) s).clear(); } }
    }
}
