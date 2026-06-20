package com.mamba.agent.model.openai.internal;

import static com.mamba.agent.http.client.HttpMethod.GET;
import static com.mamba.agent.http.client.HttpMethod.POST;
import static com.mamba.agent.internal.Utils.getOrDefault;
import static com.mamba.agent.internal.Utils.isNullOrEmpty;
import static com.mamba.agent.internal.ValidationUtils.ensureNotBlank;
import static java.time.Duration.ofSeconds;

import com.mamba.agent.http.client.HttpClient;
import com.mamba.agent.http.client.HttpClientBuilder;
import com.mamba.agent.http.client.HttpClientBuilderLoader;
import com.mamba.agent.http.client.HttpRequest;
import com.mamba.agent.http.client.log.LoggingHttpClient;
import com.mamba.agent.model.openai.internal.audio.transcription.AudioFile;
import com.mamba.agent.model.openai.internal.audio.transcription.OpenAiAudioTranscriptionRequest;
import com.mamba.agent.model.openai.internal.audio.transcription.OpenAiAudioTranscriptionResponse;
import com.mamba.agent.model.openai.internal.chat.ChatCompletionRequest;
import com.mamba.agent.model.openai.internal.chat.ChatCompletionResponse;
import com.mamba.agent.model.openai.internal.completion.CompletionRequest;
import com.mamba.agent.model.openai.internal.completion.CompletionResponse;
import com.mamba.agent.model.openai.internal.embedding.EmbeddingRequest;
import com.mamba.agent.model.openai.internal.embedding.EmbeddingResponse;
import com.mamba.agent.model.openai.internal.image.GenerateImagesRequest;
import com.mamba.agent.model.openai.internal.image.GenerateImagesResponse;
import com.mamba.agent.model.openai.internal.models.ModelsListResponse;
import com.mamba.agent.model.openai.internal.moderation.ModerationRequest;
import com.mamba.agent.model.openai.internal.moderation.ModerationResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class DefaultOpenAiClient extends OpenAiClient {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final Map<String, String> defaultHeaders;
    private final Supplier<Map<String, String>> customHeadersSupplier;
    private final Map<String, String> customQueryParams;

    public DefaultOpenAiClient(Builder builder) {

        HttpClientBuilder httpClientBuilder =
                getOrDefault(builder.httpClientBuilder, HttpClientBuilderLoader::loadHttpClientBuilder);

        HttpClient httpClient = httpClientBuilder
                .connectTimeout(getOrDefault(
                        getOrDefault(builder.connectTimeout, httpClientBuilder.connectTimeout()), ofSeconds(15)))
                .readTimeout(
                        getOrDefault(getOrDefault(builder.readTimeout, httpClientBuilder.readTimeout()), ofSeconds(60)))
                .build();

        if (builder.logRequests || builder.logResponses) {
            this.httpClient =
                    new LoggingHttpClient(httpClient, builder.logRequests, builder.logResponses, builder.logger);
        } else {
            this.httpClient = httpClient;
        }

        this.baseUrl = ensureNotBlank(builder.baseUrl, "baseUrl");

        Map<String, String> defaultHeaders = new HashMap<>();
        if (builder.apiKey != null) {
            defaultHeaders.put("Authorization", "Bearer " + builder.apiKey);
        }
        if (builder.organizationId != null) {
            defaultHeaders.put("OpenAI-Organization", builder.organizationId);
        }
        if (builder.projectId != null) {
            defaultHeaders.put("OpenAI-Project", builder.projectId);
        }
        if (builder.userAgent != null) {
            defaultHeaders.put("User-Agent", builder.userAgent);
        }
        this.defaultHeaders = defaultHeaders;
        this.customHeadersSupplier = getOrDefault(builder.customHeadersSupplier, () -> Map::of);
        this.customQueryParams = builder.customQueryParams;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends OpenAiClient.Builder<DefaultOpenAiClient, Builder> {

        public DefaultOpenAiClient build() {
            return new DefaultOpenAiClient(this);
        }
    }

    private Map<String, String> buildRequestHeaders() {
        Map<String, String> dynamicHeaders = customHeadersSupplier.get();
        if (isNullOrEmpty(dynamicHeaders)) {
            return defaultHeaders;
        }

        Map<String, String> headers = new HashMap<>(defaultHeaders);
        headers.putAll(dynamicHeaders);
        return headers;
    }

    @Override
    public SyncOrAsyncOrStreaming<CompletionResponse> completion(CompletionRequest request) {

        HttpRequest httpRequest = HttpRequest.builder()
                .method(POST)
                .url(baseUrl, "completions")
                .addQueryParams(customQueryParams)
                .addHeader("Content-Type", "application/json")
                .addHeaders(buildRequestHeaders())
                .body(Json.toJson(
                        CompletionRequest.builder().from(request).stream(false).build()))
                .build();

        HttpRequest streamingHttpRequest = HttpRequest.builder()
                .method(POST)
                .url(baseUrl, "completions")
                .addQueryParams(customQueryParams)
                .addHeader("Content-Type", "application/json")
                .addHeaders(buildRequestHeaders())
                .body(Json.toJson(
                        CompletionRequest.builder().from(request).stream(true).build()))
                .build();

        return new RequestExecutor<>(httpClient, httpRequest, streamingHttpRequest, CompletionResponse.class);
    }

    @Override
    public SyncOrAsyncOrStreaming<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {

        HttpRequest httpRequest = HttpRequest.builder()
                .method(POST)
                .url(baseUrl, "chat/completions")
                .addQueryParams(customQueryParams)
                .addHeader("Content-Type", "application/json")
                .addHeaders(buildRequestHeaders())
                .body(Json.toJson(ChatCompletionRequest.builder().from(request).stream(false)
                        .build()))
                .build();

        HttpRequest streamingHttpRequest = HttpRequest.builder()
                .method(POST)
                .url(baseUrl, "chat/completions")
                .addQueryParams(customQueryParams)
                .addHeader("Content-Type", "application/json")
                .addHeaders(buildRequestHeaders())
                .body(Json.toJson(ChatCompletionRequest.builder().from(request).stream(true)
                        .build()))
                .build();

        return new RequestExecutor<>(httpClient, httpRequest, streamingHttpRequest, ChatCompletionResponse.class);
    }

    @Override
    public SyncOrAsync<EmbeddingResponse> embedding(EmbeddingRequest request) {

        HttpRequest httpRequest = HttpRequest.builder()
                .method(POST)
                .url(baseUrl, "embeddings")
                .addQueryParams(customQueryParams)
                .addHeader("Content-Type", "application/json")
                .addHeaders(buildRequestHeaders())
                .body(Json.toJson(request))
                .build();

        return new RequestExecutor<>(httpClient, httpRequest, EmbeddingResponse.class);
    }

    @Override
    public SyncOrAsync<ModerationResponse> moderation(ModerationRequest request) {

        HttpRequest httpRequest = HttpRequest.builder()
                .method(POST)
                .url(baseUrl, "moderations")
                .addQueryParams(customQueryParams)
                .addHeader("Content-Type", "application/json")
                .addHeaders(buildRequestHeaders())
                .body(Json.toJson(request))
                .build();

        return new RequestExecutor<>(httpClient, httpRequest, ModerationResponse.class);
    }

    @Override
    public SyncOrAsync<GenerateImagesResponse> imagesGeneration(GenerateImagesRequest request) {
        HttpRequest httpRequest = HttpRequest.builder()
                .method(POST)
                .url(baseUrl, "images/generations")
                .addQueryParams(customQueryParams)
                .addHeader("Content-Type", "application/json")
                .addHeaders(buildRequestHeaders())
                .body(Json.toJson(request))
                .build();

        return new RequestExecutor<>(httpClient, httpRequest, GenerateImagesResponse.class);
    }

    @Override
    public SyncOrAsync<OpenAiAudioTranscriptionResponse> audioTranscription(OpenAiAudioTranscriptionRequest request) {
        HttpRequest.Builder httpRequestBuilder = HttpRequest.builder()
                .method(POST)
                .url(baseUrl, "audio/transcriptions")
                .addHeader("Content-Type", "multipart/form-data; boundary=----LangChain4j")
                .addHeaders(buildRequestHeaders());

        httpRequestBuilder.addFormDataField("model", request.model());

        AudioFile file = request.file();
        httpRequestBuilder.addFormDataFile("file", file.fileName(), file.mimeType(), file.content());

        if (request.language() != null) {
            httpRequestBuilder.addFormDataField("language", request.language());
        }

        if (request.prompt() != null) {
            httpRequestBuilder.addFormDataField("prompt", request.prompt());
        }

        if (request.temperature() != null) {
            httpRequestBuilder.addFormDataField("temperature", Double.toString(request.temperature()));
        }

        return new RequestExecutor<>(httpClient, httpRequestBuilder.build(), OpenAiAudioTranscriptionResponse.class);
    }

    @Override
    public SyncOrAsync<ModelsListResponse> listModels() {
        HttpRequest httpRequest = HttpRequest.builder()
                .method(GET)
                .url(baseUrl, "models")
                .addQueryParams(customQueryParams)
                .addHeaders(buildRequestHeaders())
                .build();

        return new RequestExecutor<>(httpClient, httpRequest, ModelsListResponse.class);
    }
}
