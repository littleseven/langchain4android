package com.mamba.model.audio;

import com.mamba.Experimental;
import com.mamba.data.audio.Audio;
import com.mamba.model.ModelProvider;

import static com.mamba.model.ModelProvider.OTHER;

/**
 * A model that can transcribe audio into text.
 */
@Experimental
public interface AudioTranscriptionModel {

    /**
     * Given an audio transcription request, generates a transcription.
     *
     * @param request The transcription request containing the audio file and optional parameters
     * @return The generated transcription response
     */
    AudioTranscriptionResponse transcribe(AudioTranscriptionRequest request);

    /**
     * Convenience method for simple transcription needs.
     * Given an audio file, generates a transcription.
     *
     * @param audio The audio file to generate a transcription from
     * @return The generated transcription as a plain string
     */
    default String transcribeToText(Audio audio) {
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.builder(audio).build();
        AudioTranscriptionResponse response = transcribe(request);
        return response.text();
    }

    default ModelProvider provider() {
        return OTHER;
    }
}
