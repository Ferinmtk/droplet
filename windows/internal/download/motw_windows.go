package download

import "os"

// markFromInternet adds the Mark of the Web, as a browser download would, so
// Windows (SmartScreen, Office Protected View) treats a received file with the
// same care as one fetched from the hub's web page. Best effort.
func markFromInternet(path string) {
	_ = os.WriteFile(path+":Zone.Identifier", []byte("[ZoneTransfer]\r\nZoneId=3\r\n"), 0o644)
}
