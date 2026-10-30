package linkbridge

import (
	"encoding/json"
	"io"
	"net/http"
)

// readAll drains and closes the response body. Errors are best-effort
// because the only caller is the error path (we already know the
// status code is non-2xx).
func readAll(resp *http.Response) ([]byte, error) {
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}

// decodeJSON streams the response body into dst. The caller is
// responsible for closing the body (typically via defer).
func decodeJSON(resp *http.Response, dst any) error {
	return json.NewDecoder(resp.Body).Decode(dst)
}
