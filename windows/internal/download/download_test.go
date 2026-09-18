package download

import (
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestUniqueName(t *testing.T) {
	dir := t.TempDir()
	touch := func(n string) { os.WriteFile(filepath.Join(dir, n), nil, 0o644) }
	if got := filepath.Base(UniqueName(dir, "report.pdf")); got != "report.pdf" {
		t.Fatal(got)
	}
	touch("report.pdf")
	if got := filepath.Base(UniqueName(dir, "report.pdf")); got != "report (1).pdf" {
		t.Fatal(got)
	}
	touch("report (1).pdf")
	if got := filepath.Base(UniqueName(dir, "report.pdf")); got != "report (2).pdf" {
		t.Fatal(got)
	}
	touch("archive.tar.gz")
	if got := filepath.Base(UniqueName(dir, "archive.tar.gz")); got != "archive.tar (1).gz" {
		t.Fatal(got)
	}
	touch(".env")
	if got := filepath.Base(UniqueName(dir, ".env")); got != ".env (1)" {
		t.Fatal(got)
	}
}

func TestSafeName(t *testing.T) {
	for in, want := range map[string]string{
		"a:b?.txt":       "a_b_.txt",
		`..\..\evil.exe`: "evil.exe",
		"../../x":        "x",
		"CON.txt":        "_CON.txt",
		"nul":            "_nul",
		"trailing. ":     "trailing",
		"":               "file",
		"ok name.png":    "ok name.png",
	} {
		if got := SafeName(in); got != want {
			t.Errorf("SafeName(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestSaveKeepsBothAndCleansUpOnError(t *testing.T) {
	dir := t.TempDir()
	fetch := func(_ context.Context, name string, w io.Writer) (int64, error) {
		n, err := io.Copy(w, strings.NewReader("hello "+name))
		return n, err
	}
	p1, err := Save(context.Background(), fetch, dir, "a.txt")
	if err != nil {
		t.Fatal(err)
	}
	p2, err := Save(context.Background(), fetch, dir, "a.txt")
	if err != nil {
		t.Fatal(err)
	}
	if p1 == p2 || filepath.Base(p2) != "a (1).txt" {
		t.Fatalf("second copy should get a new name: %s %s", p1, p2)
	}
	if b, _ := os.ReadFile(p2); string(b) != "hello a.txt" {
		t.Fatalf("content %q", b)
	}
	bad := func(_ context.Context, _ string, w io.Writer) (int64, error) {
		w.Write([]byte("partial"))
		return 7, errors.New("connection reset")
	}
	if _, err := Save(context.Background(), bad, dir, "b.txt"); err == nil {
		t.Fatal("expected error")
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 2 {
		t.Fatalf("a failed download must leave nothing behind: %v", entries)
	}
}
