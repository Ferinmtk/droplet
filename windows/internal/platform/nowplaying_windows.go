package platform

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"syscall"
	"time"
	"unsafe"

	ole "github.com/go-ole/go-ole"

	"github.com/Ferinmtk/droplet/windows/internal/remote"
)

// What's playing comes from Windows' media sessions
// (Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,
// the source of the volume flyout's media card), read once a second over
// WinRT. Album art is read when the track changes.

var (
	iidSessionManagerStatics = ole.NewGUID("{2050C4EE-11A0-57DE-AED7-C97C70338245}")
	iidIRandomAccessStream   = ole.NewGUID("{905A0FE1-BC53-11DF-8C49-001E4FC686DA}")
)

// Slots of the Windows.Media.Control interfaces used here.
const (
	// IGlobalSystemMediaTransportControlsSessionManagerStatics
	slotRequestAsync = 6
	// IGlobalSystemMediaTransportControlsSessionManager
	slotGetCurrentSession = 6
	slotGetSessions       = 7
	// IVectorView<T>
	slotVectorGetAt = 6
	slotVectorSize  = 7
	// IGlobalSystemMediaTransportControlsSession
	slotSourceAppUserModelID       = 6
	slotTryGetMediaPropertiesAsync = 7
	slotGetTimelineProperties      = 8
	slotGetPlaybackInfo            = 9
	// IGlobalSystemMediaTransportControlsSessionMediaProperties
	slotTitle      = 6
	slotArtist     = 9
	slotAlbumTitle = 10
	slotThumbnail  = 15
	// IGlobalSystemMediaTransportControlsSessionPlaybackInfo
	slotControls       = 6
	slotPlaybackStatus = 7
	// IGlobalSystemMediaTransportControlsSessionPlaybackControls
	slotIsNextEnabled             = 12
	slotIsPreviousEnabled         = 13
	slotIsPlaybackPositionEnabled = 20
	// IGlobalSystemMediaTransportControlsSessionTimelineProperties
	slotStartTime       = 6
	slotEndTime         = 7
	slotPosition        = 10
	slotLastUpdatedTime = 11
	// IRandomAccessStreamReference
	slotOpenReadAsync = 6
	// IRandomAccessStream
	slotStreamSize = 6
	// IStream (a classic COM interface: its methods start at slot 3)
	slotIStreamRead = 3
)

// maxArtBytes caps the album art read from an app.
const maxArtBytes = 4 << 20

type nowPlaying struct{}

// Run keeps reading the media sessions while ctx lasts. If that keeps
// failing (an old Windows without media sessions), it gives up and the
// media state carries the volume with an empty player list.
func (nowPlaying) Run(ctx context.Context, update func([]remote.Player)) {
	// this goroutine keeps its thread (and the WinRT objects made on it) to itself
	if err := startWinRT(); err != nil {
		log.Printf("now playing: %v; sending the volume only", err)
		return
	}
	fails := 0
	for ctx.Err() == nil {
		start := time.Now()
		err := readNowPlaying(ctx, update)
		if ctx.Err() != nil {
			return
		}
		update(nil)
		if time.Since(start) > time.Minute {
			fails = 0
		}
		if fails++; fails >= 3 {
			log.Printf("now playing: giving up after repeated failures (%v); sending the volume only", err)
			return
		}
		log.Printf("now playing: %v (restarting)", err)
		select {
		case <-ctx.Done():
			return
		case <-time.After(5 * time.Second):
		}
	}
}

// readNowPlaying reads the sessions once a second until ctx ends or
// reading fails, calling update when the players change.
func readNowPlaying(ctx context.Context, update func([]remote.Player)) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("panic: %v", r)
		}
	}()
	statics, err := factory("Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager", iidSessionManagerStatics)
	if err != nil {
		return err
	}
	op, err := statics.getObj(slotRequestAsync, "RequestAsync", false)
	statics.release()
	if err != nil {
		return err
	}
	mgr, err := await(op, 10*time.Second, "RequestAsync")
	if err != nil {
		return err
	}
	if mgr.p == nil {
		return errors.New("no media session manager")
	}
	defer mgr.release()

	art := newArtCache()
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	var last []byte
	for rounds := 0; ; rounds++ {
		current, sessions, err := readSessions(mgr, art)
		if err != nil {
			return fmt.Errorf("%w (after %d updates)", err, rounds)
		}
		players := remote.ToPlayers(current, sessions, art.urls)
		key, _ := json.Marshal(players)
		if string(key) != string(last) {
			last = key
			update(players)
		}
		select {
		case <-ctx.Done():
			return nil
		case <-tick.C:
		}
	}
}

// artCache remembers album art by track, so it's read once per track.
type artCache struct {
	urls  map[string]string // art key → data: URL
	tried map[string]bool   // art keys read (or failed to), not to read again
}

func newArtCache() *artCache {
	return &artCache{urls: map[string]string{}, tried: map[string]bool{}}
}

func readSessions(mgr comPtr, art *artCache) (string, []remote.SMTCSession, error) {
	current := ""
	if cur, err := mgr.getObj(slotGetCurrentSession, "GetCurrentSession", true); err == nil && cur.p != nil {
		current, _ = cur.getString(slotSourceAppUserModelID, "SourceAppUserModelId")
		cur.release()
	}
	list, err := mgr.getObj(slotGetSessions, "GetSessions", false)
	if err != nil {
		return "", nil, err
	}
	defer list.release()
	n, err := list.getInt32(slotVectorSize, "Size")
	if err != nil {
		return "", nil, err
	}
	var out []remote.SMTCSession
	for i := 0; i < int(uint32(n)) && i < 32; i++ {
		var s comPtr
		hr, _, _ := syscall.SyscallN(list.method(slotVectorGetAt), list.this(), uintptr(i), uintptr(unsafe.Pointer(&s.p)))
		if check(hr, "GetAt") != nil || s.p == nil {
			continue
		}
		// one app's session failing (it closed just now, say) doesn't stop the rest
		if sess, err := readSession(s, art); err == nil {
			out = append(out, sess)
		}
		s.release()
	}
	return current, out, nil
}

func readSession(s comPtr, art *artCache) (remote.SMTCSession, error) {
	var out remote.SMTCSession
	var err error
	if out.ID, err = s.getString(slotSourceAppUserModelID, "SourceAppUserModelId"); err != nil {
		return out, err
	}

	op, err := s.getObj(slotTryGetMediaPropertiesAsync, "TryGetMediaPropertiesAsync", false)
	if err != nil {
		return out, err
	}
	props, err := await(op, 5*time.Second, "TryGetMediaPropertiesAsync")
	if err != nil {
		return out, err
	}
	if props.p != nil {
		defer props.release()
		out.Title, _ = props.getString(slotTitle, "Title")
		out.Artist, _ = props.getString(slotArtist, "Artist")
		out.Album, _ = props.getString(slotAlbumTitle, "AlbumTitle")
		if thumb, err := props.getObj(slotThumbnail, "Thumbnail", true); err == nil && thumb.p != nil {
			out.ArtKey = out.ID + "|" + out.Title + "|" + out.Artist + "|" + out.Album
			art.load(out.ArtKey, thumb)
			thumb.release()
		}
	}

	info, err := s.getObj(slotGetPlaybackInfo, "GetPlaybackInfo", false)
	if err != nil {
		return out, err
	}
	defer info.release()
	status, err := info.getInt32(slotPlaybackStatus, "PlaybackStatus")
	if err != nil {
		return out, err
	}
	out.Status = playbackStatus(status)
	if c, err := info.getObj(slotControls, "Controls", true); err == nil && c.p != nil {
		out.CanSeek, _ = c.getBool(slotIsPlaybackPositionEnabled, "IsPlaybackPositionEnabled")
		out.CanNext, _ = c.getBool(slotIsNextEnabled, "IsNextEnabled")
		out.CanPrevious, _ = c.getBool(slotIsPreviousEnabled, "IsPreviousEnabled")
		c.release()
	}

	tl, err := s.getObj(slotGetTimelineProperties, "GetTimelineProperties", false)
	if err != nil {
		return out, err
	}
	defer tl.release()
	start, _ := tl.getInt64(slotStartTime, "StartTime")
	end, _ := tl.getInt64(slotEndTime, "EndTime")
	pos, _ := tl.getInt64(slotPosition, "Position")
	updated, _ := tl.getInt64(slotLastUpdatedTime, "LastUpdatedTime")
	out.Length, out.Position = timeline(start, end, pos, updated, out.Status == "Playing", time.Now())
	return out, nil
}

// load reads a track's album art once; art that can't be read is skipped.
func (c *artCache) load(key string, thumb comPtr) {
	if c.tried[key] {
		return
	}
	if len(c.tried) > 20 {
		c.urls, c.tried = map[string]string{}, map[string]bool{}
	}
	c.tried[key] = true
	raw, err := readThumbnail(thumb)
	if err != nil || len(raw) == 0 {
		return
	}
	if u, err := remote.ArtDataURL(raw); err == nil {
		c.urls[key] = u
	}
}

// readThumbnail reads an IRandomAccessStreamReference's bytes, through the
// classic IStream that shcore wraps around a WinRT stream.
func readThumbnail(ref comPtr) ([]byte, error) {
	op, err := ref.getObj(slotOpenReadAsync, "OpenReadAsync", false)
	if err != nil {
		return nil, err
	}
	stream, err := await(op, 5*time.Second, "OpenReadAsync")
	if err != nil {
		return nil, err
	}
	if stream.p == nil {
		return nil, errors.New("no stream")
	}
	defer stream.release()
	ras, err := stream.query(iidIRandomAccessStream)
	if err != nil {
		return nil, err
	}
	size, err := ras.getInt64(slotStreamSize, "Size")
	ras.release()
	if err != nil {
		return nil, err
	}
	if size <= 0 || size > maxArtBytes {
		return nil, fmt.Errorf("art of %d bytes", size)
	}
	var is comPtr
	hr, _, _ := pCreateStreamOverRandomAccessStream.Call(stream.this(), uintptr(unsafe.Pointer(iidIStream)), uintptr(unsafe.Pointer(&is.p)))
	if err := check(hr, "CreateStreamOverRandomAccessStream"); err != nil {
		return nil, err
	}
	defer is.release()
	buf := make([]byte, size)
	got := 0
	for got < len(buf) {
		var n uint32
		// IStream::Read(pv, cb, pcbRead); S_FALSE with fewer bytes at the end
		hr, _, _ := syscall.SyscallN(is.method(slotIStreamRead), is.this(),
			uintptr(unsafe.Pointer(&buf[got])), uintptr(len(buf)-got), uintptr(unsafe.Pointer(&n)))
		if err := check(hr, "Read"); err != nil {
			return nil, err
		}
		if n == 0 {
			break
		}
		got += int(n)
	}
	return buf[:got], nil
}
