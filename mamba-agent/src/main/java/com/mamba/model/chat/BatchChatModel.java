package com.mamba.model.chat;

import com.mamba.Experimental;
import com.mamba.model.batch.BatchPage;
import com.mamba.model.batch.BatchPagination;
import com.mamba.model.batch.BatchRequest;
import com.mamba.model.batch.BatchResponse;
import com.mamba.model.chat.request.ChatRequest;
import com.mamba.model.chat.response.ChatResponse;
import org.jspecify.annotations.Nullable;

/**
 * A model interface for processing multiple chat requests asynchronously in a batch.
 *
 * <p>Batch processing typically offers significant cost reductions compared to real-time chat requests
 * and is ideal for large-scale, non-urgent conversational or instruction-following tasks.</p>
 *
 * @see BatchResponse
 * @see BatchPage
 */
@Experimental
public interface BatchChatModel {

    /**
     * Creates a batch of chat requests and submits them for asynchronous processing.
     *
     * <p>The returned {@link BatchResponse} represents the status of the batch operation.</p>
     *
     * @param request the list of chat requests to process in the batch
     * @return a {@link BatchResponse} representing the initial state of the batch operation
     */
    BatchResponse<ChatResponse> submit(BatchRequest<ChatRequest> request);

    /**
     * Retrieves the current state and results of a chat batch operation.
     *
     * <p>The response indicates whether the batch is still processing, completed successfully,
     * or failed. Clients should poll this method at intervals until the batch completes.</p>
     *
     * @param batchId the batch identifier obtained from {@link #submit(BatchRequest)}
     * @return a {@link BatchResponse} representing the current state of the chat batch operation
     */
    BatchResponse<ChatResponse> retrieve(String batchId);

    /**
     * Cancels a chat batch operation that is currently pending or running.
     *
     * @param batchId the batch identifier to cancel
     */
    void cancel(String batchId);

    /**
     * Lists chat batch jobs with optional pagination.
     *
     * @param pagination the maximum number of batch jobs to return and token for retrieving a specific page;
     *                   if null, uses server default
     * @return a {@link BatchPage} containing chat batch responses and pagination information
     */
    BatchPage<ChatResponse> list(@Nullable BatchPagination pagination);
}
