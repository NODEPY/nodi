//go:build windows

package transfer

import (
	"os"
	"syscall"
	"unsafe"
)

const (
	lockfileExclusiveLock   = 2
	lockfileFailImmediately = 1
)

var procLockFileEx = syscall.NewLazyDLL("kernel32.dll").NewProc("LockFileEx")

func lockExclusive(f *os.File) error {
	var ol syscall.Overlapped
	r, _, err := procLockFileEx.Call(f.Fd(), lockfileExclusiveLock|lockfileFailImmediately, 0, 1, 0, uintptr(unsafe.Pointer(&ol)))
	if r == 0 {
		if err != nil {
			return err
		}
		return syscall.EINVAL
	}
	return nil
}
