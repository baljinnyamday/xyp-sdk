package xyp

import (
	"fmt"
	"time"
)

// Date is a date field as XYP sent it. Providers fill these by hand and the
// formats are not documented, so nothing is ever rejected: Raw always holds the
// original text, and Time is set only when Raw is ISO 8601. A value without a
// zone is read as UTC.
//
//	if !d.Time.IsZero() { use(d.Time) } else { use(d.Raw) }
type Date struct {
	Raw  string
	Time time.Time
}

// IsZero reports whether the field was absent (or empty) in the response.
func (d Date) IsZero() bool { return d.Raw == "" && d.Time.IsZero() }

// Decimal is a BigDecimal kept as the text XYP sent, because float64 cannot hold
// every value XYP writes into one. Parse it with the precision your use needs.
type Decimal string

// Mismatch is one response field that did not fit the SDK's model.
type Mismatch struct {
	// Path is e.g. "listData[1].year"; "" is the response itself.
	Path string
	// Problem says what did not fit, e.g. "not a valid int".
	Problem string
	// value is unexported and printed by nothing: it is citizen data and stays
	// out of logs unless the caller asks for it.
	value any
}

// Value is the raw value XYP sent for this field.
func (m Mismatch) Value() any { return m.value }

// String prints the path and the problem, never the value: fmt would otherwise
// print the unexported field with %+v and put citizen data in a log line.
func (m Mismatch) String() string {
	path := m.Path
	if path == "" {
		path = "<response>"
	}
	return path + " (" + m.Problem + ")"
}

// GoString keeps %#v free of the raw value too.
func (m Mismatch) GoString() string {
	return fmt.Sprintf("xyp.Mismatch{Path: %q, Problem: %q}", m.Path, m.Problem)
}

// Extras is what a typed response carries besides its declared fields.
type Extras struct {
	// Mismatches is normally empty. Each entry is a field of this response that
	// did not fit the SDK's model; that field kept its zero value and the call
	// still succeeded.
	Mismatches []Mismatch
	// Raw is the whole parsed response: nested map[string]any, []any, string and
	// nil. Reach fields the generated model does not declare through it.
	Raw any
}

// Ptr returns a pointer to v, for the optional numbers and booleans of a request:
//
//	params := insurance.GetCitizenPensionInquiryParams{StartYear: xyp.Ptr(int64(2020))}
func Ptr[T any](v T) *T { return &v }

// Param is one raw-call parameter. Value follows the encoding rules in the
// package documentation.
type Param struct {
	Name  string
	Value any
}

// Params is an ordered list of raw-call parameters. Use it instead of a map when
// the service cares about field order, which XYP's schemas do.
type Params []Param
