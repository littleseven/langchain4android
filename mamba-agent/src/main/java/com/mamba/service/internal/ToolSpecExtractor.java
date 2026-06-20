package com.mamba.service.internal;

import com.mamba.model.chat.request.json.JsonArraySchema;
import com.mamba.model.chat.request.json.JsonBooleanSchema;
import com.mamba.model.chat.request.json.JsonEnumSchema;
import com.mamba.model.chat.request.json.JsonIntegerSchema;
import com.mamba.model.chat.request.json.JsonNumberSchema;
import com.mamba.model.chat.request.json.JsonObjectSchema;
import com.mamba.model.chat.request.json.JsonSchemaElement;
import com.mamba.model.chat.request.json.JsonStringSchema;
import com.mamba.tool.P;
import com.mamba.tool.Tool;
import com.mamba.tool.ToolSpecification;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Java 反射的 ToolSpecification 提取器。
 *
 * <p>Android 兼容版本：扫描对象上的 @Tool 注解方法，
 * 提取方法名、描述、参数信息生成 ToolSpecification。</p>
 *
 * <p>不依赖 Kotlin 反射或 ServiceLoader，纯 Java 标准反射实现。</p>
 */
public class ToolSpecExtractor {

    private ToolSpecExtractor() {
        // utility class
    }

    /**
     * 从工具实例提取所有 ToolSpecification。
     *
     * @param tool 工具实例（包含 @Tool 注解方法）
     * @return ToolSpecification 列表
     */
    public static List<ToolSpecification> extract(Object tool) {
        List<ToolSpecification> result = new ArrayList<>();
        if (tool == null) {
            return result;
        }

        for (Method method : tool.getClass().getDeclaredMethods()) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation == null) {
                continue;
            }

            String name = toolAnnotation.name();
            if (name.isEmpty()) {
                name = method.getName();
            }

            String[] descriptions = toolAnnotation.value();
            String description = descriptions.length > 0 ? String.join(" ", descriptions) : "";

            JsonObjectSchema parameters = extractParameters(method);

            ToolSpecification spec = ToolSpecification.builder()
                    .name(name)
                    .description(description)
                    .parameters(parameters)
                    .build();

            result.add(spec);
        }

        return result;
    }

    /**
     * 从方法参数提取 JSON Schema。
     */
    private static JsonObjectSchema extractParameters(Method method) {
        Parameter[] params = method.getParameters();
        if (params.length == 0) {
            return JsonObjectSchema.builder().build();
        }

        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        List<String> required = new ArrayList<>();

        for (Parameter param : params) {
            P pAnnotation = param.getAnnotation(P.class);
            String paramName = getParamName(param, pAnnotation);
            String paramDescription = getParamDescription(pAnnotation);
            boolean isRequired = pAnnotation == null || pAnnotation.required();

            JsonSchemaElement schemaElement = toJsonSchema(param.getType(), paramDescription);
            builder.addProperty(paramName, schemaElement);

            if (isRequired) {
                required.add(paramName);
            }
        }

        if (!required.isEmpty()) {
            builder.required(required);
        }

        return builder.build();
    }

    /**
     * 获取参数名称（优先 @P.name，否则使用反射参数名）。
     */
    private static String getParamName(Parameter param, P pAnnotation) {
        if (pAnnotation != null && !pAnnotation.name().isEmpty()) {
            return pAnnotation.name();
        }
        // Java 8+ 编译时保留参数名需要 -parameters 选项
        // 如果没有，使用 param.getName() 可能返回 arg0, arg1 等
        String name = param.getName();
        if (name == null || name.startsWith("arg")) {
            // 回退：使用参数类型作为名称（不太理想但可用）
            return "param_" + param.getType().getSimpleName().toLowerCase();
        }
        return name;
    }

    /**
     * 获取参数描述。
     */
    private static String getParamDescription(P pAnnotation) {
        if (pAnnotation == null) {
            return "";
        }
        if (!pAnnotation.value().isEmpty()) {
            return pAnnotation.value();
        }
        if (!pAnnotation.description().isEmpty()) {
            return pAnnotation.description();
        }
        return "";
    }

    /**
     * 将 Java 类型映射为 JSON Schema 元素。
     */
    private static JsonSchemaElement toJsonSchema(Class<?> type, String description) {
        if (type == String.class) {
            return JsonStringSchema.builder()
                    .description(description)
                    .build();
        } else if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class) {
            return JsonIntegerSchema.builder()
                    .description(description)
                    .build();
        } else if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) {
            return JsonNumberSchema.builder()
                    .description(description)
                    .build();
        } else if (type == boolean.class || type == Boolean.class) {
            return JsonBooleanSchema.builder()
                    .description(description)
                    .build();
        } else if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            List<String> enumValues = new ArrayList<>();
            if (constants != null) {
                for (Object c : constants) {
                    enumValues.add(c.toString());
                }
            }
            return JsonEnumSchema.builder()
                    .enumValues(enumValues)
                    .description(description)
                    .build();
        } else if (type.isArray() || List.class.isAssignableFrom(type)) {
            // 简化：数组/列表作为字符串处理（或可以进一步展开）
            return JsonStringSchema.builder()
                    .description(description)
                    .build();
        } else {
            // 默认作为字符串处理
            return JsonStringSchema.builder()
                    .description(description)
                    .build();
        }
    }
}
