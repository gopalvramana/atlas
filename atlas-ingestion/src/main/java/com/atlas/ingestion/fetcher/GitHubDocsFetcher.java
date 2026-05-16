package com.atlas.ingestion.fetcher;

import com.atlas.ingestion.config.GitHubConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches raw .adoc file content from GitHub for a given branch.
 *
 * Single responsibility: retrieve raw .adoc content from GitHub API.
 * No text extraction, no chunking, no embedding — just fetch.
 *
 * Two GitHub API calls per file:
 *   1. Contents API — list files in a directory (for directory include-paths)
 *   2. Raw URL     — fetch raw .adoc content
 */
@Component
public class GitHubDocsFetcher {

    private static final Logger log = LoggerFactory.getLogger(GitHubDocsFetcher.class);

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String GITHUB_RAW_BASE = "https://raw.githubusercontent.com";

    private final GitHubConfig config;
    private final RestClient restClient;

    public GitHubDocsFetcher(GitHubConfig config) {
        this.config = config;
        this.restClient = RestClient.builder()
                .defaultHeader("Authorization", "Bearer " + config.getToken())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * Fetches all .adoc files for the given branch.
     *
     * @param branch e.g. "1.0.x"
     * @return list of fetched documents — raw .adoc content ready for extraction
     */
    public List<FetchedDocument> fetch(String branch) {
        List<FetchedDocument> results = new ArrayList<>();

        for (String includePath : config.getIncludePaths()) {
            if (includePath.endsWith(".adoc")) {
                // Direct file — fetch it immediately
                fetchFile(branch, includePath, results);
            } else {
                // Directory — list contents first, then fetch each .adoc file
                fetchDirectory(branch, includePath, results);
            }
        }

        log.info("Fetched {} files from GitHub — branch: {}", results.size(), branch);
        return results;
    }

    // --- private helpers ---

    private void fetchDirectory(String branch, String directoryPath, List<FetchedDocument> results) {
        String url = "%s/repos/%s/%s/contents/%s/%s?ref=%s"
                .formatted(GITHUB_API_BASE, config.getOwner(), config.getRepo(),
                        config.getDocsPath(), directoryPath, branch);

        try {
            List<Map<String, Object>> entries = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() {});

            if (entries == null) return;

            for (Map<String, Object> entry : entries) {
                String name = (String) entry.get("name");
                String type = (String) entry.get("type");
                String htmlUrl = (String) entry.get("html_url");

                if (!"file".equals(type)) continue;
                if (!name.endsWith(".adoc")) continue;
                if (isExcluded(name)) {
                    log.debug("Skipping excluded file: {}", name);
                    continue;
                }

                String filePath = directoryPath + "/" + name;
                fetchRawContent(branch, filePath, htmlUrl, name, results);
            }

        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Directory not found — branch: {}, path: {} — skipping", branch, directoryPath);
        } catch (HttpClientErrorException e) {
            log.error("GitHub API error listing directory — branch: {}, path: {}, status: {}",
                    branch, directoryPath, e.getStatusCode());
            throw new GitHubFetchException("GitHub API error listing directory: " + directoryPath, e);
        }
    }

    private void fetchFile(String branch, String filePath, List<FetchedDocument> results) {
        String filename = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;

        if (isExcluded(filename)) {
            log.debug("Skipping excluded file: {}", filename);
            return;
        }

        String contentsUrl = "%s/repos/%s/%s/contents/%s/%s?ref=%s"
                .formatted(GITHUB_API_BASE, config.getOwner(), config.getRepo(),
                        config.getDocsPath(), filePath, branch);

        try {
            Map<String, Object> entry = restClient.get()
                    .uri(contentsUrl)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() {});

            if (entry == null) return;

            String htmlUrl = (String) entry.get("html_url");
            fetchRawContent(branch, filePath, htmlUrl, filename, results);

        } catch (HttpClientErrorException.NotFound e) {
            log.warn("File not found — branch: {}, path: {} — skipping", branch, filePath);
        } catch (HttpClientErrorException e) {
            log.error("GitHub API error fetching file metadata — branch: {}, path: {}, status: {}",
                    branch, filePath, e.getStatusCode());
            throw new GitHubFetchException("GitHub API error fetching file: " + filePath, e);
        }
    }

    private void fetchRawContent(String branch, String filePath, String htmlUrl,
                                  String filename, List<FetchedDocument> results) {
        String rawUrl = "%s/%s/%s/%s/%s/%s"
                .formatted(GITHUB_RAW_BASE, config.getOwner(), config.getRepo(),
                        branch, config.getDocsPath(), filePath);

        try {
            String rawContent = restClient.get()
                    .uri(rawUrl)
                    .retrieve()
                    .body(String.class);

            if (rawContent == null || rawContent.isBlank()) {
                log.warn("Empty content — skipping file: {}", filename);
                return;
            }

            results.add(new FetchedDocument(filename, htmlUrl, rawContent));
            log.debug("Fetched: {}", filename);

        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Raw content not found — branch: {}, file: {} — skipping", branch, filename);
        } catch (HttpClientErrorException e) {
            log.error("GitHub API error fetching raw content — file: {}, status: {}",
                    filename, e.getStatusCode());
            throw new GitHubFetchException("GitHub API error fetching raw content: " + filename, e);
        }
    }

    private boolean isExcluded(String filename) {
        return config.getExcludeFiles() != null
                && config.getExcludeFiles().contains(filename);
    }
}
