package xyp

import (
	"bytes"
	"errors"
	"fmt"
	"log/slog"
	"reflect"
	"strings"
	"testing"
	"time"
)

type sampleYear struct {
	Year *int64 `xyp:"year"`
}

type sampleAddress struct {
	City string `xyp:"city"`
}

type sample struct {
	FirstName string         `xyp:"firstName"`
	Age       *int64         `xyp:"age"`
	Active    *bool          `xyp:"active"`
	Born      Date           `xyp:"born"`
	Photo     []byte         `xyp:"photo"`
	Amount    Decimal        `xyp:"amount"`
	Ratio     *float64       `xyp:"ratio"`
	ListData  []sampleYear   `xyp:"listData"`
	Address   *sampleAddress `xyp:"address"`
	Anything  any            `xyp:"anything"`
	Xyp       Extras         `xyp:"-"`
}

func decodeSample(t *testing.T, tree any) (sample, []Mismatch) {
	t.Helper()
	var out sample
	mismatches, err := decodeInto(&out, tree)
	if err != nil {
		t.Fatalf("decodeInto: %v", err)
	}
	return out, mismatches
}

func TestDecodeCoercesTextByFieldTypeAndLeavesMissingFieldsZero(t *testing.T) {
	t.Parallel()
	out, mismatches := decodeSample(t, map[string]any{
		"firstName": "Бат",
		"age":       "34",
		"active":    "true",
		"born":      "1990-05-01T00:00:00+08:00",
		"photo":     "AAE=",
		"amount":    "1234567890123456789.50",
		"ratio":     "0.5",
		"anything":  map[string]any{"kept": "as is"},
	})

	if len(mismatches) != 0 {
		t.Fatalf("mismatches = %v, want none", mismatches)
	}
	if out.FirstName != "Бат" {
		t.Errorf("FirstName = %q", out.FirstName)
	}
	if out.Age == nil || *out.Age != 34 {
		t.Errorf("Age = %v", out.Age)
	}
	if out.Active == nil || !*out.Active {
		t.Errorf("Active = %v", out.Active)
	}
	if !out.Born.Time.Equal(time.Date(1990, time.May, 1, 0, 0, 0, 0, time.FixedZone("", 8*3600))) {
		t.Errorf("Born = %#v", out.Born)
	}
	if !bytes.Equal(out.Photo, []byte{0, 1}) {
		t.Errorf("Photo = %v", out.Photo)
	}
	if out.Amount != "1234567890123456789.50" {
		t.Errorf("Amount = %q, want the text unchanged", out.Amount)
	}
	if out.Ratio == nil || *out.Ratio != 0.5 {
		t.Errorf("Ratio = %v", out.Ratio)
	}
	if out.ListData != nil {
		t.Errorf("ListData = %v, want nil for an absent list", out.ListData)
	}
	if !reflect.DeepEqual(out.Anything, map[string]any{"kept": "as is"}) {
		t.Errorf("Anything = %#v", out.Anything)
	}
}

func TestDecodeReadsDatesLeniently(t *testing.T) {
	t.Parallel()
	for _, test := range []struct {
		text    string
		hasTime bool
	}{
		{"2024-01-31", true},
		{"2024-01-31 12:00:00", true},
		{"2024-01-31T12:00:00+0800", true},
		{"2020", false},       // a year, not a timestamp
		{"31.01.2024", false}, // undocumented formats stay text instead of failing
	} {
		t.Run(test.text, func(t *testing.T) {
			t.Parallel()
			out, mismatches := decodeSample(t, map[string]any{"born": test.text})
			if len(mismatches) != 0 {
				t.Fatalf("a date is never a mismatch, got %v", mismatches)
			}
			if out.Born.Raw != test.text {
				t.Errorf("Raw = %q, want %q", out.Born.Raw, test.text)
			}
			if out.Born.Time.IsZero() == test.hasTime {
				t.Errorf("Time = %v for %q", out.Born.Time, test.text)
			}
		})
	}
}

func TestDecodeDatesWithoutAZoneAreUTC(t *testing.T) {
	t.Parallel()
	out, _ := decodeSample(t, map[string]any{"born": "2024-01-31T12:00:00"})
	want := time.Date(2024, time.January, 31, 12, 0, 0, 0, time.UTC)
	if !out.Born.Time.Equal(want) {
		t.Errorf("Time = %v, want %v", out.Born.Time, want)
	}
}

func TestDecodeTreatsASingleItemAsAOneItemList(t *testing.T) {
	t.Parallel()
	out, mismatches := decodeSample(t, map[string]any{
		"listData": map[string]any{"year": "2020"},
	})
	if len(mismatches) != 0 {
		t.Fatalf("mismatches = %v", mismatches)
	}
	if len(out.ListData) != 1 || out.ListData[0].Year == nil || *out.ListData[0].Year != 2020 {
		t.Errorf("ListData = %#v", out.ListData)
	}
}

func TestDecodeDropsNilItemsWithoutComplaining(t *testing.T) {
	t.Parallel()
	out, mismatches := decodeSample(t, map[string]any{
		"listData": []any{map[string]any{"year": "1"}, nil},
	})
	if len(mismatches) != 0 {
		t.Fatalf("a nil item from XYP is not a mismatch, got %v", mismatches)
	}
	if len(out.ListData) != 1 {
		t.Errorf("ListData = %#v, want one item", out.ListData)
	}
}

func TestDecodeAcceptsAMissingResponse(t *testing.T) {
	t.Parallel()
	out, mismatches := decodeSample(t, nil)
	if len(mismatches) != 0 {
		t.Fatalf("mismatches = %v", mismatches)
	}
	if out.FirstName != "" || out.Age != nil || !out.Born.IsZero() {
		t.Errorf("out = %#v, want the zero value", out)
	}
}

func TestDecodeNeverFailsTheCallAndSaysWhoseFaultItIs(t *testing.T) {
	t.Parallel()
	out, mismatches := decodeSample(t, map[string]any{
		"firstName": "Бат",
		"age":       "not-a-number-РД00000000",
		"listData":  []any{map[string]any{"year": "2020"}, map[string]any{"year": "MMXXI"}},
		"address":   "just text",
	})

	if out.FirstName != "Бат" {
		t.Errorf("FirstName = %q, a field that fits is still decoded", out.FirstName)
	}
	if out.Age != nil {
		t.Errorf("Age = %v, want nil", out.Age)
	}
	// A nested field that does not fit only costs that field, not the whole list.
	if len(out.ListData) != 2 || out.ListData[1].Year != nil {
		t.Errorf("ListData = %#v", out.ListData)
	}
	if out.Address != nil {
		t.Errorf("Address = %v, want nil", out.Address)
	}
	wantPaths := []string{"age", "listData[1].year", "address"}
	wantValues := []any{"not-a-number-РД00000000", "MMXXI", "just text"}
	for index, mismatch := range mismatches {
		if index >= len(wantPaths) {
			break
		}
		if mismatch.Path != wantPaths[index] || mismatch.Value() != wantValues[index] {
			t.Errorf("mismatch %d = %v (%v)", index, mismatch.Path, mismatch.Value())
		}
	}
	if len(mismatches) != len(wantPaths) {
		t.Fatalf("mismatches = %v, want %v", mismatches, wantPaths)
	}
}

func TestMismatchNeverPrintsTheValue(t *testing.T) {
	t.Parallel()
	_, mismatches := decodeSample(t, map[string]any{"age": "РД00000000"})
	if len(mismatches) != 1 {
		t.Fatalf("mismatches = %v", mismatches)
	}
	for _, format := range []string{"%v", "%+v", "%#v", "%s"} {
		printed := fmt.Sprintf(format, mismatches[0])
		if strings.Contains(printed, "РД00000000") {
			t.Errorf("%s printed the raw value: %s", format, printed)
		}
		if !strings.Contains(printed, "age") {
			t.Errorf("%s did not name the field: %s", format, printed)
		}
	}
	if mismatches[0].Value() != "РД00000000" {
		t.Errorf("Value() = %v, the caller must still be able to ask", mismatches[0].Value())
	}
}

func TestDecodeNeverPutsAZeroValueInsideAList(t *testing.T) {
	t.Parallel()
	type lists struct {
		Years    []*int64     `xyp:"years"`
		ListData []sampleYear `xyp:"listData"`
	}
	var out lists
	mismatches, err := decodeInto(&out, map[string]any{
		"years":    []any{"2020", "MMXXI"},
		"listData": []any{map[string]any{"year": "1"}, "text"},
	})
	if err != nil {
		t.Fatalf("decodeInto: %v", err)
	}

	if out.Years != nil || out.ListData != nil {
		t.Errorf("out = %#v, want both lists dropped", out)
	}
	want := []string{"years[1]", "listData[1]"}
	got := []string{}
	for _, mismatch := range mismatches {
		got = append(got, mismatch.Path)
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("paths = %v, want %v", got, want)
	}
}

func TestDecodeFillsListsOfEverySupportedItemType(t *testing.T) {
	t.Parallel()
	type lists struct {
		Names    []string  `xyp:"names"`
		Anything []any     `xyp:"anything"`
		Amounts  []Decimal `xyp:"amounts"`
		Dates    []Date    `xyp:"dates"`
	}
	var out lists
	mismatches, err := decodeInto(&out, map[string]any{
		"names":    []any{"a", "b"},
		"anything": []any{"text", map[string]any{"nested": "value"}},
		"amounts":  "1.5",
		"dates":    []any{"2024-01-31", "31.01.2024"},
	})
	if err != nil {
		t.Fatalf("decodeInto: %v", err)
	}

	if len(mismatches) != 0 {
		t.Fatalf("mismatches = %v", mismatches)
	}
	if !reflect.DeepEqual(out.Names, []string{"a", "b"}) {
		t.Errorf("Names = %#v", out.Names)
	}
	if len(out.Anything) != 2 ||
		!reflect.DeepEqual(out.Anything[1], map[string]any{"nested": "value"}) {
		t.Errorf("Anything = %#v", out.Anything)
	}
	if !reflect.DeepEqual(out.Amounts, []Decimal{"1.5"}) {
		t.Errorf("Amounts = %#v, want a single value wrapped in a list", out.Amounts)
	}
	if len(out.Dates) != 2 || out.Dates[1].Raw != "31.01.2024" {
		t.Errorf("Dates = %#v", out.Dates)
	}
}

func TestDecodeReportsTextInABytesFieldInsteadOfGarbage(t *testing.T) {
	t.Parallel()
	// "none" is left out: four alphabet characters are valid base64 in any decoder.
	for _, text := range []string{"N/A", "0", "NoImage", "байхгүй"} {
		t.Run(text, func(t *testing.T) {
			t.Parallel()
			out, mismatches := decodeSample(t, map[string]any{"photo": text})
			if out.Photo != nil {
				t.Errorf("Photo = %v, want nil", out.Photo)
			}
			if len(mismatches) != 1 || mismatches[0].Value() != text {
				t.Errorf("mismatches = %v", mismatches)
			}
		})
	}
	wrapped, mismatches := decodeSample(t, map[string]any{"photo": "AA\nE="})
	if len(mismatches) != 0 || !bytes.Equal(wrapped.Photo, []byte{0, 1}) {
		t.Errorf("line-wrapped base64 must decode, got %v %v", wrapped.Photo, mismatches)
	}
}

func TestDecodeReadsHandTypedBooleansAndIntegers(t *testing.T) {
	t.Parallel()
	for _, text := range []string{"Y", "yes", "T", "on", "TRUE"} {
		out, _ := decodeSample(t, map[string]any{"active": text})
		if out.Active == nil || !*out.Active {
			t.Errorf("%q did not read as true", text)
		}
	}
	for _, text := range []string{"N", "no", "F", "off", "0"} {
		out, _ := decodeSample(t, map[string]any{"active": text})
		if out.Active == nil || *out.Active {
			t.Errorf("%q did not read as false", text)
		}
	}
	rounded, _ := decodeSample(t, map[string]any{"age": "34.0"})
	if rounded.Age == nil || *rounded.Age != 34 {
		t.Errorf(`"34.0" is an integer written by a spreadsheet, got %v`, rounded.Age)
	}
	fractional, mismatches := decodeSample(t, map[string]any{"age": "34.5"})
	if fractional.Age != nil || len(mismatches) != 1 {
		t.Errorf(`"34.5" is not an integer, got %v %v`, fractional.Age, mismatches)
	}
}

func TestDecodeRejectsIntegersThatDoNotFitInt64(t *testing.T) {
	t.Parallel()
	out, mismatches := decodeSample(t, map[string]any{"age": "9223372036854775808"})
	if out.Age != nil {
		t.Errorf("Age = %v, want nil", out.Age)
	}
	if len(mismatches) != 1 || mismatches[0].Problem != "not a valid int" {
		t.Errorf("mismatches = %v", mismatches)
	}
}

func TestDecodeReportsAResponseThatIsNotAnObject(t *testing.T) {
	t.Parallel()
	out, mismatches := decodeSample(t, "just text")
	if out.FirstName != "" {
		t.Errorf("FirstName = %q", out.FirstName)
	}
	if len(mismatches) != 1 || mismatches[0].Path != "" ||
		mismatches[0].Problem != "expected an object" {
		t.Fatalf("mismatches = %v", mismatches)
	}
	if mismatches[0].String() != "<response> (expected an object)" {
		t.Errorf("String() = %q", mismatches[0].String())
	}
}

func TestDecodeNamesTheShapeItGotForAScalar(t *testing.T) {
	t.Parallel()
	for _, test := range []struct {
		name, want string
		node       any
	}{
		{"list", "expected string, got a list", []any{"a"}},
		{"object", "expected string, got an object", map[string]any{"a": "b"}},
	} {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			_, mismatches := decodeSample(t, map[string]any{"firstName": test.node})
			if len(mismatches) != 1 || mismatches[0].Problem != test.want {
				t.Errorf("mismatches = %v, want %q", mismatches, test.want)
			}
		})
	}
}

func TestDecodeRejectsAnOutThatIsNotAPointerToAStruct(t *testing.T) {
	t.Parallel()
	var notAStruct string
	for _, out := range []any{nil, sample{}, &notAStruct, (*sample)(nil)} {
		var configErr *ConfigError
		if _, err := decodeInto(out, nil); !errors.As(err, &configErr) {
			t.Errorf("decodeInto(%T) = %v, want a *ConfigError", out, err)
		}
	}
}

func TestDecodeRejectsAFieldTypeItCannotFill(t *testing.T) {
	t.Parallel()
	for _, out := range []any{
		&struct {
			Age int `xyp:"age"`
		}{},
		&struct {
			At time.Time `xyp:"at"`
		}{},
		&struct {
			Name *string `xyp:"name"`
		}{},
	} {
		var configErr *ConfigError
		// The type is checked before the data, so the error does not depend on
		// whether XYP happened to send the field.
		if _, err := decodeInto(out, nil); !errors.As(err, &configErr) {
			t.Errorf("decodeInto(%T) = %v, want a *ConfigError", out, err)
		}
	}
}

func TestWarnMismatchesLogsPathsButNeverValues(t *testing.T) {
	t.Parallel()
	var logged bytes.Buffer
	client := &Client{logger: slog.New(slog.NewTextHandler(&logged, nil))}

	_, mismatches := decodeSample(t, map[string]any{"age": "РД00000000", "photo": "N/A"})
	client.warnMismatches("WS100101_getCitizenIDCardInfo", mismatches)

	output := logged.String()
	for _, want := range []string{
		"WS100101_getCitizenIDCardInfo", "age (not a valid int)", "photo (not a valid bytes)",
		"gap in the SDK's models", IssuesURL,
	} {
		if !strings.Contains(output, want) {
			t.Errorf("log does not mention %q: %s", want, output)
		}
	}
	if strings.Contains(output, "РД00000000") {
		t.Errorf("citizen data reached the log: %s", output)
	}
	if count := strings.Count(output, "level=WARN"); count != 1 {
		t.Errorf("wrote %d warnings, want exactly one", count)
	}
}
