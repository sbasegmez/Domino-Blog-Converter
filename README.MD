# Domino Blog Converter

A small Java console application to migrate blog posts from an HCL Domino blog template to MkDocs (or other Markdown-based platforms).

## Features

* Connects to a Domino blog database using the HCL Domino JNX library.
* Converts rich-text HTML content (`RT` field) into Markdown.
* Downloads embedded images and stores them in a specified directory.
* Creates YAML front matter for each post, including title, date, author, categories, tags, and slug.
* Provides optional HTML output for debugging or archive.
* Handles custom formatting cases and common HTML cleanup tasks.

## Prerequisites

* Java 17 (x86-64). On Apple Silicon, use an Intel JVM (e.g., IBM Semeru).
* Maven (project is configured as a Maven project).
* Local installation of HCL Notes/Domino client.
* Environment variables configured for your Notes libraries (see below).

## Installation

1. Clone this repository:

   ```bash
   git clone https://github.com/sbasegmez/Domino-Blog-Converter.git
   cd Domino-Blog-Converter
   ```

2. Build the project with Maven:

   ```bash
   mvn clean package
   ```

## Configuration

Before running, set the following environment variables in your shell or IDE run configuration:

| Variable              | Description                                                                | Example                                                  |
| --------------------- | -------------------------------------------------------------------------- | -------------------------------------------------------- |
| `DB_NAME`             | Domino database to export in `Server!!path/to/file.nsf` format.            | `MyServer!!blogs/blog.nsf` or `blogs/blog.nsf` for local |
| `BASE_URL`            | Public URL of your blog ending with `/dx/`, used to download images.       | `https://www.example.com/blog.nsf/dx/`                   |
| `TARGET_BASE_DIR`     | Base directory for exported files (e.g., `docs` for MkDocs).               | `/path/to/project/docs`                                  |
| `PATH_POSTS`          | Relative directory for Markdown posts.                                     | `posts`                                                  |
| `PATH_IMG`            | Relative directory for downloaded images.                                  | `imported/images`                                        |
| `HTML_OUTPUT`         | `true` to generate corresponding HTML files for each page (for debugging). | `true`                                                   |
| `AUTHOR_NAME`         | Name to use in front matter for each post.                                 | `Jane Doe`                                               |
| `DYLD_LIBRARY_PATH`   | Path to Notes client library directory (`.so`/`.dll`).                     | `/Applications/HCL Notes.app/Contents/MacOS`             |
| `Notes_ExecDirectory` | Points to your Notes client executable directory.                          | `/Applications/HCL Notes.app/Contents/MacOS`             |
| `Notes_IDPath`        | (Optional) Full path to a Notes ID file (for passworded ID switching).     | `/Users/jane/notes.id`                                   |
| `Notes_IDPassword`    | (Optional) Password for the ID file specified above.                       | `mypassword`                                             |

> **Note**: On macOS, system protections will not let you set system-wide parameter for `DYLD_LIBRARY_PATH`. Define it in-session or within your IDE run configuration.

## Running the Application

```bash
# Example using environment variables in one command
export DB_NAME="MyServer!!blogs/blog.nsf" \
export BASE_URL="https://www.example.com/blog.nsf/dx/" \
export TARGET_BASE_DIR="./docs" \
export PATH_POSTS="posts" \
export PATH_IMG="imported/images" \
export AUTHOR_NAME="Jane Doe" \
export HTML_OUTPUT="true" \
java -jar target/domino-blog-exporter.jar
```

## Custom Formatting and Special Cases

* Converts `<div class="well">…</div>` blocks into Markdown blockquotes.
* Cleans up Domino HTML quirks (e.g., strips excess `<br>` tags, fixes multiline bold text).
* Inserts `<!-- more -->` markers in place of Domino's continue-reading tags.

## Known Issues

* Some HTML artifacts may remain (e.g., incorrect image captions, double line feeds after block elements).
* Preformatted text (`<pre>`) may collapse multiple spaces/tabs.

## Front Matter Structure

Each generated Markdown file includes YAML front matter:

```yaml
---
title: "<Subject>"
author: "<AUTHOR_NAME>"
slug: "<res_title-without-.htm>"
date: "<ISO-formatted creation date>"
categories: # Uses CustomCategory field. Supports only one
  - "<CustomCategory>"
tags: # Uses CustomTags field
  - "<CustomTag1>"  
  - "<CustomTag2>"
---
```

**Tip:** You can adjust or extend the front matter fields as needed for your target blog engine.

## Development

* Open in IntelliJ IDEA (recommended).
* Ensure environment variables for Notes libraries are set in your Run/Debug configuration.
* Modify source files under `src/main/java` and re-run via Maven or your IDE.

## License

Apache License 2.0. See [LICENSE](LICENSE) for details.
