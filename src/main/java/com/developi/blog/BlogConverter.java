package com.developi.blog;

import com.developi.jnx.utils.DominoRunner;
import com.hcl.domino.DominoClient;
import com.hcl.domino.commons.json.JsonUtil;
import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.data.DominoDateTime;
import com.hcl.domino.richtext.RichTextRecordList;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

public class BlogConverter {

    public static void main(String[] args) {
        var blogConverter = new BlogConverter();
        DominoRunner.runWithDominoClient(blogConverter::run, false);
    }

    // Database name (Set via environment property DB_NAME: e.g. server!!blog.nsf)
    public static String DB_NAME = System.getenv("DB_NAME");

    // Base URL for image downloads (Set via environment property BASE_URL: e.g. https://www.example.com/blog.nsf/dx/)
    public static String BASE_URL = System.getenv("BASE_URL");

    // Target directory for exported files (Set via environment property BASE_DIR: e.g. /path/to/blog/project)
    public static Path PATH_BASE = Path.of(StringUtils.defaultIfEmpty(System.getenv("TARGET_BASE_DIR"), ""));

    // Target directory for exported posts (Override via environment property POSTS_DIR)
    public static Path PATH_POSTS = PATH_BASE.resolve(StringUtils.defaultIfEmpty(System.getenv("POSTS_DIR"), "posts/imported"));

    // Target directory for exported images (Override via environment property IMG_DIR)
    public static Path PATH_IMG = PATH_BASE.resolve(StringUtils.defaultIfEmpty(System.getenv("IMG_DIR"), "images/imported"));

    // Author name (Override via environment property AUTHOR_NAME)
    public static final String AUTHOR_NAME = StringUtils.defaultIfEmpty(System.getenv("AUTHOR_NAME"), "Unknown");

    // Set to true to enable HTML file output (for debugging) (Override via environment property HTML_OUTPUT)
    public boolean HTML_OUTPUT = Boolean.parseBoolean(System.getenv("HTML_OUTPUT"));

    private final Map<String, Document> documentCache = new java.util.HashMap<>();
    private final Map<String, String> fileNameMapping = new java.util.HashMap<>();

    // Markdown Converter
    private final MarkDownConverter markDownConverter = new MarkDownConverter(this);

    static {
        Objects.requireNonNull(DB_NAME, "Environment variable DB_NAME is required. Set via environment property DB_NAME");
        Objects.requireNonNull(BASE_URL, "Environment variable BASE_URL is required. Set via environment property BASE_URL");
        Objects.requireNonNull(System.getenv("TARGET_BASE_DIR"), "Environment variable TARGET_BASE_DIR is required. Set via environment property TARGET_BASE_DIR");
    }

    public BlogConverter() {
        System.out.println("Database: " + DB_NAME);
        System.out.println("Base URL: " + BASE_URL);
        System.out.println("Target Base directory: " + PATH_BASE.toString());
        System.out.println("Target Post directory: " + PATH_POSTS.toString());
        System.out.println("Target Image directory: " + PATH_IMG.toString());
        System.out.println("Author name: " + AUTHOR_NAME);
        System.out.println("HTML output: " + HTML_OUTPUT);

        // Ensure directories exist
        try {
            if (!Files.exists(PATH_POSTS)) {
                    Files.createDirectories(PATH_POSTS);
            }
            if (!Files.exists(PATH_IMG)) {
                    Files.createDirectories(PATH_IMG);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public void run(DominoClient client) {

        Database db = client.openDatabase(DB_NAME);
        db.openCollection("vContent2")
          .orElseThrow()
          .forEachDocument(0, Integer.MAX_VALUE, (doc, loop) -> {
              String pageName = doc.getAsText("res_title", ' ');

              DominoDateTime dateCreated = doc.get("fulldatetime", DominoDateTime.class, doc.getCreated());
              String datePrefix = String.format("%d-%02d", dateCreated.get(ChronoField.YEAR), dateCreated.get(ChronoField.MONTH_OF_YEAR));

              String fileName = datePrefix + "-" + pageName.replace(".htm", ".md");

              System.out.println(loop.getIndex() + "." + pageName + ": Caching...");

              fileNameMapping.put(pageName, fileName);
              documentCache.put(pageName, doc);
          });

        documentCache.forEach(this::export);

    }

    public Map<String, String> getFileNameMapping() {
        return fileNameMapping;
    }

    private void export(String pageName, Document doc) {
        String slug = pageName.endsWith(".htm") ? pageName.substring(0, pageName.length() - 4) : pageName;
        File targetFile = PATH_POSTS.resolve(fileNameMapping.get(pageName)).toFile();

        if (targetFile.exists()) {
            if (!targetFile.delete()) {
                System.err.println("Error deleting existing file");
            }
        }

        String htmlContent = preprocessHtml(extractPostHtml(doc));
        String mdContent = processMarkdown(markDownConverter.htmlToMarkdown(htmlContent));
        String frontMatter = createFrontMatter(doc);

        try (var writer = new FileWriter(targetFile)) {
            IOUtils.write(frontMatter + mdContent, writer);
        } catch (Exception e) {
            System.err.println("Error writing file: " + e.getMessage());
        }

        if (HTML_OUTPUT) {
            try (var writer = new FileWriter(PATH_POSTS.resolve(slug + ".html").toFile())) {
                IOUtils.write(htmlContent, writer);
            } catch (Exception e) {
                System.err.println("Error writing file: " + e.getMessage());
            }
        }
    }

    private String preprocessHtml(String content) {
        // Remove <u>. Not supported by Markdown
        content = content.replaceAll("<(/?)u>", "");

        // Convert <div.well> to <blockquote>
        content = content.replaceAll(
            "<div\\s+class=\"well\">([\\s\\S]*?)</div>",
            "<blockquote>$1</blockquote>"
        );

        // This is an issue with the blog posts. Bold lines stick to the previous line.
        content = content.replaceAll(
            "<strong>((.?<br />)+)",
            "$1<strong>"
        );

        content = content.replaceAll(
            "</h([1-6])><br />",
            "</h$1>"
        );

        // Multiple linebreaks to two.
        content = content.replaceAll(
            "(<br />(\\s+)?){3,}",
            "<br /><br />"
        );

        return content;
    }

    private String processMarkdown(String s) {
        StringBuilder sb = new StringBuilder();

        String[] lines = s.split("\n");

        for (String string : lines) {
            String line = string.trim();

            if (line.contains("\\<$DXContinueReading$\\>")) {
                line = line.replace("\\<$DXContinueReading$\\>", "<!-- more -->");
            }

            sb.append(line).append("\n");
        }

        return sb.toString();
    }

    private String createFrontMatter(Document doc) {
        StringBuilder sb = new StringBuilder();

        DominoDateTime dateCreated = doc.get("fulldatetime", DominoDateTime.class, doc.getCreated());
        String dateFormatted = JsonUtil.toIsoString(dateCreated);
        String title = doc.getAsText("Subject", ' ');
        String slug = doc.getAsText("res_title", ' ');
        if (slug.endsWith(".htm")) {
            slug = slug.substring(0, slug.length() - 4);
        }
        List<String> tags = doc.getAsList("CustomTags", String.class, List.of());
        String category = doc.getAsText("CustomCategory", ' ');

        sb.append("---\n")
          .append("authors:\n").append("  - "). append(AUTHOR_NAME).append("\n\n")
          .append("title: \"").append(title.replace("\"", "\\\"")).append("\"\n\n")
          .append("slug: ").append(slug).append("\n\n")
          .append("date: ").append(dateFormatted).append("\n\n");

        if(StringUtils.isNotEmpty(category)) {
            sb.append("categories:\n").append("  - ").append(doc.getAsText("CustomCategory", ' ')).append("\n\n");
        }

        if(!tags.isEmpty() && StringUtils.isNotEmpty(tags.get(0))) {
            sb.append("tags:\n");
            tags.forEach(tag -> sb.append("  - ").append(tag).append("\n"));
        }

        sb.append("---\n\n");

        return sb.toString();
    }

    private String extractPostHtml(Document doc) {
        // Blog database has a RichText field called "Rt" containing the calculated HTML output
        RichTextRecordList item = doc.getRichTextItem("Rt");

        return item.extractText();
    }
}
