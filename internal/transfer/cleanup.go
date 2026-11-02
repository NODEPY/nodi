package transfer

import (
	"os"
	"path/filepath"
	"strings"
	"time"
)

const StalePartAge = time.Hour

// RemoveStaleParts deletes Nodi's own leftover .nodi-*.part files older than age.
// Names from other programs are left untouched.
func RemoveStaleParts(inbox string, age time.Duration) error {
	matches, err := filepath.Glob(filepath.Join(inbox, ".nodi-*.part"))
	if err != nil {
		return err
	}
	cutoff := time.Now().Add(-age)
	for _, path := range matches {
		base := filepath.Base(path)
		if !strings.HasPrefix(base, ".nodi-") || !strings.HasSuffix(base, ".part") || strings.ContainsRune(base, os.PathSeparator) {
			continue
		}
		info, err := os.Lstat(path)
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		if !info.ModTime().Before(cutoff) {
			continue
		}
		_ = os.Remove(path)
	}
	return nil
}
