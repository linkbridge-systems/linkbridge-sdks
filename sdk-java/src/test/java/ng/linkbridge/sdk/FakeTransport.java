package ng.linkbridge.sdk;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Hermetic transport for tests: records every request, auto-answers the token
 * endpoint (counting calls, like the Node suite's tokenCalls), and pops queued
 * responses for everything else.
 */
final class FakeTransport implements Transport {

  record Recorded(String method, URI uri, Map<String, String> headers, byte[] body) {
    String bodyText() {
      return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }
  }

  final List<Recorded> requests = new ArrayList<>();
  final Deque<Response> queue = new ArrayDeque<>();
  int tokenCalls = 0;
  String tokenResponse =
      "{\"access_token\":\"tok-1\",\"token_type\":\"bearer\",\"expires_in\":3600}";
  int tokenStatus = 200;

  static Response json(int status, String body) {
    return new Response(
        status, Map.of("content-type", "application/json"), body.getBytes(StandardCharsets.UTF_8));
  }

  void enqueue(int status, String body) {
    queue.add(json(status, body));
  }

  @Override
  public synchronized Response send(Request request) { // synchronized: the concurrency test hits this from many threads
    requests.add(new Recorded(request.method(), request.uri(), request.headers(), request.body()));
    if (request.uri().getPath().equals("/v1/oauth/token")) {
      tokenCalls++;
      return json(tokenStatus, tokenResponse);
    }
    if (queue.isEmpty()) {
      return json(200, "{}");
    }
    return queue.pop();
  }

  Recorded last() {
    return requests.get(requests.size() - 1);
  }

  /** The most recent non-token request. */
  Recorded lastApi() {
    for (int i = requests.size() - 1; i >= 0; i--) {
      if (!requests.get(i).uri().getPath().equals("/v1/oauth/token")) {
        return requests.get(i);
      }
    }
    throw new AssertionError("no API request recorded");
  }
}
