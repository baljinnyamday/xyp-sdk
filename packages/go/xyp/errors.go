package xyp

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strconv"
)

// Origin says whose side a problem is on, so logs and dashboards can route it.
type Origin string

const (
	// OriginConfig is how the client was set up (bad key, unknown operation, ...).
	OriginConfig Origin = "config"
	// OriginNetwork is the VPN, the hosts entry, DNS, TLS or a timeout.
	OriginNetwork Origin = "network"
	// OriginXYP is XYP or the data provider answering with an error.
	OriginXYP Origin = "xyp"
	// OriginSDK is a gap in this SDK. Please report it.
	OriginSDK Origin = "sdk"
)

// hasOrigin is implemented by every error this package returns.
type hasOrigin interface{ Origin() Origin }

// OriginOf reports whose side err is on. Anything that is not an SDK error is
// OriginSDK: it reached the caller through this SDK, so this SDK owns it.
func OriginOf(err error) Origin {
	var origin hasOrigin
	if errors.As(err, &origin) {
		return origin.Origin()
	}
	return OriginSDK
}

// ConfigError means the client, the call or the caller's response struct was set
// up incorrectly. Retrying will not help; the program has to change.
type ConfigError struct{ Message string }

func (e *ConfigError) Error() string { return e.Message }

// Origin is always OriginConfig.
func (e *ConfigError) Origin() Origin { return OriginConfig }

// ErrTimeout matches any ConnectionError caused by a deadline:
// errors.Is(err, xyp.ErrTimeout).
var ErrTimeout = errors.New("xyp: request timed out")

// ConnectionError means XYP could not be reached at all. Err keeps the cause, so
// errors.Is(err, context.Canceled) and the net package's own errors still work.
type ConnectionError struct {
	Message string
	Err     error
}

func (e *ConnectionError) Error() string { return e.Message }

// Unwrap exposes the cause, so errors.Is reaches context.Canceled and the net
// package's own errors.
func (e *ConnectionError) Unwrap() error { return e.Err }

// Origin is always OriginNetwork.
func (e *ConnectionError) Origin() Origin { return OriginNetwork }

// Timeout reports whether the request ran out of time, which makes it worth
// retrying, unlike a refused connection.
func (e *ConnectionError) Timeout() bool {
	if errors.Is(e.Err, context.DeadlineExceeded) {
		return true
	}
	var netErr net.Error
	return errors.As(e.Err, &netErr) && netErr.Timeout()
}

// Is makes errors.Is(err, ErrTimeout) work without exposing a second error type.
func (e *ConnectionError) Is(target error) bool { return target == ErrTimeout && e.Timeout() }

// ResponseError means XYP answered with something that is not a valid service
// response: a SOAP fault, a gateway error page, or XML the SDK cannot read.
type ResponseError struct {
	Message string
	// StatusCode is the HTTP status, or 0 when the failure was not tied to one.
	StatusCode int
}

func (e *ResponseError) Error() string { return e.Message }

// Origin is always OriginXYP.
func (e *ResponseError) Origin() Origin { return OriginXYP }

// APIError means XYP processed the request and answered with a non-zero
// resultCode. Codes: https://developer.xyp.gov.mn/docs/result-code
//
// Compare it with the Err* sentinels through errors.Is, and read the details
// through errors.As:
//
//	if errors.Is(err, xyp.ErrNotFound) { return nil, nil }
//	var apiErr *xyp.APIError
//	if errors.As(err, &apiErr) { log(apiErr.ResultCode, apiErr.RequestID) }
type APIError struct {
	ResultCode    int
	ResultMessage string
	// RequestID is what XYP support will ask you for.
	RequestID string
}

func (e *APIError) Error() string {
	return "[" + strconv.Itoa(e.ResultCode) + "] " + e.ResultMessage
}

// Origin is always OriginXYP.
func (e *APIError) Origin() Origin { return OriginXYP }

// Is maps the result code to its sentinel. Codes the list below does not cover
// match no sentinel, so they surface as a plain *APIError.
func (e *APIError) Is(target error) bool {
	sentinel, known := sentinelByCode[e.ResultCode]
	return known && sentinel == target
}

// The documented result codes, grouped the way XYP groups them.
var (
	// ErrNotFound is code 1: the data provider has no record for this request.
	ErrNotFound = errors.New("xyp: not found")
	// ErrInternal is code 2: XYP internal error.
	ErrInternal = errors.New("xyp: internal error")
	// ErrInvalidRequest is code 3: missing input, wrong endpoint, or a bad
	// accessToken/timeStamp/signature header.
	ErrInvalidRequest = errors.New("xyp: invalid request")
	// ErrAuthRequired is codes 200-202: the auth block (citizen and/or operator) is missing.
	ErrAuthRequired = errors.New("xyp: citizen or operator approval required")
	// ErrAccessDenied is codes 203 and 501: your access token may not call this service.
	ErrAccessDenied = errors.New("xyp: access denied")
	// ErrFingerprint is codes 301-304: fingerprint not registered, not matched, or matching failed.
	ErrFingerprint = errors.New("xyp: fingerprint rejected")
	// ErrCitizenData is codes 401-402: the citizen must visit the registry, or is not the owner.
	ErrCitizenData = errors.New("xyp: citizen data problem")
	// ErrSignature is codes 601-605: the citizen's digital signature or certificate was rejected.
	ErrSignature = errors.New("xyp: digital signature rejected")
	// ErrProvider is codes 801-802: the data provider's database is unreachable or timed out.
	ErrProvider = errors.New("xyp: data provider unavailable")
)

// resultCodeOK is the only result code that is not an error.
const resultCodeOK = 0

var sentinelByCode = map[int]error{
	1:   ErrNotFound,
	2:   ErrInternal,
	3:   ErrInvalidRequest,
	200: ErrAuthRequired,
	201: ErrAuthRequired,
	202: ErrAuthRequired,
	203: ErrAccessDenied,
	301: ErrFingerprint,
	302: ErrFingerprint,
	303: ErrFingerprint,
	304: ErrFingerprint,
	401: ErrCitizenData,
	402: ErrCitizenData,
	501: ErrAccessDenied,
	601: ErrSignature,
	602: ErrSignature,
	603: ErrSignature,
	604: ErrSignature,
	605: ErrSignature,
	801: ErrProvider,
	802: ErrProvider,
}

func configErrorf(format string, args ...any) *ConfigError {
	return &ConfigError{Message: fmt.Sprintf(format, args...)}
}

func responseErrorf(status int, format string, args ...any) *ResponseError {
	return &ResponseError{Message: fmt.Sprintf(format, args...), StatusCode: status}
}
