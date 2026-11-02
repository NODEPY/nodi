package transfer

import (
	"errors"
	"math"
)

const spaceMargin = 1 << 20

var diskFree = availableBytes

func ensureSpace(dir string, size int64) error {
	if size < 0 {
		return errors.New("invalid file size")
	}
	need := size
	if spaceMargin > math.MaxInt64-size {
		need = math.MaxInt64
	} else {
		need = size + spaceMargin
	}
	free, err := diskFree(dir)
	if err != nil {
		return errors.New("cannot check free space")
	}
	if free < need {
		return errors.New("not enough free space for this file")
	}
	return nil
}
