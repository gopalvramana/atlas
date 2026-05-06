package com.atlas.core;

public class AtlasQuery {

    private String question;   // the user's question
    private Version version;   // Spring AI version to filter chunks by; null means search all versions

    public AtlasQuery() {}

    public AtlasQuery(String question, Version version) {
        this.question = question;
        this.version = version;
    }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public Version getVersion() { return version; }
    public void setVersion(Version version) { this.version = version; }

    public boolean hasVersionFilter() {
        return version != null;
    }
}
