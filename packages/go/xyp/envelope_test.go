package xyp

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"os"
	"slices"
	"testing"
	"time"
)

// The fixtures are the Python SDK's output, which is verified against zeep (the SOAP
// library the known-working XYP clients use) for every operation in spec/wsdl.
// Same input, same bytes.
type envelopeFixture struct {
	Operation  string                     `json:"operation"`
	Namespace  string                     `json:"namespace"`
	Params     map[string]json.RawMessage `json:"params"`
	ParamOrder []string                   `json:"paramOrder"`
	DateFields []string                   `json:"dateFields"`
	Auth       *struct {
		Citizen struct {
			Regnum string `json:"regnum"`
			OTP    int    `json:"otp"`
		} `json:"citizen"`
		Operator struct {
			Regnum            string `json:"regnum"`
			FingerprintBase64 string `json:"fingerprintBase64"`
		} `json:"operator"`
	} `json:"auth"`
	Envelope string `json:"envelope"`
}

// The WSDLs we hold cover this many operations; a fixture file with fewer than
// that is a sign the export went wrong.
const minimumFixtures = 160

func TestEnvelopesAreByteIdenticalToTheZeepVerifiedFixtures(t *testing.T) {
	t.Parallel()
	fixtures := loadFixtures(t)
	if len(fixtures) <= minimumFixtures {
		t.Fatalf("only %d fixtures, want more than %d", len(fixtures), minimumFixtures)
	}
	for _, fixture := range fixtures {
		t.Run(fixture.Operation, func(t *testing.T) {
			t.Parallel()
			citizen, operator := fixtureAuth(t, fixture)
			got, err := buildEnvelope(
				fixture.Operation, fixture.Namespace, fixtureParams(t, fixture), citizen, operator,
			)
			if err != nil {
				t.Fatalf("buildEnvelope: %v", err)
			}
			if got != fixture.Envelope {
				t.Errorf("envelope mismatch\n got: %s\nwant: %s", got, fixture.Envelope)
			}
		})
	}
}

// fixtureParams rebuilds the request in schema order, which a JSON object cannot
// keep in Go.
func fixtureParams(t *testing.T, fixture envelopeFixture) Params {
	t.Helper()
	params := make(Params, 0, len(fixture.ParamOrder))
	for _, name := range fixture.ParamOrder {
		raw, found := fixture.Params[name]
		if !found {
			t.Fatalf("paramOrder names %q, which params does not hold", name)
		}
		params = append(params, Param{Name: name, Value: fixtureValue(t, fixture, name, raw)})
	}
	return params
}

func fixtureValue(t *testing.T, fixture envelopeFixture, name string, raw json.RawMessage) any {
	t.Helper()
	if slices.Contains(fixture.DateFields, name) {
		var text string
		decodeJSON(t, raw, &text)
		moment, err := time.Parse(time.RFC3339, text)
		if err != nil {
			t.Fatalf("date field %q: %v", name, err)
		}
		return moment
	}
	var value any
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber() // an int64 must not become a float64 and gain a ".0"
	if err := decoder.Decode(&value); err != nil {
		t.Fatalf("field %q: %v", name, err)
	}
	if number, isNumber := value.(json.Number); isNumber {
		integer, err := number.Int64()
		if err != nil {
			t.Fatalf("field %q: %v", name, err)
		}
		return integer
	}
	return value
}

func fixtureAuth(t *testing.T, fixture envelopeFixture) (citizen, operator *Auth) {
	t.Helper()
	if fixture.Auth == nil {
		return nil, nil
	}
	fingerprint, err := base64.StdEncoding.DecodeString(fixture.Auth.Operator.FingerprintBase64)
	if err != nil {
		t.Fatalf("operator fingerprint: %v", err)
	}
	approval := OTPAuth(fixture.Auth.Citizen.Regnum, fixture.Auth.Citizen.OTP)
	staff := FingerprintAuth(fixture.Auth.Operator.Regnum, fingerprint)
	return &approval, &staff
}

func loadFixtures(t *testing.T) []envelopeFixture {
	t.Helper()
	payload, err := os.ReadFile("testdata/envelopes.json")
	if err != nil {
		t.Fatalf("read fixtures: %v", err)
	}
	var fixtures []envelopeFixture
	decodeJSON(t, payload, &fixtures)
	return fixtures
}

func decodeJSON(t *testing.T, payload []byte, into any) {
	t.Helper()
	if err := json.Unmarshal(payload, into); err != nil {
		t.Fatalf("parse fixtures: %v", err)
	}
}
