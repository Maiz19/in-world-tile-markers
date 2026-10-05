package com.hdworldmarkers;

import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/** HD World Markers' lines in the chat box: local console messages, nothing is sent. */
final class Chat
{
    private Chat() { }

    /** A console line; named, with "HD World Markers: " highlighted in front. */
    static void send(ChatMessageManager chat, String text, boolean named)
    {
        String message = !named ? text : new ChatMessageBuilder().append(ChatColorType.HIGHLIGHT).append("HD World Markers: ")
            .append(ChatColorType.NORMAL).append(text).build();
        chat.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE).runeLiteFormattedMessage(message).build());
    }

    /** "a", "a and b", "a, b and c". */
    static String list(List<String> parts)
    {
        if (parts.size() < 2) { return String.join("", parts); }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
