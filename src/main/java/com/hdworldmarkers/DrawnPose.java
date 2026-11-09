package com.hdworldmarkers;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Model;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

/**
 * The pose an actor is drawn in this frame when another plugin changes it at draw time (Keyframe Smoothing paces
 * attacks along their keyframes), so the cut around it follows what is on screen, not the pose the client made, which
 * runs ahead of a smoothed swing. No compile-time dependency: a {@link PluginMessage} with namespace {@value #NAMESPACE}
 * and name {@value #NAME}, data {@code actor} (Actor) and {@code model} (its Model, posed by the client this frame);
 * the drawing plugin answers on the event bus's thread by putting {@code x}, {@code y}, {@code z} (float[], at least
 * the model's vertex count) into that same map. Unanswered (no such plugin, or it draws the client's pose), the model's
 * own vertices hold.
 */
@Singleton
final class DrawnPose
{
    static final String NAMESPACE = "keyframe-smoothing";
    static final String NAME = "pose";

    private final EventBus eventBus;
    private final Map<String, Object> request = new HashMap<>();
    /** The answer of the last ask that returned true; the answering plugin may reuse these arrays on its next answer. */
    float[] x, y, z;

    @Inject
    DrawnPose(EventBus eventBus) { this.eventBus = eventBus; }

    /** Asks for the actor's drawn pose: true with x, y and z set, false when the model's own vertices are drawn. */
    boolean ask(Actor actor, Model model)
    {
        request.clear();
        request.put("actor", actor);
        request.put("model", model);
        eventBus.post(new PluginMessage(NAMESPACE, NAME, request));
        Object ax = request.get("x"), ay = request.get("y"), az = request.get("z");
        // Nothing kept from the frame: the actor and its model are the client's.
        request.clear();
        int n = model.getVerticesCount();
        if (!(ax instanceof float[] && ay instanceof float[] && az instanceof float[])
            || ((float[]) ax).length < n || ((float[]) ay).length < n || ((float[]) az).length < n)
        {
            x = y = z = null;
            return false;
        }
        x = (float[]) ax; y = (float[]) ay; z = (float[]) az;
        return true;
    }
}
