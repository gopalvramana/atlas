package com.atlas.core;

public enum Version {

    V_1_0_GA("1.0-GA", "1.0.x"),
    V_1_1("1.1", "1.1.x"),
    V_2_0_M("2.0-M", "main");

    private final String label;   // used in queries, responses, and DB storage
    private final String branch;  // GitHub branch name used during ingestion

    Version(String label, String branch) {
        this.label = label;
        this.branch = branch;
    }

    public String getLabel() {
        return label;
    }

    public String getBranch() {
        return branch;
    }

    // Convert from label (e.g. "1.0-GA") to enum constant (e.g. Version.V_1_0_GA)
    public static Version fromLabel(String label) {
        for (Version v : values()) {
            if (v.label.equalsIgnoreCase(label)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown version label: " + label);
    }
}
