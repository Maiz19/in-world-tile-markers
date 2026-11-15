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

    /** A console line; named, with "HD World Markers: " highlighted in front. The text is escaped: it may hold a name from the game. */
    static void send(ChatMessageManager chat, String text, boolean named)
    {
        ChatMessageBuilder message = new ChatMessageBuilder();
        if (named) { message.append(ChatColorType.HIGHLIGHT).append("HD World Markers: ").append(ChatColorType.NORMAL); }
        chat.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE).runeLiteFormattedMessage(message.append(text).build()).build());
    }

    /** "a", "a and b", "a, b and c". */
    static String list(List<String> parts)
    {
        if (parts.size() < 2) { return String.join("", parts); }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
