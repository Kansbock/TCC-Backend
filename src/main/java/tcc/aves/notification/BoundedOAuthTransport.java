package tcc.aves.notification;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BoundedOAuthTransport extends HttpTransport {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Override
    protected LowLevelHttpRequest buildRequest(String method, String url) {
        return new LowLevelHttpRequest() {
            private final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3));
            @Override public void addHeader(String name, String value) { request.header(name, value); }
            @Override public LowLevelHttpResponse execute() throws IOException {
                var body = new ByteArrayOutputStream();
                if (getStreamingContent() != null) getStreamingContent().writeTo(body);
                if (getContentType() != null) request.header("Content-Type", getContentType());
                try {
                    var response = client.send(request.method(method,
                            HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    return new Response(response);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("OAuth request interrupted");
                }
            }
        };
    }

    private static final class Response extends LowLevelHttpResponse {
        private final HttpResponse<byte[]> response;
        private final List<Map.Entry<String, String>> headers = new ArrayList<>();
        Response(HttpResponse<byte[]> response) {
            this.response = response;
            response.headers().map().forEach((name, values) ->
                    values.forEach(value -> headers.add(Map.entry(name, value))));
        }
        @Override public InputStream getContent() { return new ByteArrayInputStream(response.body()); }
        @Override public String getContentEncoding() { return response.headers().firstValue("Content-Encoding").orElse(null); }
        @Override public long getContentLength() { return response.body().length; }
        @Override public String getContentType() { return response.headers().firstValue("Content-Type").orElse(null); }
        @Override public String getStatusLine() { return "HTTP/1.1 " + response.statusCode(); }
        @Override public int getStatusCode() { return response.statusCode(); }
        @Override public String getReasonPhrase() { return ""; }
        @Override public int getHeaderCount() { return headers.size(); }
        @Override public String getHeaderName(int index) { return headers.get(index).getKey(); }
        @Override public String getHeaderValue(int index) { return headers.get(index).getValue(); }
    }
}
