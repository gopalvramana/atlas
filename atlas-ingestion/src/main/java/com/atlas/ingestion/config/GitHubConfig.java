package com.atlas.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Binds atlas.ingestion.github.* from application.yml into a typed object.
 *
 * Single responsibility: configuration binding only.
 * No logic, no API calls — just typed access to YAML values.
 */
@Component
@ConfigurationProperties(prefix = "atlas.ingestion.github")
public class GitHubConfig {

    private String owner;
    private String repo;
    private String docsPath;
    private String token;
    private List<VersionConfig> versions;
    private List<String> includePaths;
    private List<String> excludeFiles;

    // --- nested type ---

    public static class VersionConfig {
        private String label;
        private String branch;

        public String getLabel()             { return label; }
        public void setLabel(String label)   { this.label = label; }
        public String getBranch()            { return branch; }
        public void setBranch(String branch) { this.branch = branch; }
    }

    // --- getters and setters ---

    public String getOwner()                          { return owner; }
    public void setOwner(String owner)                { this.owner = owner; }

    public String getRepo()                           { return repo; }
    public void setRepo(String repo)                  { this.repo = repo; }

    public String getDocsPath()                       { return docsPath; }
    public void setDocsPath(String docsPath)          { this.docsPath = docsPath; }

    public String getToken()                          { return token; }
    public void setToken(String token)                { this.token = token; }

    public List<VersionConfig> getVersions()                      { return versions; }
    public void setVersions(List<VersionConfig> versions)         { this.versions = versions; }

    public List<String> getIncludePaths()                         { return includePaths; }
    public void setIncludePaths(List<String> includePaths)        { this.includePaths = includePaths; }

    public List<String> getExcludeFiles()                         { return excludeFiles; }
    public void setExcludeFiles(List<String> excludeFiles)        { this.excludeFiles = excludeFiles; }
}
