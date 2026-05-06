package com.atlas.core;

public enum ChunkSource {

    SPRING_AI_DOCS("spring-ai-docs"),       // official Spring AI reference docs site
    SPRING_AI_GITHUB("spring-ai-github");   // spring-projects/spring-ai GitHub repo

    private final String label;  // stored in the chunks table source column

    ChunkSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static ChunkSource fromLabel(String label) {
        for (ChunkSource s : values()) {
            if (s.label.equalsIgnoreCase(label)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown source label: " + label);
    }
}
