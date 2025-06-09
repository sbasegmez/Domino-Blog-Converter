package com.developi.blog;

import static com.developi.blog.BlogConverter.BASE_URL;
import static com.developi.blog.BlogConverter.PATH_IMG;
import static com.developi.blog.BlogConverter.PATH_POSTS;

import com.hcl.domino.commons.util.MimeTypes;
import com.vladsch.flexmark.html.renderer.LinkType;
import com.vladsch.flexmark.html.renderer.ResolvedLink;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter.Builder;
import com.vladsch.flexmark.html2md.converter.HtmlLinkResolver;
import com.vladsch.flexmark.html2md.converter.HtmlLinkResolverFactory;
import com.vladsch.flexmark.html2md.converter.HtmlMarkdownWriter;
import com.vladsch.flexmark.html2md.converter.HtmlNodeConverterContext;
import com.vladsch.flexmark.html2md.converter.HtmlNodeRenderer;
import com.vladsch.flexmark.html2md.converter.HtmlNodeRendererFactory;
import com.vladsch.flexmark.html2md.converter.HtmlNodeRendererHandler;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

public class MarkDownConverter {

    private final FlexmarkHtmlConverter converter;
    private final BlogConverter blogConverter;
    private final HtmlLinkResolver localLinkResolver;

    // Shared HttpClient (thread-safe)
    private static final HttpClient SHARED_CLIENT = HttpClient.newHttpClient();

    MarkDownConverter(BlogConverter blogConverter) {
        this.blogConverter = blogConverter;
        this.localLinkResolver = new LocalLinkResolver(null);

        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS,
                    List.of(new CustomExtension()));

        var builder = FlexmarkHtmlConverter.builder(options);

        // Valid options: https://github.com/vsch/flexmark-java/wiki/Extensions#html-to-markdown
        builder.set(FlexmarkHtmlConverter.ADD_TRAILING_EOL, true);

        converter = builder.build();
    }

    /**
     * Converts a snippet of HTML into Markdown.
     *
     * @param html the input HTML string (must not be null)
     * @return a Markdown-formatted string
     */
    public String htmlToMarkdown(String html) {
        if (html == null) {
            throw new IllegalArgumentException("Input HTML must not be null");
        }

        return converter.convert(html);
    }

    /**
     * This class resolves blog links to proper format.
     * 1) Image links will be downloaded and converted to local links
     * 2) Internal links will be converted to local links
     */
    class LocalLinkResolver implements HtmlLinkResolver {

        public LocalLinkResolver(HtmlNodeConverterContext context) {
        }

        @Override
        public ResolvedLink resolveLink(Node node, HtmlNodeConverterContext context, ResolvedLink link) {

            if (link.getLinkType().equals(LinkType.IMAGE)) {
                // Only embedded images
                if (StringUtils.endsWithIgnoreCase(link.getUrl(), "?OpenElement")) {
                    return downloadBlogImage(link);
                }
            } else if (link.getLinkType().equals(LinkType.LINK)) {

                // Links inside the blog
                String newFileName = blogConverter.getFileNameMapping().get(link.getUrl());
                if (StringUtils.isNotEmpty(newFileName)) {
                    return link.withUrl(newFileName).withTitle(newFileName);
                }

            } else {
                System.out.println("Unsupported link type: " + link.getLinkType().getName());
            }

            return link;
        }
    }

    /**
     * This converts custom html. We use it to keep <iframe> and <script> tags. iframe is used for embedded videos. <script> is used for presentations.
     */
    static class CustomHtmlNodeConverter implements HtmlNodeRenderer {

        public CustomHtmlNodeConverter(DataHolder options) {

        }

        @Override
        public Set<HtmlNodeRendererHandler<?>> getHtmlNodeRendererHandlers() {
            return new HashSet<>(List.of(
                new HtmlNodeRendererHandler<>("iframe", Element.class, this::sendAsIs),
                new HtmlNodeRendererHandler<>("script", Element.class, this::sendAsIs)
            ));
        }

        private void sendAsIs(Element node, HtmlNodeConverterContext context, HtmlMarkdownWriter out) {
            out.append(node.outerHtml());
        }

        static class Factory implements HtmlNodeRendererFactory {

            @Override
            public HtmlNodeRenderer apply(DataHolder options) {
                return new CustomHtmlNodeConverter(options);
            }
        }
    }

    private ResolvedLink downloadBlogImage(ResolvedLink link) {
        try {
            String imgFileName = toValidFilename(link.getUrl());
            Path localImagePath = downloadImageUrl(link.getUrl(), imgFileName);

            String urlPrefix = PATH_POSTS.relativize(localImagePath.getParent()).toString();

            return link.withUrl(urlPrefix + "/" + localImagePath.getFileName());

        } catch (Exception e) {
            System.out.println("Failed to download image: " + link.getUrl());
            throw new RuntimeException(e);
        }
    }

    /**
     * Downloads an image from the given URL, inspects its Content-Type to decide on an extension,
     * and writes it into the specified local directory with the given base filename.
     *
     * @param imageUrl     the URL of the image to download
     * @param baseFileName the filename (without extension) to use when saving locally
     * @return the path to the saved file
     */
    public static Path downloadImageUrl(String imageUrl, String baseFileName) throws Exception {
        // 1. Build the GET request for the image URL
        HttpRequest request = HttpRequest.newBuilder()
                                         .uri(URI.create(BASE_URL + imageUrl))
                                         .GET()
                                         .build();

        // 2. Send the request, asking for the response body as a byte array
        HttpResponse<byte[]> response = SHARED_CLIENT.send(
            request,
            HttpResponse.BodyHandlers.ofByteArray()
        );

        // 3. Check the status code (200 OK expected)
        int statusCode = response.statusCode();
        if (statusCode != 200) {
            throw new IOException("Failed to download image: HTTP status " + statusCode);
        }

        // 4. Inspect the Content-Type header to determine the extension
        String contentType = response.headers()
                                     .firstValue("Content-Type")
                                     .orElseThrow(() -> new IOException("No Content-Type header in response"));

        // If contentType has parameters (e.g. "image/png; charset=UTF-8"), we strip them off.
        String extension = MimeTypes.getExtension(contentType.split(";")[0].trim());

        // 5. Build the full path: outputDir / (baseFileName + "." + extension)
        Path outputFilePath = PATH_IMG.resolve(baseFileName + "." + extension);

        // 6. Write the bytes to the file (overwrite if it already exists)
        Files.write(outputFilePath, response.body(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        return outputFilePath;
    }

    /**
     * Converts an input of the form:
     * {html-file-name}/content/M{n}?OpenElement
     * into a valid filename, e.g.
     * celebrating-openntfs-20th-anniversary-M3
     */
    public static String toValidFilename(String input) {
        // 1. Remove the literal "?OpenElement" if present
        //    (the trailing part after M<number>)
        input = input.replaceFirst("\\?OpenElement$", "");

        // 2. Split on "/content/" – we expect exactly one occurrence
        String[] parts = input.split("/content/", 2);
        if (parts.length < 2) {
            // If the format isn’t exactly as expected, either return a fallback
            // or throw an exception. Here, we’ll throw an exception.
            throw new IllegalArgumentException(
                "Input must contain '/content/' exactly once"
            );
        }

        // fPart = "html-file-name" / e.g. "celebrating-openntfs-20th-anniversary....htm"
        // 3a. Remove the extension from fPart (e.g. ".htm", ".html", ".xhtml", etc.)
        //     This regex strips off the last “.something” from the end.
        String fPart = parts[0].replaceFirst("\\.[^.]+$", "");

        // 3b. Replace any character that is NOT [A–Z a–z 0–9 -] with a hyphen
        //     This turns spaces, punctuation, even multiple dots into hyphens.
        fPart = fPart.replaceAll("[^A-Za-z0-9\\-]", "-");

        // 3c. Collapse multiple hyphens into a single hyphen
        fPart = fPart.replaceAll("-{2,}", "-");

        // 3d. Trim any leading or trailing hyphens
        fPart = fPart.replaceAll("^-|-$", "");

        // 4. Re‐join with a hyphen
        //    We assume mPart is already in the form "M<number>" (no further sanitisation needed).
        return fPart + "-" + parts[1];
    }


    class LinkResolverFactory implements HtmlLinkResolverFactory {

        @Override
        public Set<Class<?>> getAfterDependents() {
            return null;
        }

        @Override
        public Set<Class<?>> getBeforeDependents() {
            return null;
        }

        @Override
        public boolean affectsGlobalScope() {
            return false;
        }

        @Override
        public HtmlLinkResolver apply(HtmlNodeConverterContext context) {
            return localLinkResolver;
        }
    }

    class CustomExtension implements FlexmarkHtmlConverter.HtmlConverterExtension {

        public CustomExtension() {
        }

        @Override
        public void rendererOptions(@NotNull MutableDataHolder ignored) {
        }

        @Override
        public void extend(Builder builder) {
            builder.linkResolverFactory(new LinkResolverFactory());
            builder.htmlNodeRendererFactory(new CustomHtmlNodeConverter.Factory());
        }
    }
}
