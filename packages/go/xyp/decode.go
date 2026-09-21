// Soft validation of responses.
//
// Response shapes are generated from XYP's public catalog, which is typed by
// hand, so real data can disagree with them. A successful call must never be
// lost to that: a value that does not fit leaves its field at the zero value,
// its raw value is kept in Extras.Mismatches, and a warning says which field it
// was and that the SDK's model (not XYP, not the caller) is wrong.

package xyp

import (
	"fmt"
	"log/slog"
	"reflect"
	"strings"
)

// IssuesURL is where a model mismatch should be reported.
const IssuesURL = "https://github.com/baljinnyamday/xyp-sdk/issues"

const mismatchWarning = "XYP's response does not fit the SDK's model. The call succeeded and " +
	"nothing was lost: the listed fields kept their zero value and their raw values are in the " +
	"returned Extras.Mismatches. This is a gap in the SDK's models, generated from XYP's public " +
	"catalog — not an error from XYP or in your code. Please report it at " + IssuesURL

// fieldKind is how the decoder fills one Go type.
type fieldKind int

const (
	kindAny fieldKind = iota
	kindString
	kindDecimal
	kindBytes
	kindDate
	kindInt
	kindFloat
	kindBool
	kindObject
	kindList
)

// kindNames are the names that appear in a mismatch's problem text.
var kindNames = [...]string{
	kindAny: "any", kindString: "string", kindDecimal: "decimal", kindBytes: "bytes",
	kindDate: "date", kindInt: "int", kindFloat: "float", kindBool: "bool",
	kindObject: "object", kindList: "list",
}

var (
	anyType     = reflect.TypeOf((*any)(nil)).Elem()
	dateType    = reflect.TypeOf(Date{})
	decimalType = reflect.TypeOf(Decimal(""))
	bytesType   = reflect.TypeOf([]byte(nil))
)

// classify says how a Go type is filled, and false when the decoder cannot fill
// it at all.
func classify(typ reflect.Type) (fieldKind, bool) {
	switch typ {
	case anyType:
		return kindAny, true
	case dateType:
		return kindDate, true
	case decimalType:
		return kindDecimal, true
	case bytesType:
		return kindBytes, true
	}
	switch typ.Kind() {
	case reflect.String:
		return kindString, true
	case reflect.Slice:
		return kindList, true
	// A nested object is a pointer, so nil can mean "absent"; inside a list it is
	// a value, because a list has no absent items.
	case reflect.Struct:
		return kindObject, true
	case reflect.Pointer:
		// 0 and false are real answers from XYP, so these are pointers too. A
		// string is not: XML cannot tell an empty string from a missing element.
		switch typ.Elem().Kind() {
		case reflect.Int64:
			return kindInt, true
		case reflect.Float64:
			return kindFloat, true
		case reflect.Bool:
			return kindBool, true
		case reflect.Struct:
			return kindObject, true
		}
	}
	return 0, false
}

// structTypeOf is the struct behind a kindObject type, pointer or value.
func structTypeOf(typ reflect.Type) reflect.Type {
	if typ.Kind() == reflect.Pointer {
		return typ.Elem()
	}
	return typ
}

// checkType rejects a field the decoder cannot fill. That is a mistake in the
// caller's struct, not in XYP's answer, so it is an error rather than a mismatch.
func checkType(typ reflect.Type, seen map[reflect.Type]bool) error {
	kind, supported := classify(typ)
	if !supported {
		return configErrorf(
			"%s is not a response field type this SDK can decode; use string, *int64, "+
				"*float64, *bool, xyp.Decimal, []byte, xyp.Date, any, a *struct or a slice", typ,
		)
	}
	switch kind {
	case kindList:
		return checkType(typ.Elem(), seen)
	case kindObject:
		nested := structTypeOf(typ)
		if len(wireFields(nested)) == 0 {
			// Almost always a time.Time or another struct from outside the SDK;
			// decoding it would silently produce an empty value.
			return configErrorf("%s has no exported fields the SDK could fill", nested)
		}
		return checkStruct(nested, seen)
	default:
		return nil
	}
}

func checkStruct(typ reflect.Type, seen map[reflect.Type]bool) error {
	if seen[typ] {
		return nil
	}
	seen[typ] = true
	for _, field := range wireFields(typ) {
		if err := checkType(typ.Field(field.index).Type, seen); err != nil {
			return err
		}
	}
	return nil
}

// checkOut rejects a response struct the decoder cannot fill. Invoke calls it
// before it sends anything: a struct the SDK cannot fill is a mistake in the
// caller's code, and there is no reason to ask XYP for data first.
func checkOut(out any) error {
	pointer := reflect.ValueOf(out)
	if pointer.Kind() != reflect.Pointer || pointer.IsNil() ||
		pointer.Elem().Kind() != reflect.Struct {
		return configErrorf("out must be a non-nil pointer to a struct, got %T", out)
	}
	return checkStruct(pointer.Elem().Type(), map[reflect.Type]bool{})
}

// decodeInto fills out from the parsed response and returns the fields that did
// not fit. It fails only when out itself is wrong.
func decodeInto(out any, tree any) ([]Mismatch, error) {
	if err := checkOut(out); err != nil {
		return nil, err
	}
	target := reflect.ValueOf(out).Elem()
	decoder := &decoder{}
	fields, isObject := tree.(map[string]any)
	if !isObject && tree != nil {
		decoder.report("", "expected an object", tree)
	}
	decoder.fill(target, fields, "")
	return decoder.mismatches, nil
}

type decoder struct{ mismatches []Mismatch }

func (d *decoder) report(path, problem string, value any) {
	d.mismatches = append(d.mismatches, Mismatch{Path: path, Problem: problem, value: value})
}

func (d *decoder) reject(kind fieldKind, node any, path string) (reflect.Value, bool) {
	d.report(path, "not a valid "+kindNames[kind], node)
	return reflect.Value{}, false
}

// fill decodes every declared field of a struct. A field that does not fit keeps
// its zero value; unknown fields XYP sent stay reachable through Extras.Raw.
func (d *decoder) fill(target reflect.Value, fields map[string]any, path string) {
	for _, field := range wireFields(target.Type()) {
		child := target.Field(field.index)
		value, fits := d.value(child.Type(), fields[field.name], childPath(path, field.name))
		if fits {
			child.Set(value)
		}
	}
}

// value decodes node for typ. fits is false when the node did not fit, in which
// case the mismatch has already been reported.
func (d *decoder) value(typ reflect.Type, node any, path string) (reflect.Value, bool) {
	kind, _ := classify(typ) // already validated by checkType
	if node == nil {
		return reflect.Zero(typ), true
	}
	switch kind {
	case kindAny:
		return reflect.ValueOf(node), true
	case kindList:
		return d.list(typ, node, path)
	case kindObject:
		return d.object(typ, node, path)
	default:
		return d.scalar(typ, kind, node, path)
	}
}

func (d *decoder) object(typ reflect.Type, node any, path string) (reflect.Value, bool) {
	fields, isObject := node.(map[string]any)
	if !isObject {
		d.report(path, "expected an object", node)
		return reflect.Value{}, false
	}
	pointer := reflect.New(structTypeOf(typ))
	d.fill(pointer.Elem(), fields, path)
	if typ.Kind() == reflect.Pointer {
		return pointer, true
	}
	return pointer.Elem(), true
}

func (d *decoder) list(typ reflect.Type, node any, path string) (reflect.Value, bool) {
	// XML cannot tell a one-item list from a single value; the Go type can.
	raw, isList := node.([]any)
	if !isList {
		raw = []any{node}
	}
	list := reflect.MakeSlice(typ, 0, len(raw))
	index := 0
	fits := true
	for _, item := range raw {
		// An empty or nil item carries no data and would break the []T promise.
		if item == nil {
			continue
		}
		value, itemFits := d.value(typ.Elem(), item, fmt.Sprintf("%s[%d]", path, index))
		index++
		if !itemFits {
			fits = false
			continue
		}
		list = reflect.Append(list, value)
	}
	// []T never holds a zero value standing in for a rejected item: when one item
	// does not fit, the whole list is dropped and the raw items stay in the
	// mismatches.
	if !fits {
		return reflect.Value{}, false
	}
	return list, true
}

func (d *decoder) scalar(typ reflect.Type, kind fieldKind, node any, path string) (reflect.Value, bool) {
	text, isText := node.(string)
	if !isText {
		d.report(path, "expected "+kindNames[kind]+", got "+shapeOf(node), node)
		return reflect.Value{}, false
	}
	switch kind {
	case kindString:
		return reflect.ValueOf(text).Convert(typ), true
	case kindDecimal:
		// Kept as text: no precision loss, whatever the caller parses it with.
		if !floatPattern.MatchString(text) {
			return d.reject(kind, node, path)
		}
		return reflect.ValueOf(Decimal(text)), true
	case kindDate:
		// Providers fill date fields by hand and the formats are not documented,
		// so an unreadable one is kept as text rather than rejected.
		return reflect.ValueOf(parseDate(text)), true
	case kindBytes:
		return d.bytes(node, text, path)
	case kindInt:
		return d.integer(typ, node, text, path)
	case kindFloat:
		return d.float(typ, node, text, path)
	case kindBool:
		return d.boolean(typ, node, text, path)
	default:
		return d.reject(kind, node, path)
	}
}

func shapeOf(node any) string {
	if _, isList := node.([]any); isList {
		return "a list"
	}
	return "an object"
}

func childPath(path, name string) string {
	if path == "" {
		return name
	}
	return path + "." + name
}

// warnMismatches writes the one log line this SDK produces. Field paths and
// reasons only: the values are citizen data and stay out of logs.
func (c *Client) warnMismatches(operation string, mismatches []Mismatch) {
	fields := make([]string, 0, len(mismatches))
	for _, mismatch := range mismatches {
		fields = append(fields, mismatch.String())
	}
	c.logger.Warn(mismatchWarning,
		slog.String("operation", operation),
		slog.String("fields", strings.Join(fields, "; ")),
	)
}
