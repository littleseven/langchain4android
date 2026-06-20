package com.mamba.agent.http.client.log;

import static com.mamba.agent.internal.Utils.getOrDefault;
import static com.mamba.agent.internal.ValidationUtils.ensureNotNull;

import com.mamba.agent.Internal;
import com.mamba.agent.exception.HttpException;
import com.mamba.agent.http.client.HttpClient;
import com.mamba.agent.http.client.HttpRequest;
import com.mamba.agent.http.client.SuccessfulHttpResponse;
import com.mamba.agent.http.client.sse.ServerSentEvent;
import com.mamba.agent.http.client.sse.ServerSentEventContext;
import com.mamba.agent.http.client.sse.ServerSentEventListener;
import com.mamba.agent.http.client.sse.ServerSentEventParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Internal
public class LoggingHttpClient implements HttpClient {

    private static final Logger DEFAULT_LOG = LoggerFactory.getLogger(LoggingHttpClient.class);

    private final HttpClient delegateHttpClient;
    private final boolean logRequests;
    private final boolean logResponses;
    private final Logger log;

    public LoggingHttpClient(HttpClient delegateHttpClient, Boolean logRequests, Boolean logResponses) {
        this.delegateHttpClient = ensureNotNull(delegateHttpClient, "delegateHttpClient");
        this.logRequests = getOrDefault(logRequests, false);
        this.logResponses = getOrDefault(logResponses, false);
        this.log = DEFAULT_LOG;
    }

    public LoggingHttpClient(HttpClient delegateHttpClient, Boolean logRequests, Boolean logResponses, Logger logger) {
        this.delegateHttpClient = ensureNotNull(delegateHttpClient, "delegateHttpClient");
        this.logRequests = getOrDefault(logRequests, false);
        this.logResponses = getOrDefault(logResponses, false);
        this.log = getOrDefault(logger, DEFAULT_LOG);
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException {

        if (logRequests) {
            HttpRequestLogger.log(log, request);
        }

        SuccessfulHttpResponse response = delegateHttpClient.execute(request);

        if (logResponses) {
            HttpResponseLogger.log(log, response);
        }

        return response;
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventListener delegateListener) {

        if (logRequests) {
            HttpRequestLogger.log(log, request);
        }

        this.delegateHttpClient.execute(request, new ServerSentEventListener() {

            @Override
            public void onOpen(SuccessfulHttpResponse response) {
                if (logResponses) {
                    HttpResponseLogger.log(log, response);
                }
                delegateListener.onOpen(response);
            }

            @Override
            public void onEvent(ServerSentEvent event) {
                if (logResponses) {
                    log.debug("{}", event);
                }
                delegateListener.onEvent(event);
            }

            @Override
            public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
                if (logResponses) {
                    log.debug("{}", event);
                }
                delegateListener.onEvent(event, context);
            }

            @Override
            public void onError(Throwable throwable) {
                delegateListener.onError(throwable);
            }

            @Override
            public void onClose() {
                delegateListener.onClose();
            }
        });
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener delegateListener) {

        if (logRequests) {
            HttpRequestLogger.log(log, request);
        }

        this.delegateHttpClient.execute(request, parser, new ServerSentEventListener() {

            @Override
            public void onOpen(SuccessfulHttpResponse response) {
                if (logResponses) {
                    HttpResponseLogger.log(log, response);
                }
                delegateListener.onOpen(response);
            }

            @Override
            public void onEvent(ServerSentEvent event) {
                if (logResponses) {
                    log.debug("{}", event);
                }
                delegateListener.onEvent(event);
            }

            @Override
            public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
                if (logResponses) {
                    log.debug("{}", event);
                }
                delegateListener.onEvent(event, context);
            }

            @Override
            public void onError(Throwable throwable) {
                delegateListener.onError(throwable);
            }

            @Override
            public void onClose() {
                delegateListener.onClose();
            }
        });
    }
}
