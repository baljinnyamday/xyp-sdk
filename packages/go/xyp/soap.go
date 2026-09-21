// SOAP 1.1 document/literal envelopes for XYP. Pure functions, no I/O.
//
// Request (only the operation element is namespaced):
//
//	<soap:Envelope><soap:Body><tns:OP>
//	  <request> <auth><citizen/><operator/></auth> ...fields... </request>
//	</tns:OP></soap:Body></soap:Envelope>
//
// Response:
//
//	<return> <requestId/> <resultCode/> <resultMessage/> <response>...</response> </return>

package xyp

import (
	"bytes"
	"encoding/xml"
	"errors"
	"io"
	"maps"
	"regexp"
	"slices"
	"strconv"
	"strings"
)

const (
	soapNamespace  = "http://schemas.xmlsoap.org/soap/envelope/"
	xmlDeclaration = "<?xml version='1.0' encoding='utf-8'?>\n"
	nilAttribute   = "nil"
)

var integerText = regexp.MustCompile(`^-?\d+$`)

// serviceResult is the <return> element every XYP service answers with.
type serviceResult struct {
	requestID  string
	resultCode int
	message    string
	data       any
}

func buildEnvelope(operation, namespace string, params any, citizen, operator *Auth) (string, error) {
	if err := checkName(operation); err != nil {
		return "", err
	}
	auth, err := encodeAuth(citizen, operator)
	if err != nil {
		return "", err
	}
	fields, err := encodeFields(params)
	if err != nil {
		return "", err
	}
	request := wrapElement("request", auth+fields)
	body := wrapElement("soap:Body", wrapElement("tns:"+operation, request))
	attributes := `xmlns:soap="` + soapNamespace + `" xmlns:tns="` + escapeAttribute(namespace) + `"`
	return xmlDeclaration + "<soap:Envelope " + attributes + ">" + body + "</soap:Envelope>", nil
}

// encodeAuth puts <auth> first: every request type extends `serviceRequest`, so
// that is its position in the schema and where zeep (the known-working client)
// puts it.
func encodeAuth(citizen, operator *Auth) (string, error) {
	var fields Params
	if citizen != nil {
		fields = append(fields, Param{Name: "citizen", Value: citizen.toWire()})
	}
	if operator != nil {
		fields = append(fields, Param{Name: "operator", Value: operator.toWire()})
	}
	if len(fields) == 0 {
		return "", nil
	}
	return encodeElement("auth", fields)
}

func parseResponse(payload []byte) (serviceResult, error) {
	document, err := parseDocument(payload)
	if err != nil {
		return serviceResult{}, err
	}
	if fault, found := findElement(document, "Fault"); found {
		reason := textOf(childOf(fault, "faultstring"))
		if reason == "" {
			reason = "unknown SOAP fault"
		}
		return serviceResult{}, responseErrorf(0, "XYP returned a SOAP fault: %s", reason)
	}
	result, found := findElement(document, "return")
	if !found {
		return serviceResult{}, responseErrorf(0, "XYP response has no <return> element")
	}
	code := textOf(childOf(result, "resultCode"))
	if !integerText.MatchString(code) {
		return serviceResult{}, responseErrorf(0, "XYP response has no numeric <resultCode>")
	}
	resultCode, err := strconv.Atoi(code)
	if err != nil {
		return serviceResult{}, responseErrorf(0, "XYP response has no numeric <resultCode>")
	}
	return serviceResult{
		requestID:  textOf(childOf(result, "requestId")),
		resultCode: resultCode,
		message:    textOf(childOf(result, "resultMessage")),
		data:       childOf(result, "response"),
	}, nil
}

// parseDocument turns the payload into element name -> value, recursively.
func parseDocument(payload []byte) (map[string]any, error) {
	decoder := xml.NewDecoder(bytes.NewReader(payload))
	decoder.Strict = true
	document := map[string]any{}
	for {
		token, err := decoder.Token()
		if errors.Is(err, io.EOF) {
			return document, nil
		}
		if err != nil {
			return nil, invalidXML()
		}
		switch typed := token.(type) {
		case xml.Directive:
			// SOAP forbids DTDs, and refusing them outright rules out entity-expansion attacks.
			return nil, invalidXML()
		case xml.StartElement:
			value, err := readElement(decoder, typed)
			if err != nil {
				return nil, err
			}
			addChild(document, typed.Name.Local, value)
		}
	}
}

// readElement reads the content of the element whose start tag was just read.
// A leaf becomes its trimmed text (or nil when empty), an element with children
// becomes a map, and repeated sibling names become a list. Namespace prefixes
// and attributes are ignored; xsi:nil is the one exception.
func readElement(decoder *xml.Decoder, start xml.StartElement) (any, error) {
	if isNil(start) {
		return nil, skipElement(decoder)
	}
	var text strings.Builder
	children := map[string]any{}
	for {
		token, err := decoder.Token()
		if err != nil {
			return nil, invalidXML()
		}
		switch typed := token.(type) {
		case xml.StartElement:
			child, err := readElement(decoder, typed)
			if err != nil {
				return nil, err
			}
			addChild(children, typed.Name.Local, child)
		case xml.CharData:
			text.Write(typed)
		case xml.Directive:
			return nil, invalidXML()
		case xml.EndElement:
			if len(children) > 0 {
				return children, nil
			}
			trimmed := strings.TrimSpace(text.String())
			if trimmed == "" {
				return nil, nil
			}
			return trimmed, nil
		}
	}
}

func skipElement(decoder *xml.Decoder) error {
	if err := decoder.Skip(); err != nil {
		return invalidXML()
	}
	return nil
}

func isNil(start xml.StartElement) bool {
	for _, attribute := range start.Attr {
		if attribute.Name.Local == nilAttribute &&
			(attribute.Value == "true" || attribute.Value == "1") {
			return true
		}
	}
	return false
}

func addChild(parent map[string]any, name string, value any) {
	existing, seen := parent[name]
	if !seen {
		parent[name] = value
		return
	}
	if list, isList := existing.([]any); isList {
		parent[name] = append(list, value)
		return
	}
	parent[name] = []any{existing, value}
}

// findElement is a depth-first search for the first element called name. Keys
// are visited in sorted order, because a Go map has none and the result has to
// be the same on every run.
func findElement(node any, name string) (any, bool) {
	switch typed := node.(type) {
	case map[string]any:
		if child, found := typed[name]; found {
			return child, true
		}
		for _, key := range slices.Sorted(maps.Keys(typed)) {
			if found, ok := findElement(typed[key], name); ok {
				return found, true
			}
		}
	case []any:
		for _, item := range typed {
			if found, ok := findElement(item, name); ok {
				return found, true
			}
		}
	}
	return nil, false
}

func childOf(node any, name string) any {
	parent, isObject := node.(map[string]any)
	if !isObject {
		return nil
	}
	return parent[name]
}

func textOf(node any) string {
	text, isText := node.(string)
	if !isText {
		return ""
	}
	return text
}

func invalidXML() *ResponseError {
	return responseErrorf(0, "XYP returned a response that is not valid XML")
}
