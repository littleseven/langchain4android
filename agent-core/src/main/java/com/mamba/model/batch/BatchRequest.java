package com.mamba.model.batch;

import static com.mamba.internal.Utils.copy;
import static com.mamba.internal.ValidationUtils.ensureNotNull;

import com.mamba.Experimental;
import java.util.List;
import java.util.Objects;

/**
 * Represents a batch of requests to be processed together.
 *
 * @param <T> The type of the requests in this batch.
 */
@Experimental
public class BatchRequest<T> {

    private final List<T> requests;

    /**
     * Creates a new BatchRequest.
     *
     * @param requests The list of requests. Must not be null.
     */
    public BatchRequest(List<T> requests) {
        this.requests = copy(ensureNotNull(requests, "requests"));
    }

    /**
     * Returns the list of requests in this batch.
     *
     * @return The requests.
     */
    public List<T> requests() {
        return requests;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        BatchRequest<?> that = (BatchRequest<?>) o;
        return Objects.equals(requests, that.requests);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(requests);
    }

    @Override
    public String toString() {
        return "BatchRequest{" + "requests=" + requests + '}';
    }
}
