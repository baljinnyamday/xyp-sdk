package xyp

import (
	"errors"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"time"
)

const (
	testNamespace = "http://citizen.xyp.gov.mn/"
	testOperation = "WS100101_getCitizenIDCardInfo"
	xsi           = `xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"`
)

// soapResponse is shaped like the sample on developer.xyp.gov.mn/docs/result-code.
func soapResponse(inner string, code int, message string) string {
	return `<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body>
    <ns2:WS100101_getCitizenIDCardInfoResponse xmlns:ns2="http://citizen.xyp.gov.mn/">
      <return>
        <request ` + xsi + ` xsi:type="ns2:citizenRequestData"/>
        <requestId>4fd9aa5f-1984-4b61-b379-13c1bcbd29c7</requestId>
        <response ` + xsi + ` xsi:type="ns2:citizenData">` + inner + `</response>
        <resultCode>` + strconv.Itoa(code) + `</resultCode>
        <resultMessage>` + message + `</resultMessage>
      </return>
    </ns2:WS100101_getCitizenIDCardInfoResponse>
  </soap:Body>
</soap:Envelope>`
}

type nestedInput struct {
	Code  string `xyp:"code"`
	Empty string `xyp:"empty"`
	Kept  string `xyp:"-"`
}

type requestInput struct {
	Skipped *string     `xyp:"skipped"`
	Flag    bool        `xyp:"flag"`
	IDs     []int64     `xyp:"ids"`
	Nested  nestedInput `xyp:"nested"`
	Photo   []byte      `xyp:"photo"`
}

func TestBuildEnvelopeOmitsEmptyRepeatsSlicesAndNestsObjects(t *testing.T) {
	t.Parallel()
	input := requestInput{
		Flag:   false,
		IDs:    []int64{1, 2},
		Nested: nestedInput{Code: "A", Kept: "not on the wire"},
		Photo:  []byte{0, 1},
	}

	envelope, err := buildEnvelope(testOperation, testNamespace, input, nil, nil)
	if err != nil {
		t.Fatalf("buildEnvelope: %v", err)
	}

	want := "<request><flag>0</flag><ids>1</ids><ids>2</ids>" +
		"<nested><code>A</code></nested><photo>AAE=</photo></request>"
	if !strings.Contains(envelope, want) {
		t.Errorf("envelope = %s, want it to contain %s", envelope, want)
	}
	if strings.Contains(envelope, "not on the wire") {
		t.Error("a field tagged `xyp:\"-\"` reached the wire")
	}
}

func TestBuildEnvelopeAcceptsEveryParamShape(t *testing.T) {
	t.Parallel()
	moment := time.Date(2024, time.January, 31, 12, 0, 0, 0, time.UTC)
	for _, test := range []struct {
		name   string
		params any
		want   string
	}{
		{"nil", nil, "<request />"},
		{"empty params", Params{}, "<request />"},
		{"ordered params", Params{{Name: "b", Value: "2"}, {Name: "a", Value: "1"}},
			"<request><b>2</b><a>1</a></request>"},
		{"map is sorted", map[string]any{"b": "2", "a": "1"},
			"<request><a>1</a><b>2</b></request>"},
		{"pointer to struct", &nestedInput{Code: "A"}, "<request><code>A</code></request>"},
		{"time is UTC RFC 3339", Params{{Name: "at", Value: moment}},
			"<request><at>2024-01-31T12:00:00Z</at></request>"},
		{"a raw date is sent as it stands", Params{{Name: "at", Value: Date{Raw: "31.01.2024"}}},
			"<request><at>31.01.2024</at></request>"},
		{"a zero date is left out", Params{{Name: "at", Value: Date{}}}, "<request />"},
		{"a date with only a time is formatted", Params{{Name: "at", Value: Date{Time: moment}}},
			"<request><at>2024-01-31T12:00:00Z</at></request>"},
		{"floats keep their shortest form", Params{{Name: "n", Value: 0.5}},
			"<request><n>0.5</n></request>"},
		{"a nil pointer is left out", Params{{Name: "n", Value: (*int64)(nil)}}, "<request />"},
		{"a pointer is followed", Params{{Name: "n", Value: Ptr(int64(7))}},
			"<request><n>7</n></request>"},
	} {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			envelope, err := buildEnvelope(testOperation, testNamespace, test.params, nil, nil)
			if err != nil {
				t.Fatalf("buildEnvelope: %v", err)
			}
			if !strings.Contains(envelope, test.want) {
				t.Errorf("envelope = %s, want it to contain %s", envelope, test.want)
			}
		})
	}
}

func TestBuildEnvelopeRejectsAValueItCannotSend(t *testing.T) {
	t.Parallel()
	_, err := buildEnvelope(testOperation, testNamespace, Params{
		{Name: "bad", Value: make(chan int)},
	}, nil, nil)

	var configErr *ConfigError
	if !errors.As(err, &configErr) {
		t.Fatalf("err = %v, want a *ConfigError", err)
	}
	if OriginOf(err) != OriginConfig {
		t.Errorf("origin = %q, want %q", OriginOf(err), OriginConfig)
	}
}

func TestBuildEnvelopeCannotBeUsedToInjectXML(t *testing.T) {
	t.Parallel()
	envelope, err := buildEnvelope(testOperation, `ns"/><evil`, Params{
		{Name: "regnum", Value: "</regnum><admin>true</admin>"},
	}, nil, nil)
	if err != nil {
		t.Fatalf("buildEnvelope: %v", err)
	}

	want := "<regnum>&lt;/regnum&gt;&lt;admin&gt;true&lt;/admin&gt;</regnum>"
	if !strings.Contains(envelope, want) {
		t.Errorf("envelope = %s, want it to contain %s", envelope, want)
	}
	if strings.Contains(envelope, "<admin>") || strings.Contains(envelope, "<evil") {
		t.Errorf("markup survived escaping: %s", envelope)
	}
	if !strings.Contains(envelope, `xmlns:tns="ns&quot;/&gt;&lt;evil"`) {
		t.Errorf("the namespace attribute was not escaped: %s", envelope)
	}
}

func TestBuildEnvelopeStartsWithTheDeclarationAndAuth(t *testing.T) {
	t.Parallel()
	citizen := OTPAuth("РД00000000", 1234)
	envelope, err := buildEnvelope(testOperation, testNamespace,
		Params{{Name: "regnum", Value: "РД00000000"}}, &citizen, nil)
	if err != nil {
		t.Fatalf("buildEnvelope: %v", err)
	}

	if !strings.HasPrefix(envelope, "<?xml version='1.0' encoding='utf-8'?>\n<soap:Envelope ") {
		t.Errorf("envelope does not start with the declaration: %q", envelope[:60])
	}
	want := "<request><auth><citizen><authType>1</authType><otp>1234</otp>" +
		"<regnum>РД00000000</regnum></citizen></auth><regnum>"
	if !strings.Contains(envelope, want) {
		t.Errorf("envelope = %s, want it to contain %s", envelope, want)
	}
}

func TestParseResponseReadsTheDocumentedShape(t *testing.T) {
	t.Parallel()
	result, err := parseResponse([]byte(soapResponse(
		`<firstname>Бат &amp; &#1041;</firstname><empty/><gone xsi:nil="true"/>`+
			"<listData><year>2020</year></listData><listData><year>2021</year></listData>", 0,
		"амжилттай")))
	if err != nil {
		t.Fatalf("parseResponse: %v", err)
	}

	if result.requestID != "4fd9aa5f-1984-4b61-b379-13c1bcbd29c7" {
		t.Errorf("requestID = %q", result.requestID)
	}
	if result.resultCode != 0 || result.message != "амжилттай" {
		t.Errorf("result = %d %q", result.resultCode, result.message)
	}
	want := map[string]any{
		"firstname": "Бат & Б",
		"empty":     nil,
		"gone":      nil,
		"listData":  []any{map[string]any{"year": "2020"}, map[string]any{"year": "2021"}},
	}
	if !reflect.DeepEqual(result.data, want) {
		t.Errorf("data = %#v, want %#v", result.data, want)
	}
}

func TestParseResponseKeepsNumericLookingTextAsText(t *testing.T) {
	t.Parallel()
	result, err := parseResponse([]byte(soapResponse(
		"<regnum>0012</regnum><phone>+97699</phone>", 0, "ok")))
	if err != nil {
		t.Fatalf("parseResponse: %v", err)
	}
	want := map[string]any{"regnum": "0012", "phone": "+97699"}
	if !reflect.DeepEqual(result.data, want) {
		t.Errorf("data = %#v, want %#v", result.data, want)
	}
}

func TestParseResponseGivesNilDataForAnEmptyResponseElement(t *testing.T) {
	t.Parallel()
	result, err := parseResponse([]byte(soapResponse("", 1, "олдсонгүй")))
	if err != nil {
		t.Fatalf("parseResponse: %v", err)
	}
	if result.data != nil {
		t.Errorf("data = %#v, want nil", result.data)
	}
	if result.resultCode != 1 {
		t.Errorf("resultCode = %d, want 1", result.resultCode)
	}
}

func TestParseResponseTurnsFaultsGarbageAndDTDsIntoResponseErrors(t *testing.T) {
	t.Parallel()
	fault := `<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>` +
		`<soap:Fault><faultcode>soap:Client</faultcode><faultstring>Unmarshalling Error` +
		`</faultstring></soap:Fault></soap:Body></soap:Envelope>`
	bomb := `<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa">]><x>&a;</x>`

	for _, test := range []struct{ name, payload, want string }{
		{"fault", fault, "Unmarshalling Error"},
		{"gateway page", "<html>gateway timeout", "not valid XML"},
		{"no return element", "<a/>", "<return>"},
		{"entity bomb", bomb, "not valid XML"},
	} {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			_, err := parseResponse([]byte(test.payload))
			var responseErr *ResponseError
			if !errors.As(err, &responseErr) {
				t.Fatalf("err = %v, want a *ResponseError", err)
			}
			if !strings.Contains(responseErr.Message, test.want) {
				t.Errorf("message = %q, want it to contain %q", responseErr.Message, test.want)
			}
			if OriginOf(err) != OriginXYP {
				t.Errorf("origin = %q, want %q", OriginOf(err), OriginXYP)
			}
		})
	}
}

func TestParseResponseRequiresANumericResultCode(t *testing.T) {
	t.Parallel()
	_, err := parseResponse([]byte(soapResponse("", 0, "ok")))
	if err != nil {
		t.Fatalf("a numeric code must be accepted: %v", err)
	}
	broken := strings.Replace(soapResponse("", 0, "ok"),
		"<resultCode>0</resultCode>", "<resultCode>ok</resultCode>", 1)
	if _, err := parseResponse([]byte(broken)); err == nil ||
		!strings.Contains(err.Error(), "numeric <resultCode>") {
		t.Errorf("err = %v, want a complaint about <resultCode>", err)
	}
}

func TestBuildEnvelopeRejectsNamesThatAreNotXMLNames(t *testing.T) {
	t.Parallel()
	cases := map[string]struct {
		operation string
		params    any
	}{
		"param name":  {testOperation, Params{{Name: "a><admin>1</admin><b", Value: "x"}}},
		"map key":     {testOperation, map[string]any{"a/><evil": "x"}},
		"nested name": {testOperation, Params{{Name: "ok", Value: Params{{Name: "b c", Value: "x"}}}}},
		"empty name":  {testOperation, Params{{Name: "", Value: "x"}}},
		"operation":   {"WS1><evil", nil},
	}
	for name, test := range cases {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			envelope, err := buildEnvelope(test.operation, testNamespace, test.params, nil, nil)
			var configErr *ConfigError
			if !errors.As(err, &configErr) {
				t.Fatalf("err = %v (envelope %q), want a *ConfigError", err, envelope)
			}
		})
	}
}

func TestBuildEnvelopeOmitsAZeroTime(t *testing.T) {
	t.Parallel()
	var unset *time.Time
	envelope, err := buildEnvelope(testOperation, testNamespace, Params{
		{Name: "zero", Value: time.Time{}},
		{Name: "unset", Value: unset},
	}, nil, nil)
	if err != nil {
		t.Fatalf("buildEnvelope: %v", err)
	}
	if !strings.Contains(envelope, "<request />") {
		t.Errorf("envelope = %s, want an empty request", envelope)
	}
}
