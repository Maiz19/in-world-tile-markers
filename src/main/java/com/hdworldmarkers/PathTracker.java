package com.hdworldmarkers;

import static net.runelite.api.MenuAction.*;

import com.hdworldmarkers.HdWorldMarkersConfig.DrawLocations;
import com.hdworldmarkers.HdWorldMarkersConfig.DrawMode;
import com.hdworldmarkers.HdWorldMarkersConfig.MarkerStyle;
import com.hdworldmarkers.HdWorldMarkersConfig.PathDisplaySetting;
import com.hdworldmarkers.RouteFinder.Target;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.util.Text;

/**
 * The walking paths, with Path Marker's options: the active path, where your last click makes you walk, and the hover
 * path, where a left click would. Routes come from RouteFinder; every game tick the active path is checked against
 * where you are. The plugin draws the paths' scene tiles (markers); the minimap is drawn here.
 */
@Singleton
final class PathTracker extends Overlay implements KeyListener
{
    /** Options that are never the left click (they can sit above it) and options that never walk. */
    private static final Set<MenuAction> NOT_LEFT_CLICK = EnumSet.of(EXAMINE_ITEM_GROUND, EXAMINE_NPC, EXAMINE_OBJECT, RUNELITE,
        RUNELITE_HIGH_PRIORITY, RUNELITE_INFOBOX, RUNELITE_OVERLAY, RUNELITE_OVERLAY_CONFIG, RUNELITE_PLAYER), NOT_WALKING = EnumSet.of(CANCEL,
        CC_OP, CC_OP_LOW_PRIORITY, PLAYER_EIGHTH_OPTION, WIDGET_CLOSE, WIDGET_CONTINUE, WIDGET_FIRST_OPTION, WIDGET_SECOND_OPTION,
        WIDGET_THIRD_OPTION, WIDGET_FOURTH_OPTION, WIDGET_FIFTH_OPTION, WIDGET_TARGET, WIDGET_TARGET_ON_WIDGET, WIDGET_TYPE_1,
        WIDGET_TYPE_4, WIDGET_TYPE_5);
    /**
     * Options on a thing (an object, ground item, character or player): their target needs no tile under the mouse,
     * so they are routed at the click itself; until the next client tick the client shows its own flag on the thing.
     */
    static final Set<MenuAction> ON_A_THING = EnumSet.of(GAME_OBJECT_FIRST_OPTION, GAME_OBJECT_SECOND_OPTION,
        GAME_OBJECT_THIRD_OPTION, GAME_OBJECT_FOURTH_OPTION, GAME_OBJECT_FIFTH_OPTION, WIDGET_TARGET_ON_GAME_OBJECT,
        GROUND_ITEM_FIRST_OPTION, GROUND_ITEM_SECOND_OPTION, GROUND_ITEM_THIRD_OPTION, GROUND_ITEM_FOURTH_OPTION,
        GROUND_ITEM_FIFTH_OPTION, WIDGET_TARGET_ON_GROUND_ITEM, NPC_FIRST_OPTION, NPC_SECOND_OPTION, NPC_THIRD_OPTION,
        NPC_FOURTH_OPTION, NPC_FIFTH_OPTION, WIDGET_TARGET_ON_NPC, PLAYER_FIRST_OPTION, PLAYER_SECOND_OPTION,
        PLAYER_THIRD_OPTION, PLAYER_FOURTH_OPTION, PLAYER_FIFTH_OPTION, PLAYER_SIXTH_OPTION, PLAYER_SEVENTH_OPTION,
        WIDGET_TARGET_ON_PLAYER);
    /** Options on objects: where they walk, the client's destination tells best (see watching). */
    private static final Set<MenuAction> OBJECT_OPTIONS = EnumSet.of(GAME_OBJECT_FIRST_OPTION, GAME_OBJECT_SECOND_OPTION,
        GAME_OBJECT_THIRD_OPTION, GAME_OBJECT_FOURTH_OPTION, GAME_OBJECT_FIFTH_OPTION, WIDGET_TARGET_ON_GAME_OBJECT);
    /** Tiles a predicted path has at most. */
    static final int MAX_PREDICTED = 300;
    @Inject private Client client;
    @Inject private HdWorldMarkersConfig config;
    @Inject private OverlayManager overlays;
    @Inject private KeyManager keys;
    @Inject private MouseManager mouse;
    RouteFinder routes = new RouteFinder();
    /** The active and hover paths, and a predicted one to a walk target beyond what the client routes. */
    private final Path activePath = new Path(), hoverPath = new Path(), predictedPath = new Path();
    /**
     * Your tile at the last game tick; a click waiting for the next client tick, with the tile under an open menu. The
     * click is copied: RuneLite's menu entries show the menu as it is now, rebuilt by then.
     */
    private WorldPoint lastTile;
    private Click click;
    private Tile clickTile, lastHovered;
    /** A click found no route of its own: the active path follows where the client walks instead. */
    private boolean toDestination;
    /**
     * The last click was on a target used from a distance (a banker across its booth): its walk stops where the
     * server's flag does not, at approachEnd (null: you were in range, no walk). Kept after arrival, until the next click
     * or the client's flag is gone.
     */
    private boolean approaching;
    private WorldPoint approachEnd;
    /**
     * Client ticks (two game ticks' worth) after a click on an object in which the active path takes the server's flag
     * once it comes: which side of an obstacle the game walks to, its own rules decide, and the route found here first
     * could lead around it until the next game tick. The destination before the click.
     */
    private int watching;
    private LocalPoint clickDestination;
    /**
     * The flag the client puts down itself at a click, on the clicked object or tile, until the server sends where it
     * walks you: no destination (taken as one, the path flew to the object and back).
     */
    private LocalPoint clickFlag;
    /** The last click found its own route, which the active path is. */
    private boolean clickRouted;

    /** A clicked option: its kind, its parameters (scene x and y), its id, its character and its text. */
    private static final class Click
    {
        final MenuAction type;
        final int x, y, id;
        final Actor actor;
        final String option;
        /** Routed already, at the click. */
        boolean routed;
        Click(MenuEntry e) { type = e.getType(); x = e.getParam0(); y = e.getParam1(); id = e.getIdentifier(); actor = e.getActor(); option = e.getOption(); }
    }
    private Point mouseAt;
    /**
     * A route found at a click may start a tick late (fresh until found again once); mismatch: last tick you were not
     * where the active path put you. pressed: a left press, which may be a minimap click (it has no menu option).
     */
    private boolean fresh, mismatch, clickInMenu, clickRunning, ctrl, keyActive, keyHover, pressed;
    private final MouseAdapter mouseListener = new MouseAdapter()
    {
        @Override public MouseEvent mousePressed(MouseEvent e)
        {
            if (e.getButton() == MouseEvent.BUTTON1) { pressed = true; mouseAt = client.getMouseCanvasPosition(); }
            return e;
        }
    };

    {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    /** A route on show: what it leads to and from where, its turns, and the tiles a walk along it stands on or runs past. */
    static final class Path
    {
        Target target;
        WorldPoint from;
        List<WorldPoint> turns = Collections.emptyList();
        boolean running;
        final List<WorldPoint> main = new ArrayList<>(), passed = new ArrayList<>();

        void clear()
        {
            target = null; from = null; turns = Collections.emptyList();
            main.clear(); passed.clear();
        }
    }

    void startUp()
    {
        activePath.clear(); hoverPath.clear(); predictedPath.clear();
        click = null;
        ctrl = keyActive = keyHover = pressed = false;
        overlays.add(this);
        keys.registerKeyListener(this);
        mouse.registerMouseListener(mouseListener);
    }

    void shutDown()
    {
        overlays.remove(this);
        keys.unregisterKeyListener(this);
        mouse.unregisterMouseListener(mouseListener);
    }

    @Override public void keyTyped(KeyEvent e) { }
    @Override public void keyPressed(KeyEvent e) { key(e, true); }
    @Override public void keyReleased(KeyEvent e) { key(e, false); }

    /** Ctrl changes running; a path's keybind shows it while held, or toggles it, as its display option says. */
    private void key(KeyEvent e, boolean down)
    {
        if (e.getKeyCode() == KeyEvent.VK_CONTROL) { ctrl = down; }
        if (config.displayKeybindActivePath().matches(e)) { keyActive = keyed(config.activePathDisplaySetting(), keyActive, down); }
        if (config.displayKeybindHoverPath().matches(e)) { keyHover = keyed(config.hoverPathDisplaySetting(), keyHover, down); }
    }

    private static boolean keyed(PathDisplaySetting display, boolean shown, boolean down)
    {
        return display == PathDisplaySetting.WHILE_KEY_PRESSED ? down : display == PathDisplaySetting.TOGGLE_ON_KEYPRESS && down ? !shown : shown;
    }

    /** Whether a click now runs: the run option, which holding Ctrl inverts or overrides as the game's Ctrl option says. */
    private boolean runOnClick()
    {
        boolean run = client.getVarpValue(VarPlayerID.OPTION_RUN) == 1;
        switch (ctrl ? client.getVarbitValue(VarbitID.RUNINVERT_MODE) : 0)
        {
            case 1: return true;
            case 2: return false;
            case 3: return !run;
            default: return run;
        }
    }

    /**
     * Where the walk you are on ends: the last tile of the path shown for it, so the destination tile and the path
     * always agree. The predicted path to a walk target (guess; object: the object or wall it met) while markers shows
     * it; else the active path; after a walk to a target used from a distance, where it stopped; your own tile where a
     * click walks nowhere (beside what was clicked, or already in range). Null where no path knows: then the client's
     * destination tells.
     */
    WorldPoint destination(Player me, WorldPoint guess, Target object)
    {
        // A Walk here is routed on the next client tick; until then the client's flag is on the tile clicked.
        if (click != null && !click.routed && guess == null) { return null; }
        if (guess != null && (activePath.turns.isEmpty() || MarkerSources.outside(me.getWorldView(), guess)))
        {
            predict(me, guess, object);
            // No path to a tile (another level): the tile clicked.
            return !predictedPath.main.isEmpty() ? last(predictedPath.main) : object == null ? guess : me.getWorldLocation();
        }
        if (!activePath.turns.isEmpty()) { return last(activePath.turns); }
        if (approaching) { return approachEnd == null ? me.getWorldLocation() : approachEnd; }
        // The client's own flag of the click lies on what was clicked (the server's comes a tick later): no walk. Before
        // the next client tick that flag is not known yet, but every flag is the click's own.
        LocalPoint flag = client.getLocalDestinationLocation();
        return clickRouted && (click != null || clickFlag != null && clickFlag.equals(flag)) ? me.getWorldLocation() : null;
    }

    /** Running on the active path, or for a click now when there is none. */
    boolean running() { return activePath.turns.isEmpty() ? runOnClick() : activePath.running; }

    /** A key released while the client had no focus never arrives: Ctrl would stay held. */
    @Subscribe public void onFocusChanged(FocusChanged e) { if (!e.isFocused()) { ctrl = false; } }

    @Subscribe public void onVarbitChanged(VarbitChanged e)
    {
        if (e.getVarpId() == VarPlayerID.OPTION_RUN) { activePath.running = e.getValue() == 1; }
    }

    @Subscribe public void onGameStateChanged(GameStateChanged e)
    {
        if (e.getGameState() == GameState.HOPPING || e.getGameState() == GameState.LOGGING_IN) { activePath.clear(); }
    }

    /**
     * Found on the next client tick, when the game knows the tile a left-click Walk here goes to; an open menu keeps
     * the tile it was opened on.
     */
    @Subscribe public void onMenuOptionClicked(MenuOptionClicked e)
    {
        click = new Click(e.getMenuEntry());
        approaching = false;
        clickRouted = false;
        clickDestination = client.getLocalDestinationLocation();
        clickInMenu = client.isMenuOpen();
        clickTile = lastHovered;
        clickRunning = runOnClick();
        // A thing's route at once: frames drawn before the next client tick show it, not the client's flag on the thing.
        Player me = client.getLocalPlayer();
        if (ON_A_THING.contains(click.type) && me != null && routes.load(me.getWorldView())) { route(click, null); }
    }

    /** Routes a click: its route becomes the active path, or the active path follows where the client walks. */
    private void route(Click c, Tile hovered)
    {
        clickRouted = start(target(c.type, c.x, c.y, c.id, c.actor, c.option, clickInMenu ? clickTile : hovered, null), clickRunning);
        toDestination = !clickRouted && !NOT_WALKING.contains(c.type) && !NOT_LEFT_CLICK.contains(c.type);
        watching = OBJECT_OPTIONS.contains(c.type) ? 60 : 0;
        c.routed = true;
    }

    /**
     * The game used a target from a distance (its bank, collection box or chat opened): the server stopped you where
     * you stand, also where the route found here went on. No walk when you never moved.
     */
    @Subscribe public void onWidgetLoaded(WidgetLoaded e)
    {
        int id = e.getGroupId();
        Player me = client.getLocalPlayer();
        if (!approaching || me == null || id != InterfaceID.BANKMAIN && id != InterfaceID.GE_COLLECT && id != InterfaceID.CHAT_LEFT) { return; }
        approachEnd = approachEnd == null ? null : me.getWorldLocation();
        activePath.clear();
    }

    @Subscribe public void onClientTick(ClientTick e)
    {
        Player me = client.getLocalPlayer();
        if (me == null || !routes.load(me.getWorldView())) { return; }
        Tile hovered = me.getWorldView().getSelectedSceneTile();
        if (click != null)
        {
            if (!click.routed) { route(click, hovered); }
            LocalPoint flag = client.getLocalDestinationLocation();
            clickFlag = flag != null && !flag.equals(clickDestination) ? flag : null;
        }
        click = null;
        if (watching > 0)
        {
            watching--;
            LocalPoint destination = serverDestination();
            if (destination != null && !destination.equals(clickDestination))
            {
                watching = 0;
                WorldPoint to = WorldPoint.fromLocal(client, destination);
                if (activePath.turns.isEmpty() || !to.equals(last(activePath.turns))) { start(Target.tile(to), clickRunning); }
            }
        }
        // With only Cancel in the menu the mouse can be on the minimap, where a left press walks.
        MenuEntry[] entries = client.getMenu().getMenuEntries();
        WorldPoint minimap = entries.length == 1 && !client.isMenuOpen()
            && (pressed || config.hoverPathDisplaySetting() != PathDisplaySetting.NEVER) ? minimapTile(me) : null;
        // The hover path keeps its target while the mouse is on no tile.
        MenuEntry option = entries.length == 0 ? null : chosen(entries);
        Target t = minimap != null ? Target.tile(minimap) : hovered == null || option == null ? hoverPath.target
            : target(option.getType(), option.getParam0(), option.getParam1(), option.getIdentifier(), option.getActor(), option.getOption(), hovered, hoverPath.target);
        if (minimap != null && pressed) { clickRouted = start(t, runOnClick()); clickFlag = null; }
        pressed = false;
        mouseAt = client.getMouseCanvasPosition();
        lastHovered = hovered;
        hoverTo(t, me.getWorldLocation(), false);
    }

    @Subscribe public void onGameTick(GameTick e)
    {
        Player me = client.getLocalPlayer();
        if (me == null || !routes.load(me.getWorldView())) { return; }
        routes.loadBlockers(me.getWorldView());
        WorldPoint now = me.getWorldLocation();
        LocalPoint destination = client.getLocalDestinationLocation();
        if (toDestination && activePath.turns.isEmpty() && destination != null) { start(Target.tile(WorldPoint.fromLocal(client, destination)), runOnClick()); }
        toDestination = false;
        // The server walks you with no path known here (the walk began elsewhere, as a predicted walk into the extended
        // view): its path to the flag, so the destination tile never stands there without one. Found from the tile you
        // are on now, so it is followed from the next tick; the game chose running, so the run setting, not Ctrl now.
        LocalPoint walking = serverDestination();
        WorldPoint flagged = walking == null ? null : WorldPoint.fromLocal(client, walking);
        if (activePath.turns.isEmpty() && !approaching && flagged != null && !flagged.equals(now) && lastTile != null && !now.equals(lastTile))
        {
            start(Target.tile(flagged), client.getVarpValue(VarPlayerID.OPTION_RUN) == 1);
        }
        else if (!activePath.turns.isEmpty()) { follow(now); }
        // Arrived and the client's flag is gone: a later walk the server starts is no longer this one.
        if (approaching && activePath.turns.isEmpty() && destination == null) { approaching = false; }
        walk(activePath, now, false);
        hoverTo(hoverPath.target, now, true);
        lastTile = now;
    }

    /** The hover path to t from your tile: found again when either changes, walked again also when running changes. */
    private void hoverTo(Target t, WorldPoint from, boolean walk)
    {
        // Shown nowhere (by default): no route search every time the mouse moves; found again once it is shown.
        if (!shows(false, false) && !shows(false, true)) { hoverPath.clear(); return; }
        // Toward a target used from a distance, where the walk stops depends on running too.
        if (t == null ? hoverPath.target != null : !t.same(hoverPath.target) || !from.equals(hoverPath.from) || t.fromAfar() && runOnClick() != hoverPath.running)
        {
            List<WorldPoint> turns = t == null ? null : routes.approach(from, t, routes.find(from, t), runOnClick());
            hoverPath.turns = turns == null ? Collections.emptyList() : turns;
            hoverPath.target = t;
            hoverPath.from = from;
            walk = true;
        }
        boolean run = runOnClick();
        if (walk || run != hoverPath.running)
        {
            hoverPath.running = run;
            walk(hoverPath, from, false);
        }
    }

    /**
     * A click's route becomes the active path; when nothing near its target can be reached, the active path stays.
     * Returns whether there is a route.
     */
    private boolean start(Target t, boolean running)
    {
        WorldPoint from = client.getLocalPlayer().getWorldLocation();
        List<WorldPoint> turns = t == null ? null : routes.approach(from, t, routes.find(from, t), running);
        if (turns == null) { return false; }
        activePath.target = t;
        activePath.turns = turns;
        activePath.running = running;
        approaching = t.fromAfar();
        approachEnd = turns.isEmpty() ? null : last(turns);
        fresh = true;
        mismatch = false;
        lastTile = from;
        walk(activePath, from, false);
        return true;
    }

    /** Each game tick: the active path against where you are; it ends, or is found again where you left it or its target moved. */
    private void follow(WorldPoint now)
    {
        // The server walks you elsewhere than this route ends: to its flag then. Its flag lies beyond where a walk to a
        // target used from a distance stops.
        LocalPoint destination = activePath.target.fromAfar() ? null : serverDestination();
        WorldPoint to = destination == null ? null : WorldPoint.fromLocal(client, destination);
        if (to != null && !to.equals(last(activePath.turns))) { reroute(Target.tile(to), now); }
        // Arrived, or moved more than two tiles (teleported, or another level).
        if (activePath.turns.isEmpty() || now.equals(last(activePath.turns)) || now.distanceTo(lastTile) > 2) { activePath.clear(); return; }
        // Where the route puts you now: a step on from your last tile walking, two running; a blocked step stays put.
        WorldPoint at = lastTile, end = last(activePath.turns);
        int passed = 0, steps = 0;
        for (; steps < (activePath.running ? 2 : 1) && (at.getX() != end.getX() || at.getY() != end.getY()); steps++)
        {
            if (at.equals(activePath.turns.get(passed))) { passed++; }
            WorldPoint next = routes.step(at, activePath.turns.get(passed));
            at = next == null ? at : next;
        }
        boolean elsewhere = !at.equals(now);
        // You already stood at its end, or were elsewhere two ticks in a row with no flag of the server's: it is not what
        // you walk. Toward the server's flag the game walks another way than this route: found again from where you are.
        if (steps == 0 || elsewhere && !fresh && mismatch && to == null) { activePath.clear(); return; }
        // The first time you are elsewhere after a click, the route is found again from where you are.
        if (elsewhere && (fresh || to != null)) { reroute(activePath.target, now); }
        else { activePath.turns.subList(0, Math.min(passed, activePath.turns.size() - 1)).clear(); }
        mismatch = elsewhere;
        // To a character: on the last stretch, found again for where it stands now.
        if (activePath.target.actor != null && activePath.turns.size() < 2)
        {
            reroute(Target.of(activePath.target.actor).reaching(activePath.target), now);
        }
    }

    /** The client's destination once the server sets it; null while it is the client's own flag of the last click. */
    private LocalPoint serverDestination()
    {
        LocalPoint destination = client.getLocalDestinationLocation();
        return destination == null || destination.equals(clickFlag) ? null : destination;
    }

    private void reroute(Target t, WorldPoint from)
    {
        List<WorldPoint> turns = routes.approach(from, t, routes.find(from, t), activePath.running);
        if (turns == null) { return; }
        activePath.turns = turns;
        fresh = false;
        if (approaching) { approachEnd = turns.isEmpty() ? null : last(turns); }
    }

    private static WorldPoint last(List<WorldPoint> points) { return points.get(points.size() - 1); }

    /**
     * Fills a path's tiles: from `from` through its turns, a step at a time as the game moves you, up to a blocked step;
     * blind (a predicted walk beyond the loaded area) through the border and the extended view where it is known, and
     * straight on where nothing is known or a step is blocked, up to MAX_PREDICTED tiles. Running, every other tile is
     * only run past; the last tile is always stood on.
     */
    void walk(Path p, WorldPoint from, boolean blind)
    {
        p.main.clear();
        p.passed.clear();
        if (p.turns.isEmpty() || from.getPlane() != p.turns.get(0).getPlane()) { return; }
        WorldPoint at = from, end = last(p.turns);
        boolean pass = p.running;
        for (WorldPoint turn : p.turns)
        {
            while ((at.getX() != turn.getX() || at.getY() != turn.getY()) && (!blind || p.main.size() + p.passed.size() < MAX_PREDICTED))
            {
                WorldPoint next = blind ? routes.known(at) ? routes.stepBeyond(at, turn) : null : routes.step(at, turn);
                if (next == null)
                {
                    if (!blind) { return; }
                    next = at.dx(Integer.signum(turn.getX() - at.getX())).dy(Integer.signum(turn.getY() - at.getY()));
                }
                at = next;
                (pass && !at.equals(end) ? p.passed : p.main).add(at);
                pass = p.running && !pass;
            }
        }
    }

    /** The menu option a click chooses: the one under the mouse in an open menu, else the left-click option. */
    private MenuEntry chosen(MenuEntry[] entries)
    {
        if (client.isMenuOpen())
        {
            // Off the options, a click closes the menu: Cancel, the first.
            MenuEntry row = MouseTargets.row(client, entries);
            return row != null ? row : entries[0];
        }
        int i = entries.length - 1;
        while (i > 0 && NOT_LEFT_CLICK.contains(entries[i].getType())) { i--; }
        return entries[i];
    }

    /**
     * Where a menu option walks: to an object, a ground item's tile, a character, or (Walk here, and options not named
     * here) the given tile. Null for options that never walk; keep when the target is not found.
     */
    private Target target(MenuAction type, int x, int y, int id, Actor actor, String option, Tile tile, Target keep)
    {
        switch (type)
        {
            case GAME_OBJECT_FIRST_OPTION: case GAME_OBJECT_SECOND_OPTION: case GAME_OBJECT_THIRD_OPTION: case GAME_OBJECT_FOURTH_OPTION:
            case GAME_OBJECT_FIFTH_OPTION: case WIDGET_TARGET_ON_GAME_OBJECT:
                Target object = object(sceneTile(x, y), id);
                return object == null ? keep : object;
            case GROUND_ITEM_FIRST_OPTION: case GROUND_ITEM_SECOND_OPTION: case GROUND_ITEM_THIRD_OPTION: case GROUND_ITEM_FOURTH_OPTION:
            case GROUND_ITEM_FIFTH_OPTION: case WIDGET_TARGET_ON_GROUND_ITEM:
                Tile on = sceneTile(x, y);
                List<TileItem> items = on == null ? null : on.getGroundItems();
                if (items == null || items.stream().noneMatch(i -> i != null && i.getId() == id)) { return keep; }
                Target item = Target.tile(on.getWorldLocation());
                // A spell (Telekinetic Grab) takes it from a distance.
                if ("Cast".equals(option == null ? "" : Text.removeTags(option))) { item.range = SPELL_RANGE; }
                return item;
            case NPC_FIRST_OPTION: case NPC_SECOND_OPTION: case NPC_THIRD_OPTION: case NPC_FOURTH_OPTION: case NPC_FIFTH_OPTION:
            case WIDGET_TARGET_ON_NPC: case PLAYER_FIRST_OPTION: case PLAYER_SECOND_OPTION: case PLAYER_THIRD_OPTION:
            case PLAYER_FOURTH_OPTION: case PLAYER_FIFTH_OPTION: case PLAYER_SIXTH_OPTION: case PLAYER_SEVENTH_OPTION:
            case WIDGET_TARGET_ON_PLAYER:
                if (actor == null) { return keep; }
                Target t = Target.of(actor);
                reach(t, option);
                return t;
            default:
                return NOT_LEFT_CLICK.contains(type) || NOT_WALKING.contains(type) ? null
                    : tile == null ? keep : Target.tile(tile.getWorldLocation());
        }
    }

    /** Tiles a spell reaches, cast or autocast, and the most any attack reaches. */
    private static final int SPELL_RANGE = 10;
    /** The weapon param holding its attack range; 0 for weapons used from beside the target. */
    private static final int ATTACK_RANGE = 13;

    /**
     * How far an option on a character reaches: a spell cast on it, or an attack while autocasting, SPELL_RANGE; an
     * attack as far as the weapon worn reaches (a halberd 2, a salamander 1 by sight, so diagonally too, a ranged
     * weapon or powered staff further, on longrange 2 more); else as far as the character is used from.
     */
    private void reach(Target t, String option)
    {
        String o = option == null ? "" : Text.removeTags(option);
        int mode = client.getVarpValue(VarPlayerID.COM_MODE);
        // Style 4 is autocasting (5 its defensive form, kept in another varbit).
        if ("Cast".equals(o) || "Attack".equals(o) && mode == 4) { t.range = SPELL_RANGE; return; }
        if (!"Attack".equals(o)) { return; }
        ItemContainer worn = client.getItemContainer(net.runelite.api.gameval.InventoryID.WORN);
        Item weapon = worn == null ? null : worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
        int id = weapon == null ? -1 : weapon.getId();
        // The cache gives the Dawnbringer 10; it reaches 7.
        int range = id < 0 ? 0 : id == net.runelite.api.gameval.ItemID.VERZIK_SPECIAL_WEAPON ? 7
            : client.getItemDefinition(id).getIntValue(ATTACK_RANGE);
        if (range == 1) { t.bySight = true; }
        // Every weapon reaching further than a halberd has its longrange (on powered staves named Defensive) as style 3.
        else if (range > 1) { t.range = Math.min(SPELL_RANGE, mode == 3 && range > 2 ? range + 2 : range); }
    }

    /** The object with this id on a scene tile, as a target, or null. */
    private static Target object(Tile tile, int id)
    {
        if (tile == null) { return null; }
        for (GameObject o : tile.getGameObjects())
        {
            if (o != null && o.getId() == id) { return Target.object(tile.getWorldLocation(), o.sizeX(), o.sizeY(), o.getConfig(), id); }
        }
        WallObject wall = tile.getWallObject();
        DecorativeObject decoration = tile.getDecorativeObject();
        GroundObject ground = tile.getGroundObject();
        int config = wall != null && wall.getId() == id ? wall.getConfig() : decoration != null && decoration.getId() == id
            ? decoration.getConfig() : ground != null && ground.getId() == id ? ground.getConfig() : -1;
        return config < 0 ? null : Target.object(tile.getWorldLocation(), 1, 1, config, id);
    }

    /** A tile of the scene on your level, or null. */
    private Tile sceneTile(int x, int y)
    {
        WorldView wv = client.getLocalPlayer().getWorldView();
        Tile[][] level = wv.getScene().getTiles()[wv.getPlane()];
        return x >= 0 && y >= 0 && x < level.length && y < level[x].length ? level[x][y] : null;
    }

    private Widget minimap()
    {
        return client.getWidget(!client.isResized() ? InterfaceID.Toplevel.MINIMAP : client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1
            ? InterfaceID.ToplevelPreEoc.MINIMAP : InterfaceID.ToplevelOsrsStretch.MINIMAP);
    }

    /** The tile under the mouse on the minimap, as the game maps it: turned with the camera, zoom pixels per tile from you. */
    private WorldPoint minimapTile(Player me)
    {
        Widget map = minimap();
        if (mouseAt == null || map == null || map.isHidden() || !map.contains(mouseAt)) { return null; }
        double a = client.getCameraYawTarget() * Perspective.UNIT14, k = 128 / client.getMinimapZoom();
        double x = mouseAt.getX() - map.getCanvasLocation().getX() - map.getWidth() / 2, y = mouseAt.getY() - map.getCanvasLocation().getY() - map.getHeight() / 2;
        LocalPoint at = me.getLocalLocation();
        return WorldPoint.fromScene(me.getWorldView(), Math.floorDiv(at.getX() + (int) ((x * Math.cos(a) + y * Math.sin(a)) * k), 128),
            Math.floorDiv(at.getY() + (int) ((x * Math.sin(a) - y * Math.cos(a)) * k), 128), me.getWorldView().getPlane());
    }

    private static boolean shown(PathDisplaySetting display, boolean key)
    {
        return display == PathDisplaySetting.ALWAYS || display != PathDisplaySetting.NEVER && key;
    }

    /** Whether the active or hover path shows on the minimap or in the game world, by its options (the active one if it exists). */
    private boolean shows(boolean active, boolean minimap)
    {
        DrawLocations where = active ? config.activePathDrawLocations() : config.hoverPathDrawLocations();
        if (where != DrawLocations.BOTH && where != (minimap ? DrawLocations.MINIMAP : DrawLocations.GAME_WORLD)) { return false; }
        if (active) { return shown(config.activePathDisplaySetting(), keyActive); }
        return shown(config.hoverPathDisplaySetting(), keyHover) && (!config.drawOnlyIfNoActivePath()
            || activePath.turns.isEmpty() || config.activePathDisplaySetting() == PathDisplaySetting.NEVER);
    }

    /** Passes each tile a path shows to draw, with whether it is stood on: all of them, or only the target tile. */
    private void each(Path p, boolean active, BiConsumer<WorldPoint, Boolean> draw)
    {
        if ((active ? config.activePathDrawMode() : config.hoverPathDrawMode()) == DrawMode.TARGET_TILE)
        {
            if (!p.main.isEmpty()) { draw.accept(last(p.main), true); }
            return;
        }
        p.main.forEach(tile -> draw.accept(tile, true));
        p.passed.forEach(tile -> draw.accept(tile, false));
    }

    /** A path's outline or fill colour, for the tiles stood on (main) or run past. */
    private Color color(boolean active, boolean main, boolean fill)
    {
        if (active) { return main ? fill ? config.activePathFill1() : config.activePathStroke1() : fill ? config.activePathFill2() : config.activePathStroke2(); }
        return main ? fill ? config.hoverPathFill1() : config.hoverPathStroke1() : fill ? config.hoverPathFill2() : config.hoverPathStroke2();
    }

    /**
     * The paths' tiles in the game world, hover path first, for the plugin's renderer. With a walk target predicted
     * beyond what the client routes (MarkerSources.predictedTarget), its path too, while no active path exists or the
     * target lies beyond the loaded area; where the predicted click met an object or wall (object), the path goes beside it.
     */
    List<Marker> markers(WorldPoint guess, Target object)
    {
        List<Marker> out = new ArrayList<>();
        Player me = client.getLocalPlayer();
        if (me == null) { return out; }
        WorldView wv = me.getWorldView();
        if (shows(false, false)) { PathLook hover = new PathLook(false, wv); each(hoverPath, false, (tile, main) -> out.add(hover.marker(tile, main))); }
        if (!shows(true, false)) { return out; }
        PathLook active = new PathLook(true, wv);
        // One path: the predicted one while it is shown (its end is the destination), else the active one.
        if (guess != null && (activePath.turns.isEmpty() || MarkerSources.outside(wv, guess)))
        {
            predict(me, guess, object);
            each(predictedPath, true, (tile, main) -> out.add(active.marker(tile, main)));
        }
        else { each(activePath, true, (tile, main) -> out.add(active.marker(tile, main))); }
        return out;
    }

    /** A path's options as its tiles need them, read once per path rather than per tile (a predicted path has hundreds). */
    private final class PathLook
    {
        final boolean active, dot;
        final Color stroke, fill, passedStroke, passedFill;
        final double width;
        final WorldView wv;

        PathLook(boolean active, WorldView wv)
        {
            this.active = active; this.wv = wv;
            dot = (active ? config.activePathMarkerStyle() : config.hoverPathMarkerStyle()) == MarkerStyle.DOT;
            stroke = color(active, true, false); fill = color(active, true, true);
            passedStroke = color(active, false, false); passedFill = color(active, false, true);
            width = dot ? 2 : config.pathBorderWidth();
        }

        /** A path tile, stood on (main) or run past. */
        Marker marker(WorldPoint tile, boolean main)
        {
            Marker m = new Marker((active ? "path:a:" : "path:h:") + tile.getX() + ":" + tile.getY(), MarkerSources.local(wv, tile), wv.getPlane(), 1, 1,
                main ? stroke : passedStroke, main ? fill : passedFill, width, null);
            m.dot = dot;
            m.layer = active ? Marker.PATH_ACTIVE : Marker.PATH_HOVER;
            return m;
        }
    }

    /**
     * The predicted path to a walk target: the route to the known tile nearest it, through the loaded area's border and
     * on through a renderer's extended view where it is loaded (around water, walls and objects), then straight on,
     * since beyond that nothing is known. Found again only when the target, your tile or running changes.
     */
    private void predict(Player me, WorldPoint guess, Target object)
    {
        WorldPoint from = me.getWorldLocation();
        Target target = object != null ? object : Target.tile(guess);
        if (target.same(predictedPath.target) && from.equals(predictedPath.from) && predictedPath.running == running()) { return; }
        predictedPath.target = target;
        predictedPath.from = from;
        predictedPath.running = running();
        WorldView wv = me.getWorldView();
        List<WorldPoint> turns = new ArrayList<>();
        // This runs while every mark is collected: a failure leaves only the straight line.
        try
        {
            // An object or wall is walked beside, from a side the game allows, as a click on it walks.
            List<WorldPoint> route = !routes.load(wv) ? null : routes.findBeyond(from, object != null ? object : Target.tile(routes.clampKnown(guess)));
            if (route != null) { turns.addAll(route); }
            if (object != null && route != null) { predictedPath.turns = turns; walk(predictedPath, from, true); return; }
        }
        catch (RuntimeException ex)
        {
            turns.clear();
        }
        turns.add(guess);
        predictedPath.turns = turns;
        walk(predictedPath, from, true);
    }

    /** The minimap: both paths' tiles in their fill colours, turned with the camera, up to the minimap's edge. */
    @Override public Dimension render(Graphics2D graphics)
    {
        Player me = client.getLocalPlayer();
        Widget map = minimap();
        if (me == null || map == null || map.isHidden()) { return null; }
        double zoom = client.getMinimapZoom() / 128;
        int cx = map.getCanvasLocation().getX() + map.getWidth() / 2, cy = map.getCanvasLocation().getY() + map.getHeight() / 2;
        LocalPoint at = me.getLocalLocation();
        Graphics2D g = (Graphics2D) graphics.create();
        g.rotate(client.getCameraYawTarget() * Perspective.UNIT14, cx, cy);
        for (boolean active : new boolean[]{false, true})
        {
            if (!shows(active, true)) { continue; }
            each(active ? activePath : hoverPath, active, (tile, main) -> {
                LocalPoint p = LocalPoint.fromWorld(me.getWorldView(), tile);
                if (p == null) { return; }
                double x = (p.getX() - at.getX()) * zoom, y = (at.getY() - p.getY()) * zoom;
                // The minimap shows about 74 pixels around its centre.
                if (Math.hypot(x, y) > 74) { return; }
                g.setColor(color(active, main, true));
                g.fill(new Rectangle2D.Double(cx + x - 64 * zoom, cy + y - 64 * zoom, 128 * zoom, 128 * zoom));
            });
        }
        g.dispose();
        return null;
    }
}
