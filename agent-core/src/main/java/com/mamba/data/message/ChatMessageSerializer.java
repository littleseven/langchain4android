package com.mamba.data.message;

import java.util.List;

public class ChatMessageSerializer {

    static final ChatMessageJsonCodec CODEC = new JacksonChatMessageJsonCodec();

    public static String messageToJson(ChatMessage message) {
        return CODEC.messageToJson(message);
    }

    public static String messagesToJson(List<ChatMessage> messages) {
        return CODEC.messagesToJson(messages);
    }
}
