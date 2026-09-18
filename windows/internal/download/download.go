// Package download saves inbox files into the download folder without
// overwriting anything already there.
package download

import (
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// Fetcher streams a named inbox file into w (hub.Client.Download).
type Fetcher func(ctx context.Context, name string, w io.Writer) (int64, error)

// windowsReserved are device names Windows won't let a file be called, whatever the extension.
var windowsReserved = map[string]bool{
	"CON": true, "PRN": true, "AUX": true, "NUL": true,
	"COM1": true, "COM2": true, "COM3": true, "COM4": true, "COM5": true, "COM6": true, "COM7": true, "COM8": true, "COM9": true,
	"LPT1": true, "LPT2": true, "LPT3": true, "LPT4": true, "LPT5": true, "LPT6": true, "LPT7": true, "LPT8": true, "LPT9": true,
}

// SafeName makes a hub filename safe to create on Windows (and anywhere else).
func SafeName(name string) string {
	name = filepath.Base(strings.ReplaceAll(name, "\\", "/"))
	name = strings.Map(func(r rune) rune {
		if r < 32 || strings.ContainsRune(`<>:"/\|?*`, r) {
			return '_'
		}
		return r
	}, name)
	name = strings.TrimRight(name, ". ")
	if name == "" || name == "." || name == ".." {
		name = "file"
	}
	stem := name
	if i := strings.IndexByte(stem, '.'); i >= 0 {
		stem = stem[:i]
	}
	if windowsReserved[strings.ToUpper(stem)] {
		name = "_" + name
	}
	return name
}

// UniqueName returns a path in dir for name that doesn't exist yet, adding
// " (1)", " (2)", ... before the extension like Windows Explorer does.
func UniqueName(dir, name string) string {
	name = SafeName(name)
	p := filepath.Join(dir, name)
	if !exists(p) {
		return p
	}
	ext := filepath.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	if ext == name { // ".bashrc"-style names have no stem
		stem, ext = name, ""
	}
	for i := 1; ; i++ {
		p = filepath.Join(dir, fmt.Sprintf("%s (%d)%s", stem, i, ext))
		if !exists(p) {
			return p
		}
	}
}

func exists(p string) bool {
	_, err := os.Lstat(p)
	return err == nil
}

// Save downloads name into dir under a unique name. The data goes to a
// temporary file first, so a half-finished download never looks complete.
func Save(ctx context.Context, fetch Fetcher, dir, name string) (string, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	tmp, err := os.CreateTemp(dir, ".droplet-*.part")
	if err != nil {
		return "", err
	}
	_, err = fetch(ctx, name, tmp)
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		os.Remove(tmp.Name())
		return "", err
	}
	dest := UniqueName(dir, name)
	if err := os.Rename(tmp.Name(), dest); err != nil {
		os.Remove(tmp.Name())
		return "", err
	}
	_ = os.Chmod(dest, 0o644) // temp files start owner-only
	markFromInternet(dest)
	return dest, nil
}
