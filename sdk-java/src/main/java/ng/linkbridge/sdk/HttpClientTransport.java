package ng.linkbridge.sdk;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Default {@link Transport} on {@code java.net.http.HttpClient}.
 *
 * <p>Defaults mirror the sibling SDKs' de-facto contract: 30s total request
 * timeout, 10s connect timeout, redirects never followed, TLS verification on
 * (the JDK default).
 */
public final class HttpClientTransport implements Transport {

  private final HttpClient client;
  private final Duration requestTimeout;

  public HttpClientTransport() {
    this(Duration.ofSeconds(30), Duration.ofSeconds(10));
  }

  public HttpClientTransport(Duration requestTimeout, Duration connectTimeout) {
    this.requestTimeout = requestTimeout;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public Response send(Request request) throws IOException, InterruptedException {
    HttpRequest.Builder b = HttpRequest.newBuilder(request.uri()).timeout(requestTimeout);
    request.headers().forEach(b::header);
    b.method(
        request.method(),
        request.body() == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(request.body()));

    HttpResponse<byte[]> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());

    Map<String, String> headers = new HashMap<>();
    resp.headers().map().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(", ", v)));
    byte[] body = resp.body() == null ? new byte[0] : resp.body();
    return new Response(resp.statusCode(), headers, body);
  }
}
