package xyp

import (
	"encoding/base64"
	"reflect"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
)

// structTag names the wire field a Go struct field maps to, on requests and
// responses alike.
const structTag = "xyp"

// escapeText escapes the three characters that are special in element content.
// encoding/xml is not used for requests: it escapes more characters (and writes
// \n as &#xA;), which would no longer match the bytes zeep sends.
var escapeText = strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;").Replace

// escapeAttribute also escapes the quote that delimits an attribute value.
var escapeAttribute = strings.NewReplacer(
	"&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;",
).Replace

// xmlName is what may become an element name. Names reach the envelope unescaped
// (an element name has no escaped form), so a name taken from a map key or a raw
// Call is checked instead: anything else could rewrite the request.
var xmlName = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_.-]*$`)

func checkName(name string) error {
	if !xmlName.MatchString(name) {
		return configErrorf("%q cannot be sent to XYP: it is not a valid XML element name", name)
	}
	return nil
}

// wireField is one exported struct field and the element name it carries.
type wireField struct {
	index int
	name  string
}

// wireFields lists a struct's exported fields in declaration order, with the
// name from the `xyp` tag, or the Go field name when the tag is absent. A tag of
// "-" drops the field, as it does in encoding/json.
func wireFields(typ reflect.Type) []wireField {
	fields := make([]wireField, 0, typ.NumField())
	for index := range typ.NumField() {
		field := typ.Field(index)
		name := field.Tag.Get(structTag)
		if !field.IsExported() || name == "-" {
			continue
		}
		if name == "" {
			name = field.Name
		}
		fields = append(fields, wireField{index: index, name: name})
	}
	return fields
}

// encodeFields renders the body of a <request> element. See the package
// documentation for the accepted shapes of params and the encoding of values.
func encodeFields(params any) (string, error) {
	if params == nil {
		return "", nil
	}
	if fields, ok := params.(Params); ok {
		return encodeParams(fields)
	}
	value := reflect.ValueOf(params)
	for value.Kind() == reflect.Pointer {
		if value.IsNil() {
			return "", nil
		}
		value = value.Elem()
	}
	switch value.Kind() {
	case reflect.Struct:
		return encodeStruct(value)
	case reflect.Map:
		return encodeMap(value)
	default:
		return "", configErrorf(
			"params must be nil, a struct, xyp.Params or a map with string keys, got %s",
			value.Type(),
		)
	}
}

func encodeParams(params Params) (string, error) {
	var out strings.Builder
	for _, param := range params {
		element, err := encodeElement(param.Name, param.Value)
		if err != nil {
			return "", err
		}
		out.WriteString(element)
	}
	return out.String(), nil
}

func encodeStruct(value reflect.Value) (string, error) {
	var out strings.Builder
	for _, field := range wireFields(value.Type()) {
		element, err := encodeElement(field.name, value.Field(field.index).Interface())
		if err != nil {
			return "", err
		}
		out.WriteString(element)
	}
	return out.String(), nil
}

// encodeMap writes the keys sorted, because a Go map has no order. Use a struct
// or xyp.Params when the service's schema order matters.
func encodeMap(value reflect.Value) (string, error) {
	if value.Type().Key().Kind() != reflect.String {
		return "", configErrorf("map keys must be strings, got %s", value.Type().Key())
	}
	keys := value.MapKeys()
	slices.SortFunc(keys, func(a, b reflect.Value) int { return strings.Compare(a.String(), b.String()) })
	var out strings.Builder
	for _, key := range keys {
		element, err := encodeElement(key.String(), value.MapIndex(key).Interface())
		if err != nil {
			return "", err
		}
		out.WriteString(element)
	}
	return out.String(), nil
}

func encodeElement(name string, value any) (string, error) {
	if err := checkName(name); err != nil {
		return "", err
	}
	if value == nil {
		return "", nil
	}
	switch typed := value.(type) {
	case Params:
		inner, err := encodeParams(typed)
		if err != nil {
			return "", err
		}
		return wrapElement(name, inner), nil
	case Date:
		if typed.IsZero() {
			return "", nil
		}
		if typed.Raw != "" {
			return wrapElement(name, escapeText(typed.Raw)), nil
		}
		return wrapElement(name, formatTime(typed.Time)), nil
	case time.Time:
		// The zero time is an unset field, like "" and a nil pointer.
		if typed.IsZero() {
			return "", nil
		}
		return wrapElement(name, formatTime(typed)), nil
	case []byte:
		return encodeBytes(name, typed), nil
	}
	return encodeReflected(name, reflect.ValueOf(value))
}

func encodeReflected(name string, value reflect.Value) (string, error) {
	switch value.Kind() {
	case reflect.Pointer, reflect.Interface:
		if value.IsNil() {
			return "", nil
		}
		return encodeElement(name, value.Elem().Interface())
	case reflect.Slice, reflect.Array:
		return encodeSequence(name, value)
	case reflect.Map:
		if value.IsNil() {
			return "", nil
		}
		inner, err := encodeMap(value)
		if err != nil {
			return "", err
		}
		return wrapElement(name, inner), nil
	case reflect.Struct:
		inner, err := encodeStruct(value)
		if err != nil {
			return "", err
		}
		return wrapElement(name, inner), nil
	default:
		return encodeScalar(name, value)
	}
}

// encodeSequence repeats the element once per item, which is how XML writes a
// list. A byte slice is a single base64 element instead.
func encodeSequence(name string, value reflect.Value) (string, error) {
	if value.Kind() == reflect.Slice && value.Type().Elem().Kind() == reflect.Uint8 {
		return encodeBytes(name, value.Bytes()), nil
	}
	if value.Kind() == reflect.Slice && value.IsNil() {
		return "", nil
	}
	var out strings.Builder
	for index := range value.Len() {
		element, err := encodeElement(name, value.Index(index).Interface())
		if err != nil {
			return "", err
		}
		out.WriteString(element)
	}
	return out.String(), nil
}

func encodeBytes(name string, data []byte) string {
	if data == nil {
		return ""
	}
	return wrapElement(name, base64.StdEncoding.EncodeToString(data))
}

func encodeScalar(name string, value reflect.Value) (string, error) {
	switch value.Kind() {
	case reflect.String:
		// An empty string is the zero value of an unset field, so it is left out
		// rather than sent as an empty element.
		if value.String() == "" {
			return "", nil
		}
		return wrapElement(name, escapeText(value.String())), nil
	case reflect.Bool:
		// "1"/"0" is valid for xs:boolean and xs:int alike; the portal calls some
		// xs:int flags "boolean", so this form is accepted whichever the server declares.
		if value.Bool() {
			return wrapElement(name, "1"), nil
		}
		return wrapElement(name, "0"), nil
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		return wrapElement(name, strconv.FormatInt(value.Int(), 10)), nil
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		return wrapElement(name, strconv.FormatUint(value.Uint(), 10)), nil
	case reflect.Float32:
		return wrapElement(name, strconv.FormatFloat(value.Float(), 'f', -1, 32)), nil
	case reflect.Float64:
		return wrapElement(name, strconv.FormatFloat(value.Float(), 'f', -1, 64)), nil
	default:
		return "", configErrorf("cannot send field %q of type %s to XYP", name, value.Type())
	}
}

// formatTime writes the lexical form zeep (the known-working client) sends: UTC
// with "Z", and no trailing zeros in the fraction.
func formatTime(value time.Time) string {
	return value.UTC().Format(time.RFC3339Nano)
}

func wrapElement(name, inner string) string {
	if inner == "" {
		return "<" + name + " />"
	}
	return "<" + name + ">" + inner + "</" + name + ">"
}
