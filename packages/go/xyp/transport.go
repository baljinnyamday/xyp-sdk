package xyp

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"syscall"
)

const (
	soapContentType = "text/xml; charset=utf-8"
	// httpErrorStatus and above is where a reply stops being a service response.
	httpErrorStatus = 400
)

// endpointName is one URL path segment such as "citizen-1.5.0". WithEndpoint takes
// it from the caller, and it must not be able to reach another path or host.
var endpointName = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]*$`)

// targetNamespace reads the namespace of an endpoint whose WSDL is not checked
// into spec/wsdl/.
var targetNamespace = regexp.MustCompile(`targetNamespace="([^"]+)"`)

type httpReply struct {
	status int
	body   []byte
}

func (c *Client) send(ctx context.Context, operation string, params any, options callOptions) (any, error) {
	endpoint := options.endpoint
	if endpoint == "" {
		known, found := operationEndpoints[operation]
		if !found {
			return nil, configErrorf(
				"unknown operation %q. Pass xyp.WithEndpoint(\"<name>-<version>\") to call a "+
					"service this SDK version does not know about.", operation,
			)
		}
		endpoint = known
	}
	if !endpointName.MatchString(endpoint) {
		return nil, configErrorf(
			"endpoint %q is not a name like \"citizen-1.5.0\"", endpoint,
		)
	}
	address := c.baseURL + "/" + endpoint + "/ws"
	namespace, err := c.namespaceOf(ctx, endpoint, address)
	if err != nil {
		return nil, err
	}
	envelope, err := buildEnvelope(operation, namespace, params, options.citizen, options.operator)
	if err != nil {
		return nil, err
	}
	reply, err := c.do(ctx, http.MethodPost, address, envelope)
	if err != nil {
		return nil, err
	}
	return unwrap(reply)
}

// namespaceOf returns the endpoint's XML namespace, reading it from the live
// WSDL the first time an endpoint outside the generated registry is used.
func (c *Client) namespaceOf(ctx context.Context, endpoint, address string) (string, error) {
	c.namespacesMu.Lock()
	cached, found := c.namespaces[endpoint]
	c.namespacesMu.Unlock()
	if found {
		return cached, nil
	}
	// The WSDL is fetched without the lock: two callers racing here cost one
	// extra GET, holding the lock across a network call would cost every caller.
	reply, err := c.do(ctx, http.MethodGet, address+"?WSDL", "")
	if err != nil {
		return "", err
	}
	match := targetNamespace.FindSubmatch(reply.body)
	if reply.status >= httpErrorStatus || match == nil {
		return "", responseErrorf(
			reply.status, "could not read the WSDL of endpoint %q", endpoint,
		)
	}
	namespace := string(match[1])
	c.namespacesMu.Lock()
	c.namespaces[endpoint] = namespace
	c.namespacesMu.Unlock()
	return namespace, nil
}

func (c *Client) do(ctx context.Context, method, address, body string) (httpReply, error) {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()

	request, err := http.NewRequestWithContext(ctx, method, address, bodyReader(body))
	if err != nil {
		return httpReply{}, configErrorf("cannot build a request for %q", address)
	}
	if method == http.MethodPost {
		request.Header.Set("Content-Type", soapContentType)
		request.Header.Set("SOAPAction", `""`)
		if signErr := c.signHeaders(request.Header); signErr != nil {
			return httpReply{}, signErr
		}
	}
	response, err := c.http.Do(request)
	if err != nil {
		return httpReply{}, connectionError(err)
	}
	defer func() { _ = response.Body.Close() }()

	payload, err := io.ReadAll(response.Body)
	if err != nil {
		return httpReply{}, connectionError(err)
	}
	return httpReply{status: response.StatusCode, body: payload}, nil
}

func bodyReader(body string) io.Reader {
	if body == "" {
		return nil
	}
	return strings.NewReader(body)
}

func connectionError(err error) *ConnectionError {
	return &ConnectionError{
		Message: "could not reach XYP (" + failureCause(err) + "). Check the VPN connection, " +
			"the hosts entry for xyp.gov.mn and the TLS settings.",
		Err: err,
	}
}

// failureCause is the short reason that goes in the message, in the spirit of
// the errno code the TypeScript SDK prints.
func failureCause(err error) string {
	var netErr net.Error
	switch {
	case errors.Is(err, context.DeadlineExceeded),
		errors.As(err, &netErr) && netErr.Timeout():
		return "timeout"
	case errors.Is(err, context.Canceled):
		return "canceled"
	}
	var errno syscall.Errno
	if errors.As(err, &errno) {
		return errno.Error()
	}
	var urlErr *url.Error
	if errors.As(err, &urlErr) {
		return urlErr.Err.Error()
	}
	return err.Error()
}

func unwrap(reply httpReply) (any, error) {
	result, err := parseResponse(reply.body)
	if err != nil {
		var responseErr *ResponseError
		if reply.status >= httpErrorStatus && errors.As(err, &responseErr) {
			// JAX-WS sends SOAP faults with HTTP 500: keep the fault text, it is the only
			// diagnostic the caller gets, and add the status to it.
			return nil, responseErrorf(
				reply.status, "XYP answered with HTTP %d: %s", reply.status, responseErr.Message,
			)
		}
		return nil, err
	}
	if result.resultCode != resultCodeOK {
		return nil, &APIError{
			ResultCode:    result.resultCode,
			ResultMessage: result.message,
			RequestID:     result.requestID,
		}
	}
	return result.data, nil
}
