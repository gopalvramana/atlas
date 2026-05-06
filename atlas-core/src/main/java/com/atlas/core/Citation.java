package com.atlas.core;

public class Citation {

    private String section;  // doc section the chunk came from e.g. "ChatClient"
    private String url;      // direct link to the source page
    private String excerpt;  // the exact chunk text the answer is grounded in

    public Citation() {}

    public Citation(String section, String url, String excerpt) {
        this.section = section;
        this.url = url;
        this.excerpt = excerpt;
    }

    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getExcerpt() { return excerpt; }
    public void setExcerpt(String excerpt) { this.excerpt = excerpt; }
}
