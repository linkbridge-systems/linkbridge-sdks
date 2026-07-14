package ng.linkbridge.sdk;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * Pluggable HTTP transport, mirroring the injectable transport/fetch seam in
 * the sibling SDKs so tests can run hermetically.
 *
 * <p><b>Contract:</b> implementations must NOT throw on 4xx/5xx responses —
 * they return the status and body so the SDK can decode the canonical error
 * envelope. Throw only for genuine I/O failures. Implementations must not
 * follow redirects.
 */
@FunctionalInterface
public interface Transport {

  Response send(Request request) throws IOException, InterruptedException;

  /**
   * @param method HTTP method, upper case
   * @param uri fully-resolved request URI (base URL + path + query)
   * @param headers request headers, lower-case names
   * @param body request body bytes, or {@code null} when the request has none
   */
  record Request(String method, URI uri, Map<String, String> headers, byte[] body) {}

  /**
   * @param status HTTP status code
   * @param headers response headers, lower-case names
   * @param body raw response body bytes, never {@code null} (empty array for no body)
   */
  record Response(int status, Map<String, String> headers, byte[] body) {}
}
