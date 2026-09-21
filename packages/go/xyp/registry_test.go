package xyp

import (
	"encoding/json"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"testing"
)

func TestRegistryCoversEveryServiceInTheSpec(t *testing.T) {
	t.Parallel()
	path := filepath.Join("..", "..", "..", "spec", "services.json")
	payload, err := os.ReadFile(path) //nolint:gosec // a fixed path inside the repository
	if errors.Is(err, fs.ErrNotExist) {
		// A downloaded module zip holds only this module's own files.
		t.Skipf("%s is not part of this module", path)
	}
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	var services []struct {
		OperationName string `json:"operationName"`
	}
	if err := json.Unmarshal(payload, &services); err != nil {
		t.Fatalf("parse %s: %v", path, err)
	}

	want := make([]string, 0, len(services))
	for _, service := range services {
		want = append(want, service.OperationName)
	}
	slices.Sort(want)
	got := make([]string, 0, len(operationEndpoints))
	for operation := range operationEndpoints {
		got = append(got, operation)
	}
	slices.Sort(got)

	if !slices.Equal(got, want) {
		t.Errorf("the registry has %d operations, the spec %d", len(got), len(want))
	}
}

func TestKnownNamespacesAreSeededFromTheCheckedInWSDLs(t *testing.T) {
	t.Parallel()
	namespace, found := knownNamespaces["citizen-1.5.0"]
	if !found || namespace != "http://citizen.xyp.gov.mn/" {
		t.Errorf("knownNamespaces[citizen-1.5.0] = %q, %v", namespace, found)
	}
	if _, found := knownNamespaces["insurance-1.5.0"]; found {
		t.Error("insurance-1.5.0 has no WSDL in the repository, so it must be learned at runtime")
	}
}
