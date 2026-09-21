// The lenient readers behind one response field. The accepted spellings are the
// ones the Python and TypeScript SDKs accept, so every SDK reads a response the
// same way.

package xyp

import (
	"encoding/base64"
	"reflect"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var (
	// "34.0" is an integer written by a spreadsheet.
	integerPattern = regexp.MustCompile(`^[+-]?\d+(\.0+)?$`)
	floatPattern   = regexp.MustCompile(`^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$`)
	// Strict on purpose: a lenient decoder turns "N/A" into garbage bytes.
	base64Pattern = regexp.MustCompile(`^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$`)
	// Must start with a full date: a bare "2020" is a year, not a timestamp.
	isoDatePattern = regexp.MustCompile(
		`^\d{4}-\d{2}-\d{2}([T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?)?$`)
	offsetWithoutColon = regexp.MustCompile(`([+-]\d{2})(\d{2})$`)
	whitespace         = regexp.MustCompile(`\s`)
)

var (
	trueText  = map[string]bool{"true": true, "1": true, "t": true, "yes": true, "y": true, "on": true}
	falseText = map[string]bool{"false": true, "0": true, "f": true, "no": true, "n": true, "off": true}
)

// Layouts for the shapes isoDatePattern allows, after normalizeISO. A layout
// without a zone parses as UTC, which is what an XYP timestamp without one means.
var dateLayouts = []string{
	"2006-01-02",
	"2006-01-02T15:04",
	"2006-01-02T15:04Z07:00",
	"2006-01-02T15:04:05",
	time.RFC3339,
}

func (d *decoder) bytes(node any, text, path string) (reflect.Value, bool) {
	compact := whitespace.ReplaceAllString(text, "")
	if !base64Pattern.MatchString(compact) {
		return d.reject(kindBytes, node, path)
	}
	data, err := base64.StdEncoding.DecodeString(compact)
	if err != nil {
		return d.reject(kindBytes, node, path)
	}
	return reflect.ValueOf(data), true
}

func (d *decoder) integer(typ reflect.Type, node any, text, path string) (reflect.Value, bool) {
	if !integerPattern.MatchString(text) {
		return d.reject(kindInt, node, path)
	}
	digits, _, _ := strings.Cut(text, ".") // the pattern only allows ".000" here
	number, err := strconv.ParseInt(digits, 10, 64)
	if err != nil {
		return d.reject(kindInt, node, path) // out of int64 range
	}
	pointer := reflect.New(typ.Elem())
	pointer.Elem().SetInt(number)
	return pointer, true
}

func (d *decoder) float(typ reflect.Type, node any, text, path string) (reflect.Value, bool) {
	if !floatPattern.MatchString(text) {
		return d.reject(kindFloat, node, path)
	}
	number, err := strconv.ParseFloat(text, 64)
	if err != nil {
		return d.reject(kindFloat, node, path)
	}
	pointer := reflect.New(typ.Elem())
	pointer.Elem().SetFloat(number)
	return pointer, true
}

func (d *decoder) boolean(typ reflect.Type, node any, text, path string) (reflect.Value, bool) {
	lower := strings.ToLower(text)
	if !trueText[lower] && !falseText[lower] {
		return d.reject(kindBool, node, path)
	}
	pointer := reflect.New(typ.Elem())
	pointer.Elem().SetBool(trueText[lower])
	return pointer, true
}

// parseDate never rejects: providers fill date fields by hand and the formats
// are not documented, so anything unreadable is kept as text.
func parseDate(text string) Date {
	date := Date{Raw: text}
	if !isoDatePattern.MatchString(text) {
		return date
	}
	normalized := normalizeISO(text)
	for _, layout := range dateLayouts {
		parsed, err := time.Parse(layout, normalized)
		if err == nil {
			date.Time = parsed
			return date
		}
	}
	return date
}

// normalizeISO brings the spellings isoDatePattern allows down to the layouts Go
// understands: a space separator becomes "T", and a "+0800" offset gains its colon.
func normalizeISO(text string) string {
	normalized := strings.Replace(text, " ", "T", 1)
	return offsetWithoutColon.ReplaceAllString(normalized, "$1:$2")
}
