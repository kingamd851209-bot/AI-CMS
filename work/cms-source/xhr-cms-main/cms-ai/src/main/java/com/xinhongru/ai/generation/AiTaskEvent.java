package com.xinhongru.ai.generation;

/** CMS event contract. Engine-specific session IDs and tool payloads stay in adapters. */
public record AiTaskEvent(String type, String text) {
    public static AiTaskEvent delta(String text) {
        return new AiTaskEvent("delta", text);
    }

    public static AiTaskEvent progress(String text) {
        return new AiTaskEvent("progress", text);
    }

    public boolean terminal() {
        return type.equals("done") || type.equals("failed") || type.equals("cancelled");
    }
}
