package com.mamba.agent.model.image;

import com.mamba.agent.Experimental;
import com.mamba.agent.data.image.Image;
import com.mamba.agent.model.batch.BatchPage;
import com.mamba.agent.model.batch.BatchPagination;
import com.mamba.agent.model.batch.BatchRequest;
import com.mamba.agent.model.batch.BatchResponse;
import com.mamba.agent.model.output.Response;
import org.jspecify.annotations.Nullable;

/**
 * A model interface for processing multiple image generation requests asynchronously in a batch.
 *
 * <p>Batch processing typically offers significant cost reductions compared to real-time requests
 * and is ideal for large-scale, non-urgent tasks.</p>
 *
 * @see BatchResponse
 * @see BatchPage
 */
@Experimental
public interface BatchImageModel {

    /**
     * Creates a batch of image generation prompts and submits them for asynchronous processing.
     *
     * <p>The returned {@link BatchResponse} represents the status of the batch operation.</p>
     *
     * @param request the list of image generation prompts or requests to process
     * @return a {@link BatchResponse} representing the initial state of the batch operation
     */
    BatchResponse<Response<Image>> submit(BatchRequest<String> request);

    /**
     * Retrieves the current state and results of an image generation batch operation.
     *
     * <p>The response indicates whether the batch is still processing, completed successfully,
     * or failed. Once completed, the response will contain the generated image data.</p>
     *
     * @param batchId the batch identifier obtained from {@link #submit(BatchRequest)}
     * @return a {@link BatchResponse} representing the current state of the image batch operation
     */
    BatchResponse<Response<Image>> retrieve(String batchId);

    /**
     * Cancels an image generation batch operation that is currently pending or running.
     *
     * @param batchId the batch identifier to cancel
     */
    void cancel(String batchId);

    /**
     * Lists image generation batch jobs with optional pagination.
     *
     * @param pagination the maximum number of batch jobs to return and token for retrieving a specific page; if null, uses server default
     * @return a {@link BatchPage} containing image generation batch responses and pagination information
     */
    BatchPage<Response<Image>> list(@Nullable BatchPagination pagination);
}
