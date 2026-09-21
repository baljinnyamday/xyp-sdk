package xyp

import (
	"context"
	"crypto"
	"fmt"
	"log/slog"
	"maps"
	"net/http"
	"sync"
	"time"
)

// Client talks to XYP. It is safe for concurrent use and keeps connections
// alive, so build one and share it; call Close when the program is done with it.
//
//	client, err := xyp.NewClient(xyp.Options{})
//	defer client.Close()
//	card, err := citizen.New(client).GetCitizenIDCardInfo(ctx, params)
type Client struct {
	accessToken string
	key         crypto.Signer
	baseURL     string
	timeout     time.Duration
	logger      *slog.Logger
	http        *http.Client
	// now is the clock the request timestamp comes from; tests pin it.
	now func() time.Time

	// namespacesMu guards namespaces, which grows as endpoints outside the
	// generated registry have their WSDL read.
	namespacesMu sync.Mutex
	namespaces   map[string]string
}

// NewClient validates the options, loads the credentials and returns a ready
// client. It performs no I/O against XYP.
func NewClient(options Options) (*Client, error) {
	resolved, err := options.resolve()
	if err != nil {
		return nil, err
	}
	return &Client{
		accessToken: resolved.accessToken,
		key:         resolved.key,
		baseURL:     resolved.baseURL,
		timeout:     resolved.timeout,
		logger:      resolved.logger,
		http: &http.Client{
			Transport: newTransport(resolved.roots, resolved.insecure),
		},
		now: defaultClock,
		// Copied, so the generated registry stays immutable and shared.
		namespaces: maps.Clone(knownNamespaces),
	}, nil
}

// callOptions is what the CallOption functions configure.
type callOptions struct {
	citizen  *Auth
	operator *Auth
	endpoint string
}

// CallOption tunes one call. The generated methods pass these straight through.
type CallOption func(*callOptions)

// WithAuth attaches the approval of the citizen whose data is requested.
func WithAuth(citizen Auth) CallOption {
	return func(options *callOptions) { options.citizen = &citizen }
}

// WithOperator attaches the approval of the staff member making the request.
func WithOperator(operator Auth) CallOption {
	return func(options *callOptions) { options.operator = &operator }
}

// WithEndpoint names the endpoint to call, e.g. "citizen-1.5.0", for a service
// that is newer than this SDK version.
func WithEndpoint(endpoint string) CallOption {
	return func(options *callOptions) { options.endpoint = endpoint }
}

func newCallOptions(options []CallOption) callOptions {
	var resolved callOptions
	for _, option := range options {
		option(&resolved)
	}
	return resolved
}

// Call runs a service by its original XYP name and returns the response tree as
// nested map[string]any, []any, string and nil.
//
//	data, err := client.Call(ctx, "WS100101_getCitizenIDCardInfo",
//	    xyp.Params{{Name: "regnum", Value: "РД00000000"}})
//
// See the package documentation for the shapes params may take.
func (c *Client) Call(ctx context.Context, operation string, params any, opts ...CallOption) (any, error) {
	return c.send(ctx, operation, params, newCallOptions(opts))
}

// Invoke runs a service and decodes the response into out, a pointer to a
// struct whose fields carry `xyp:"wireName"` tags.
//
// A field the response does not fit is left at its zero value and recorded in
// the returned Extras; the call itself still succeeds, because the SDK's models
// come from a hand-typed catalog and real data sometimes disagrees with them.
// Extras.Raw holds the whole response, including fields out does not declare.
func (c *Client) Invoke(ctx context.Context, operation string, params, out any, opts ...CallOption) (Extras, error) {
	if err := checkOut(out); err != nil {
		return Extras{}, err
	}
	data, err := c.send(ctx, operation, params, newCallOptions(opts))
	if err != nil {
		return Extras{}, err
	}
	mismatches, err := decodeInto(out, data)
	if err != nil {
		return Extras{}, err
	}
	if len(mismatches) > 0 {
		c.warnMismatches(operation, mismatches)
	}
	return Extras{Mismatches: mismatches, Raw: data}, nil
}

// Close releases the kept-alive connections. The client stays usable; it just
// has to open a new connection.
func (c *Client) Close() { c.http.CloseIdleConnections() }

// String keeps the access token and the private key out of logs: fmt prints
// unexported fields with %+v unless the type says otherwise.
func (c *Client) String() string {
	return fmt.Sprintf("xyp.Client{BaseURL: %q}", c.baseURL)
}

// GoString does the same for %#v.
func (c *Client) GoString() string {
	return fmt.Sprintf("&xyp.Client{BaseURL: %q}", c.baseURL)
}
