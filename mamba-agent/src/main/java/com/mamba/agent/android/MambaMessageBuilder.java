package com.mamba.agent.android;

import com.mamba.agent.data.message.AiMessage;
import com.mamba.agent.data.message.ChatMessage;
import com.mamba.agent.data.message.SystemMessage;
import com.mamba.agent.data.message.ToolExecutionResultMessage;
import com.mamba.agent.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Mamba 消息构建器：简化 ChatMessage 的创建。
 *
 * <p>提供流式 API 构建消息列表，避免手动创建多个消息对象：</p>
 *
 * <pre>{@code
 * List<ChatMessage> messages = MambaMessageBuilder.builder()
 *     .system("你是一个 helpful 的助手")
 *     .user("你好，请帮我拍照")
 *     .build();
 * }</pre>
 *
 * <p>支持多轮对话构建：</p>
 *
 * <pre>{@code
 * List<ChatMessage> messages = MambaMessageBuilder.builder()
 *     .system("你是一个 helpful 的助手")
 *     .user("打开相机")
 *     .assistant("好的，已为您打开相机")
 *     .user("拍照")
 *     .build();
 * }</pre>
 *
 * <p>支持工具结果消息：</p>
 *
 * <pre>{@code
 * List<ChatMessage> messages = MambaMessageBuilder.builder()
 *     .user("获取屏幕信息")
 *     .assistantToolCalls(toolRequest)  // AI 请求调用工具
 *     .toolResult(toolRequest.id(), "get_screen_info", screenInfoJson)
 *     .build();
 * }</pre>
 *
 * @see ChatMessage
 * @see UserMessage
 * @see SystemMessage
 * @see AiMessage
 * @see ToolExecutionResultMessage
 */
public class MambaMessageBuilder {

    private MambaMessageBuilder() {
        // utility class
    }

    /**
     * 创建新的 Builder 实例。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 快速创建单条用户消息列表。
     */
    public static List<ChatMessage> user(String text) {
        return builder().user(text).build();
    }

    /**
     * 快速创建 system + user 消息列表。
     */
    public static List<ChatMessage> chat(String system, String user) {
        return builder().system(system).user(user).build();
    }

    public static class Builder {
        private final List<ChatMessage> messages = new ArrayList<>();

        /**
         * 添加系统消息。
         */
        public Builder system(String text) {
            messages.add(SystemMessage.from(text));
            return this;
        }

        /**
         * 添加用户消息。
         */
        public Builder user(String text) {
            messages.add(UserMessage.from(text));
            return this;
        }

        /**
         * 添加 AI 文本回复消息。
         */
        public Builder assistant(String text) {
            messages.add(new AiMessage(text));
            return this;
        }

        /**
         * 添加 AI 工具调用请求消息。
         *
         * @param toolExecutionRequests 工具执行请求列表
         */
        public Builder assistantToolCalls(
                List<com.mamba.agent.agent.tool.ToolExecutionRequest> toolExecutionRequests) {
            messages.add(new AiMessage(toolExecutionRequests));
            return this;
        }

        /**
         * 添加工具执行结果消息。
         *
         * @param id       工具调用 ID
         * @param toolName 工具名称
         * @param result   执行结果文本
         */
        public Builder toolResult(String id, String toolName, String result) {
            messages.add(new ToolExecutionResultMessage(id, toolName, result));
            return this;
        }

        /**
         * 添加任意 ChatMessage。
         */
        public Builder add(ChatMessage message) {
            messages.add(message);
            return this;
        }

        /**
         * 添加多条消息。
         */
        public Builder addAll(List<ChatMessage> msgs) {
            messages.addAll(msgs);
            return this;
        }

        /**
         * 构建消息列表。
         *
         * @return 不可修改的消息列表副本
         */
        public List<ChatMessage> build() {
            return new ArrayList<>(messages);
        }

        /**
         * 获取当前消息数量。
         */
        public int size() {
            return messages.size();
        }

        /**
         * 检查是否为空。
         */
        public boolean isEmpty() {
            return messages.isEmpty();
        }
    }
}
