package com.atlas.core;

import java.util.List;

public class AtlasResponse {

    private String answer;                    // raw markdown returned by the model
    private List<Citation> citations;         // sources the answer is grounded in
    private List<Version> availableVersions;  // Turn 1 only — versions where the feature was found
                                              // empty in Turn 2 when a specific version is selected

    public AtlasResponse() {}

    public AtlasResponse(String answer, List<Citation> citations, List<Version> availableVersions) {
        this.answer = answer;
        this.citations = citations;
        this.availableVersions = availableVersions;
    }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public List<Citation> getCitations() { return citations; }
    public void setCitations(List<Citation> citations) { this.citations = citations; }

    public List<Version> getAvailableVersions() { return availableVersions; }
    public void setAvailableVersions(List<Version> availableVersions) { this.availableVersions = availableVersions; }

    public boolean isDiscoveryResponse() {
        return availableVersions != null && !availableVersions.isEmpty();
    }
}
