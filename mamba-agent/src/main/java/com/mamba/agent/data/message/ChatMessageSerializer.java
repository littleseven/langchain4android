package com.mamba.agent.data.message;

import com.mamba.agent.spi.data.message.ChatMessageJsonCodecFactory;

import java.util.List;

import static com.mamba.agent.spi.ServiceHelper.loadFactories;

public class ChatMessageSerializer {

    static final ChatMessageJsonCodec CODEC = loadCodec();

    private static ChatMessageJsonCodec loadCodec() {
        for (ChatMessageJsonCodecFactory factory : loadFactories(ChatMessageJsonCodecFactory.class)) {
            return factory.create();
        }
        return new JacksonChatMessageJsonCodec();
    }

    public static String messageToJson(ChatMessage message) {
        return CODEC.messageToJson(message);
    }

    public static String messagesToJson(List<ChatMessage> messages) {
        return CODEC.messagesToJson(messages);
    }
}
