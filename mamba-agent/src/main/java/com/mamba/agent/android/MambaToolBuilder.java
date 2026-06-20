package com.mamba.agent.android;

import com.mamba.agent.agent.tool.ToolExecutionRequest;
import com.mamba.agent.agent.tool.ToolSpecification;
import com.mamba.agent.model.chat.request.json.JsonBooleanSchema;
import com.mamba.agent.model.chat.request.json.JsonEnumSchema;
import com.mamba.agent.model.chat.request.json.JsonIntegerSchema;
import com.mamba.agent.model.chat.request.json.JsonNumberSchema;
import com.mamba.agent.model.chat.request.json.JsonObjectSchema;
import com.mamba.agent.model.chat.request.json.JsonSchemaElement;
import com.mamba.agent.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mamba 工具构建器：简化 ToolSpecification 的创建。
 *
 * <p>提供流式 API 构建工具定义，避免手动操作 JsonObjectSchema：</p>
 *
 * <pre>{@code
 * ToolSpecification tool = MambaToolBuilder.builder()
 *     .name("navigate_to")
 *     .description("导航到指定页面")
 *     .paramString("destination", "目标页面名称", true)
 *     .build();
 * }</pre>
 *
 * <p>支持多种参数类型：</p>
 *
 * <pre>{@code
 * ToolSpecification tool = MambaToolBuilder.builder()
 *     .name("adjust_beauty")
 *     .description("调节美颜参数")
 *     .paramInteger("smoothing", "磨皮程度 0-100", false)
 *     .paramNumber("exposure", "曝光值", false)
 *     .paramBoolean("enable", "是否启用", true)
 *     .paramEnum("filter", "滤镜类型", List.of("NONE", "WARM", "COOL"), false)
 *     .build();
 * }</pre>
 *
 * @see ToolSpecification
 */
public class MambaToolBuilder {

    private MambaToolBuilder() {
        // utility class
    }

    /**
     * 创建新的 Builder 实例。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 快速创建无参数工具。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @return ToolSpecification
     */
    public static ToolSpecification simple(String name, String description) {
        return builder().name(name).description(description).build();
    }

    public static class Builder {
        private String name;
        private String description;
        private final Map<String, JsonSchemaElement> properties = new HashMap<>();
        private final List<String> required = new ArrayList<>();
        private Boolean strict;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 添加字符串参数。
         *
         * @param name        参数名
         * @param description 参数描述
         * @param isRequired  是否必填
         */
        public Builder paramString(String name, String description, boolean isRequired) {
            properties.put(name, JsonStringSchema.builder().description(description).build());
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        /**
         * 添加字符串参数（简化版，无描述）。
         */
        public Builder paramString(String name, boolean isRequired) {
            return paramString(name, null, isRequired);
        }

        /**
         * 添加整数参数。
         *
         * @param name        参数名
         * @param description 参数描述
         * @param isRequired  是否必填
         */
        public Builder paramInteger(String name, String description, boolean isRequired) {
            properties.put(name, JsonIntegerSchema.builder().description(description).build());
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        /**
         * 添加整数参数（简化版，无描述）。
         */
        public Builder paramInteger(String name, boolean isRequired) {
            return paramInteger(name, null, isRequired);
        }

        /**
         * 添加数值参数（浮点数）。
         *
         * @param name        参数名
         * @param description 参数描述
         * @param isRequired  是否必填
         */
        public Builder paramNumber(String name, String description, boolean isRequired) {
            properties.put(name, JsonNumberSchema.builder().description(description).build());
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        /**
         * 添加数值参数（简化版，无描述）。
         */
        public Builder paramNumber(String name, boolean isRequired) {
            return paramNumber(name, null, isRequired);
        }

        /**
         * 添加布尔参数。
         *
         * @param name        参数名
         * @param description 参数描述
         * @param isRequired  是否必填
         */
        public Builder paramBoolean(String name, String description, boolean isRequired) {
            properties.put(name, JsonBooleanSchema.builder().description(description).build());
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        /**
         * 添加布尔参数（简化版，无描述）。
         */
        public Builder paramBoolean(String name, boolean isRequired) {
            return paramBoolean(name, null, isRequired);
        }

        /**
         * 添加枚举参数。
         *
         * @param name        参数名
         * @param description 参数描述
         * @param values      枚举值列表
         * @param isRequired  是否必填
         */
        public Builder paramEnum(String name, String description, List<String> values, boolean isRequired) {
            properties.put(name, JsonEnumSchema.builder()
                    .description(description)
                    .enumValues(values)
                    .build());
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        /**
         * 添加枚举参数（简化版，无描述）。
         */
        public Builder paramEnum(String name, List<String> values, boolean isRequired) {
            return paramEnum(name, null, values, isRequired);
        }

        /**
         * 设置是否启用严格模式（schema 校验）。
         */
        public Builder strict(boolean strict) {
            this.strict = strict;
            return this;
        }

        /**
         * 构建 ToolSpecification。
         */
        public ToolSpecification build() {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Tool name must not be null or empty");
            }
            if (description == null || description.isEmpty()) {
                throw new IllegalArgumentException("Tool description must not be null or empty");
            }

            ToolSpecification.Builder builder = ToolSpecification.builder()
                    .name(name)
                    .description(description);

            if (!properties.isEmpty()) {
                JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder()
                        .addProperties(properties);
                if (!required.isEmpty()) {
                    schemaBuilder.required(required);
                }
                builder.parameters(schemaBuilder.build());
            }

            if (strict != null) {
                builder.strict(strict);
            }

            return builder.build();
        }
    }
}
