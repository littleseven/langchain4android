package com.mamba.android;

import com.mamba.tool.ToolExecutionRequest;
import com.mamba.data.message.AiMessage;
import com.mamba.data.message.ChatMessage;
import com.mamba.model.chat.ChatModel;
import com.mamba.model.chat.request.ChatRequest;
import com.mamba.model.chat.request.DefaultChatRequestParameters;
import com.mamba.model.chat.request.ToolChoice;
import com.mamba.model.chat.response.ChatResponse;

import java.util.List;

/**
 * Mamba 聊天助手：封装 ChatModel 的常用操作。
 *
 * <p>提供更高层的 API，简化单轮/多轮对话调用：</p>
 *
 * <pre>{@code
 * MambaChatHelper chat = new MambaChatHelper(model);
 *
 * // 单轮对话
 * String reply = chat.chat("你好");
 *
 * // 带工具调用的对话
 * ChatResponse response = chat.chatWithTools(
 *     "打开相机",
 *     List.of(navigateTool, captureTool)
 * );
 * }</pre>
 *
 * @see ChatModel
 * @see ChatRequest
 */
public class MambaChatHelper {

    private final ChatModel model;

    public MambaChatHelper(ChatModel model) {
        this.model = model;
    }

    /**
     * 单轮文本对话。
     *
     * @param userMessage 用户输入
     * @return AI 回复文本
     */
    public String chat(String userMessage) {
        return model.chat(userMessage);
    }

    /**
     * 多轮对话（带历史消息）。
     *
     * @param messages 消息列表（包含 system/user/assistant 等）
     * @return ChatResponse
     */
    public ChatResponse chat(List<ChatMessage> messages) {
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .build();
        return model.chat(request);
    }

    /**
     * 带工具调用的对话。
     *
     * @param userMessage       用户输入
     * @param toolSpecifications 可用工具列表
     * @return ChatResponse（可能包含 toolExecutionRequests）
     */
    public ChatResponse chatWithTools(
            String userMessage,
            List<com.mamba.tool.ToolSpecification> toolSpecifications) {
        return chatWithTools(userMessage, toolSpecifications, ToolChoice.AUTO);
    }

    /**
     * 带工具调用的对话（指定工具选择策略）。
     *
     * @param userMessage       用户输入
     * @param toolSpecifications 可用工具列表
     * @param toolChoice        工具选择策略
     * @return ChatResponse
     */
    public ChatResponse chatWithTools(
            String userMessage,
            List<com.mamba.tool.ToolSpecification> toolSpecifications,
            ToolChoice toolChoice) {
        ChatRequest request = ChatRequest.builder()
                .messages(MambaMessageBuilder.user(userMessage))
                .toolSpecifications(toolSpecifications)
                .parameters(
                        DefaultChatRequestParameters.builder()
                                .toolChoice(toolChoice)
                                .build()
                )
                .build();
        return model.chat(request);
    }

    /**
     * 强制工具调用的对话（模型必须调用工具）。
     *
     * @param userMessage       用户输入
     * @param toolSpecifications 可用工具列表
     * @return ChatResponse（必定包含 toolExecutionRequests）
     */
    public ChatResponse chatRequireTools(
            String userMessage,
            List<com.mamba.tool.ToolSpecification> toolSpecifications) {
        return chatWithTools(userMessage, toolSpecifications, ToolChoice.REQUIRED);
    }

    /**
     * 从响应中提取 AI 文本内容。
     *
     * @param response ChatResponse
     * @return 文本内容（可能为 null）
     */
    public static String extractText(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            return null;
        }
        return response.aiMessage().text();
    }

    /**
     * 从响应中提取工具调用请求。
     *
     * @param response ChatResponse
     * @return 工具调用请求列表（可能为空）
     */
    public static List<ToolExecutionRequest> extractToolRequests(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            return List.of();
        }
        AiMessage aiMessage = response.aiMessage();
        if (aiMessage.hasToolExecutionRequests()) {
            return aiMessage.toolExecutionRequests();
        }
        return List.of();
    }

    /**
     * 检查响应是否包含工具调用请求。
     *
     * @param response ChatResponse
     * @return true 如果包含工具调用
     */
    public static boolean hasToolCalls(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            return false;
        }
        return response.aiMessage().hasToolExecutionRequests();
    }
}
