package com.druk.llamacpp;

/**
 * Streaming callback from the inference service back to the app.
 *
 * `onResponseDelta` carries only the bytes appended since the previous
 * callback (a bandwidth optimization over sending the full accumulated
 * response each token). The proxy on the app side accumulates and forwards
 * the full string to the existing `LlamaGenerationCallback.onFullResponse`.
 *
 * `onGenerationFinished` is the terminal signal — generation will not call
 * onResponseDelta again for this session until a new startGeneration call.
 * statusCode mirrors the previous semantics: 0 = stopped naturally, non-zero
 * = error / cancelled.
 */
oneway interface ILlamaGenerationCallback {
    void onResponseDelta(String delta);
    /** The model started writing a tool call; sent once per startGeneration. */
    void onToolCallStarted();
    void onGenerationFinished(int statusCode);
}
