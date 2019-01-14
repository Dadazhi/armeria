/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.annotation.Nullable;

import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;

import com.linecorp.armeria.internal.PathAndQuery;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.logging.LoggingService;

import io.netty.handler.codec.DateFormatter;

@RunWith(Parameterized.class)
public class HttpFileServiceTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Pattern ETAG_PATTERN = Pattern.compile("^\"[^\"]+\"$");

    private static final String baseResourceDir =
            HttpFileServiceTest.class.getPackage().getName().replace('.', '/') + '/';
    private static final File tmpDir;

    private static final Server server;
    private static int httpPort;

    @Parameters(name = "{index}: cached={0}")
    public static Collection<Boolean> parameters() {
        return ImmutableSet.of(true, false);
    }

    static {
        try {
            tmpDir = Files.createTempDirectory("armeria-test.").toFile();
        } catch (Exception e) {
            throw new Error(e);
        }

        final ServerBuilder sb = new ServerBuilder();

        try {
            sb.serviceUnder(
                    "/cached/fs/",
                    HttpFileService.forFileSystem(tmpDir.toPath()));

            sb.serviceUnder(
                    "/uncached/fs/",
                    HttpFileServiceBuilder.forFileSystem(tmpDir.toPath())
                                          .maxCacheEntries(0)
                                          .build());

            sb.serviceUnder(
                    "/cached/compressed/",
                    HttpFileServiceBuilder.forClassPath(baseResourceDir + "foo")
                                          .serveCompressedFiles(true)
                                          .build());
            sb.serviceUnder(
                    "/uncached/compressed/",
                    HttpFileServiceBuilder.forClassPath(baseResourceDir + "foo")
                                          .serveCompressedFiles(true)
                                          .maxCacheEntries(0)
                                          .build());

            sb.serviceUnder(
                    "/cached/classes/",
                    HttpFileService.forClassPath("/"));
            sb.serviceUnder(
                    "/uncached/classes/",
                    HttpFileServiceBuilder.forClassPath("/")
                                          .maxCacheEntries(0)
                                          .build());

            sb.serviceUnder(
                    "/cached/",
                    HttpFileService.forClassPath(baseResourceDir + "foo")
                                   .orElse(HttpFileService.forClassPath(baseResourceDir + "bar")));
            sb.serviceUnder(
                    "/uncached/",
                    HttpFileServiceBuilder.forClassPath(baseResourceDir + "foo")
                                          .maxCacheEntries(0)
                                          .build()
                                          .orElse(HttpFileServiceBuilder.forClassPath(baseResourceDir + "bar")
                                                                        .maxCacheEntries(0)
                                                                        .build()));

            sb.decorator(LoggingService.newDecorator());
        } catch (Exception e) {
            throw new Error(e);
        }
        server = sb.build();
    }

    @BeforeClass
    public static void init() throws Exception {
        server.start().get();

        httpPort = server.activePorts().values().stream()
                         .filter(ServerPort::hasHttp).findAny().get().localAddress().getPort();
    }

    @AfterClass
    public static void destroy() throws Exception {
        server.stop();

        // Delete the temporary files created for testing against the real file system.
        Files.walkFileTree(tmpDir.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private final boolean cached;

    public HttpFileServiceTest(boolean cached) {
        this.cached = cached;
    }

    @Before
    public void setUp() {
        PathAndQuery.clearCachedPaths();
    }

    @Test
    public void testClassPathGet() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final String lastModified;
            final String etag;
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/foo.txt")))) {
                assert200Ok(res, "text/plain", "foo");
                lastModified = header(res, HttpHeaders.LAST_MODIFIED);
                etag = header(res, HttpHeaders.ETAG);
            }

            assert304NotModified(hc, "/foo.txt", etag, lastModified);

            // Confirm file service paths are cached when cache is enabled.
            if (cached) {
                assertThat(PathAndQuery.cachedPaths()).contains("/cached/foo.txt");
            }
        }
    }

    @Test
    public void testClassPathGetUtf8() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/%C2%A2.txt")))) {
                assert200Ok(res, "text/plain", "¢");
            }
        }
    }

    @Test
    public void testClassPathGetFromModule() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            // Read a class from a JDK module (java.base).
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(newUri("/classes/java/lang/Object.class")))) {
                assert200Ok(res, null, content -> assertThat(content).isNotEmpty());
            }
        }
    }

    @Test
    public void testClassPathGetFromJar() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            // Read a class from a third-party library JAR.
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(newUri("/classes/io/netty/util/NetUtil.class")))) {
                assert200Ok(res, null, content -> assertThat(content).isNotEmpty());
            }
        }
    }

    @Test
    public void testClassPathOrElseGet() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal();
             CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/bar.txt")))) {
            assert200Ok(res, "text/plain", "bar");
        }
    }

    @Test
    public void testIndexHtml() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/")))) {
                assert200Ok(res, "text/html", "<html><body></body></html>");
            }
        }
    }

    @Test
    public void testUnknownMediaType() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal();
             CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/bar.unknown")))) {
            assert200Ok(res, null, "Unknown Media Type");
            final String lastModified = header(res, HttpHeaders.LAST_MODIFIED);
            final String etag = header(res, HttpHeaders.ETAG);
            assert304NotModified(hc, "/bar.unknown", etag, lastModified);
        }
    }

    @Test
    public void testGetPreCompressedSupportsNone() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpGet request = new HttpGet(newUri("/compressed/foo.txt"));
            try (CloseableHttpResponse res = hc.execute(request)) {
                assertThat(res.getFirstHeader("Content-Encoding")).isNull();
                assertThat(headerOrNull(res, "Content-Type")).isEqualTo(
                        "text/plain; charset=utf-8");
                final byte[] content = ByteStreams.toByteArray(res.getEntity().getContent());
                assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("foo");

                // Confirm path not cached when cache disabled.
                assertThat(PathAndQuery.cachedPaths())
                        .doesNotContain("/compressed/foo.txt");
            }
        }
    }

    @Test
    public void testGetPreCompressedSupportsGzip() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpGet request = new HttpGet(newUri("/compressed/foo.txt"));
            request.setHeader("Accept-Encoding", "gzip");
            try (CloseableHttpResponse res = hc.execute(request)) {
                assertThat(headerOrNull(res, "Content-Encoding")).isEqualTo("gzip");
                assertThat(headerOrNull(res, "Content-Type")).isEqualTo(
                        "text/plain; charset=utf-8");
                final byte[] content;
                try (GZIPInputStream unzipper = new GZIPInputStream(res.getEntity().getContent())) {
                    content = ByteStreams.toByteArray(unzipper);
                }
                assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("foo");
            }
        }
    }

    @Test
    public void testGetPreCompressedSupportsBrotli() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpGet request = new HttpGet(newUri("/compressed/foo.txt"));
            request.setHeader("Accept-Encoding", "br");
            try (CloseableHttpResponse res = hc.execute(request)) {
                assertThat(headerOrNull(res, "Content-Encoding")).isEqualTo("br");
                assertThat(headerOrNull(res, "Content-Type")).isEqualTo(
                        "text/plain; charset=utf-8");
                // Test would be more readable and fun by decompressing like the gzip one, but since JDK doesn't
                // support brotli yet, just compare the compressed content to avoid adding a complex dependency.
                final byte[] content = ByteStreams.toByteArray(res.getEntity().getContent());
                assertThat(content).containsExactly(
                        Resources.toByteArray(Resources.getResource(baseResourceDir + "foo/foo.txt.br")));
            }
        }
    }

    @Test
    public void testGetPreCompressedSupportsBothPrefersBrotli() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpGet request = new HttpGet(newUri("/compressed/foo.txt"));
            request.setHeader("Accept-Encoding", "gzip, br");
            try (CloseableHttpResponse res = hc.execute(request)) {
                assertThat(headerOrNull(res, "Content-Encoding")).isEqualTo("br");
                assertThat(headerOrNull(res, "Content-Type")).isEqualTo(
                        "text/plain; charset=utf-8");
                // Test would be more readable and fun by decompressing like the gzip one, but since JDK doesn't
                // support brotli yet, just compare the compressed content to avoid adding a complex dependency.
                final byte[] content = ByteStreams.toByteArray(res.getEntity().getContent());
                assertThat(content).containsExactly(
                        Resources.toByteArray(Resources.getResource(baseResourceDir + "foo/foo.txt.br")));
            }
        }
    }

    @Test
    public void testFileSystemGet() throws Exception {
        final File barFile = new File(tmpDir, "bar.html");
        final String expectedContentA = "<html/>";
        final String expectedContentB = "<html><body/></html>";
        Files.write(barFile.toPath(), expectedContentA.getBytes(StandardCharsets.UTF_8));

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final String lastModified;
            final String etag;
            HttpUriRequest req = new HttpGet(newUri("/fs/bar.html"));
            try (CloseableHttpResponse res = hc.execute(req)) {
                assert200Ok(res, "text/html", expectedContentA);
                lastModified = header(res, HttpHeaders.LAST_MODIFIED);
                etag = header(res, HttpHeaders.ETAG);
            }

            assert304NotModified(hc, "/fs/bar.html", etag, lastModified);

            // Test if the 'If-Modified-Since' header works as expected after the file is modified.
            req = new HttpGet(newUri("/fs/bar.html"));
            final Instant now = Instant.now();
            req.setHeader(HttpHeaders.IF_MODIFIED_SINCE,
                          DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(now, UTC)));

            // HTTP-date has no sub-second precision; just add a few seconds to the time.
            Files.write(barFile.toPath(), expectedContentB.getBytes(StandardCharsets.UTF_8));
            assertThat(barFile.setLastModified(now.toEpochMilli() + 5000)).isTrue();

            final String newLastModified;
            final String newETag;
            try (CloseableHttpResponse res = hc.execute(req)) {
                assert200Ok(res, "text/html", expectedContentB);
                newLastModified = header(res, HttpHeaders.LAST_MODIFIED);
                newETag = header(res, HttpHeaders.ETAG);

                // Ensure that both 'Last-Modified' and 'ETag' changed.
                assertThat(newLastModified).isNotEqualTo(lastModified);
                assertThat(newETag).isNotEqualTo(etag);
            }

            // Test if the 'If-None-Match' header works as expected after the file is modified.
            req = new HttpGet(newUri("/fs/bar.html"));
            req.setHeader(HttpHeaders.IF_NONE_MATCH, etag);

            try (CloseableHttpResponse res = hc.execute(req)) {
                assert200Ok(res, "text/html", expectedContentB);

                // Ensure that both 'Last-Modified' and 'ETag' changed.
                assertThat(header(res, HttpHeaders.LAST_MODIFIED)).isEqualTo(newLastModified);
                assertThat(header(res, HttpHeaders.ETAG)).isEqualTo(newETag);
            }

            // Test if the cache detects the file removal correctly.
            final boolean deleted = barFile.delete();
            assertThat(deleted).isTrue();

            req = new HttpGet(newUri("/fs/bar.html"));
            req.setHeader(HttpHeaders.IF_MODIFIED_SINCE, currentHttpDate());
            req.setHeader(HttpHeaders.CONNECTION, "close");

            try (CloseableHttpResponse res = hc.execute(req)) {
                assert404NotFound(res);
            }
        }
    }

    @Test
    public void testFileSystemGet_modifiedFile() throws Exception {
        final File barFile = new File(tmpDir, "modifiedFile.html");
        final String expectedContentA = "<html/>";
        final String expectedContentB = "<html><body/></html>";
        Files.write(barFile.toPath(), expectedContentA.getBytes(StandardCharsets.UTF_8));
        final long barFileLastModified = barFile.lastModified();

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpUriRequest req = new HttpGet(newUri("/fs/modifiedFile.html"));
            try (CloseableHttpResponse res = hc.execute(req)) {
                assert200Ok(res, "text/html", expectedContentA);
            }

            // Modify the file cached by the service. Update last modification time explicitly
            // so that it differs from the old value.
            Files.write(barFile.toPath(), expectedContentB.getBytes(StandardCharsets.UTF_8));
            assertThat(barFile.setLastModified(barFileLastModified + 5000)).isTrue();

            try (CloseableHttpResponse res = hc.execute(req)) {
                assert200Ok(res, "text/html", expectedContentB);
            }
        }
    }

    @Test
    public void testFileSystemGet_newFile() throws Exception {
        final File barFile = new File(tmpDir, "newFile.html");
        final String expectedContentA = "<html/>";

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpUriRequest req = new HttpGet(newUri("/fs/newFile.html"));
            try (CloseableHttpResponse res = hc.execute(req)) {
                assert404NotFound(res);
            }
            Files.write(barFile.toPath(), expectedContentA.getBytes(StandardCharsets.UTF_8));
            try (CloseableHttpResponse res = hc.execute(req)) {
                assert200Ok(res, "text/html", expectedContentA);
            }
        } finally {
            barFile.delete();
        }
    }

    @Test
    public void testFileSystemGetUtf8() throws Exception {
        final File barFile = new File(tmpDir, "¢.txt");
        final String expectedContentA = "¢";
        Files.write(barFile.toPath(), expectedContentA.getBytes(StandardCharsets.UTF_8));

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpUriRequest req = new HttpGet(newUri("/fs/%C2%A2.txt"));
            try (CloseableHttpResponse res = hc.execute(req)) {
                assert200Ok(res, "text/plain", expectedContentA);
            }
        }
    }

    private static void assert200Ok(CloseableHttpResponse res,
                                    @Nullable String expectedContentType,
                                    String expectedContent) throws Exception {
        assert200Ok(res, expectedContentType, content -> assertThat(content).isEqualTo(expectedContent));
    }

    private static void assert200Ok(CloseableHttpResponse res,
                                    @Nullable String expectedContentType,
                                    Consumer<String> contentAssertions) throws Exception {

        assertStatusLine(res, "HTTP/1.1 200 OK");

        // Ensure that the 'Date' header exists and is well-formed.
        final String date = headerOrNull(res, HttpHeaders.DATE);
        assertThat(date).isNotNull();
        DateFormatter.parseHttpDate(date);

        // Ensure that the 'Last-Modified' header exists and is well-formed.
        final String lastModified = headerOrNull(res, HttpHeaders.LAST_MODIFIED);
        assertThat(lastModified).isNotNull();
        DateFormatter.parseHttpDate(lastModified);

        // Ensure that the 'ETag' header exists and is well-formed.
        final String entityTag = headerOrNull(res, HttpHeaders.ETAG);
        assertThat(entityTag).matches(ETAG_PATTERN);

        // Ensure the content type is correct.
        if (expectedContentType != null) {
            assertThat(headerOrNull(res, HttpHeaders.CONTENT_TYPE)).startsWith(expectedContentType);
        } else {
            assertThat(res.containsHeader(HttpHeaders.CONTENT_TYPE)).isFalse();
        }

        // Ensure the content satisfies the condition.
        contentAssertions.accept(EntityUtils.toString(res.getEntity()).trim());
    }

    private void assert304NotModified(CloseableHttpClient hc, String path,
                                      String expectedETag, String expectedLastModified) throws IOException {
        final String uri = newUri(path);

        // Test if the 'If-None-Match' header works as expected. (a single etag)
        final HttpUriRequest req1 = new HttpGet(uri);
        req1.setHeader(HttpHeaders.IF_NONE_MATCH, expectedETag);

        try (CloseableHttpResponse res = hc.execute(req1)) {
            assert304NotModified(res, expectedETag, expectedLastModified);
        }

        // Test if the 'If-None-Match' header works as expected. (multiple etags)
        final HttpUriRequest req2 = new HttpGet(uri);
        req2.setHeader(HttpHeaders.IF_NONE_MATCH, "\"an-etag-that-never-matches\", " + expectedETag);

        try (CloseableHttpResponse res = hc.execute(req2)) {
            assert304NotModified(res, expectedETag, expectedLastModified);
        }

        // Test if the 'If-None-Match' header works as expected. (an asterisk)
        final HttpUriRequest req3 = new HttpGet(uri);
        req3.setHeader(HttpHeaders.IF_NONE_MATCH, "*");

        try (CloseableHttpResponse res = hc.execute(req3)) {
            assert304NotModified(res, expectedETag, expectedLastModified);
        }

        // Test if the 'If-Modified-Since' header works as expected.
        final HttpUriRequest req4 = new HttpGet(uri);
        req4.setHeader(HttpHeaders.IF_MODIFIED_SINCE, currentHttpDate());

        try (CloseableHttpResponse res = hc.execute(req4)) {
            assert304NotModified(res, expectedETag, expectedLastModified);
        }

        // 'If-Modified-Since' should never be evaluated if 'If-None-Match' exists.
        final HttpUriRequest req5 = new HttpGet(uri);
        req5.setHeader(HttpHeaders.IF_NONE_MATCH, "\"an-etag-that-never-matches\"");
        req5.setHeader(HttpHeaders.IF_MODIFIED_SINCE, currentHttpDate());

        try (CloseableHttpResponse res = hc.execute(req5)) {
            // Should not receive '304 Not Modified' because the etag did not match.
            assertStatusLine(res, "HTTP/1.1 200 OK");
        }
    }

    private static void assert304NotModified(
            CloseableHttpResponse res, String expectedEtag, String expectedLastModified) {

        assertStatusLine(res, "HTTP/1.1 304 Not Modified");

        // Ensure that the 'ETag' header did not change.
        assertThat(headerOrNull(res, HttpHeaders.ETAG)).isEqualTo(expectedEtag);

        // Ensure that the 'Last-Modified' header did not change.
        assertThat(headerOrNull(res, HttpHeaders.LAST_MODIFIED)).isEqualTo(expectedLastModified);

        // Ensure that the 'Content-Length' header does not exist.
        assertThat(res.containsHeader(HttpHeaders.CONTENT_LENGTH)).isFalse();

        // Ensure that the content does not exist.
        assertThat(res.getEntity()).isNull();
    }

    private static void assert404NotFound(CloseableHttpResponse res) {
        assertStatusLine(res, "HTTP/1.1 404 Not Found");
        // Ensure that the 'Last-Modified' header does not exist.
        assertThat(res.getFirstHeader(HttpHeaders.LAST_MODIFIED)).isNull();
    }

    private static void assertStatusLine(CloseableHttpResponse res, String expectedStatusLine) {
        assertThat(res.getStatusLine().toString()).isEqualTo(expectedStatusLine);
    }

    private static String header(CloseableHttpResponse res, String name) {
        final String value = headerOrNull(res, name);
        assertThat(value).withFailMessage("The response must contains the header '%s'.", name).isNotNull();
        return value;
    }

    @Nullable
    private static String headerOrNull(CloseableHttpResponse res, String name) {
        if (!res.containsHeader(name)) {
            return null;
        }
        return res.getFirstHeader(name).getValue();
    }

    private static String currentHttpDate() {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(UTC));
    }

    private String newUri(String path) {
        return "http://127.0.0.1:" + httpPort + '/' + (cached ? "cached" : "uncached") + path;
    }
}
