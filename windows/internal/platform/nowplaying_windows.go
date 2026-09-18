package platform

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"os/exec"
	"sync"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"

	"github.com/Ferinmtk/droplet/windows/internal/remote"
)

// What's playing comes from Windows' media sessions
// (GlobalSystemMediaTransportControlsSessionManager, the source of the
// volume flyout's media card). That's a WinRT API, which Go can't reach
// without cgo, so a small PowerShell loop reads it and prints one JSON line
// a second; album art comes as a separate line when the track changes.
//
// The loop dies with droplet: it's in a kill-on-close job object, and it
// exits when its output pipe breaks.

const nowPlayingScript = `
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
$out = [Console]::Out
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
$asTask = @([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation` + "`" + `1' })[0]
function Await($op, [Type]$type) {
  $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
  if (-not $t.Wait(5000)) { throw 'timed out' }
  $t.Result
}
$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
$sent = @{}
while ($true) {
  $players = New-Object System.Collections.ArrayList
  $cur = $mgr.GetCurrentSession()
  $curId = ''
  if ($cur) { $curId = $cur.SourceAppUserModelId }
  foreach ($s in $mgr.GetSessions()) {
    try {
      $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
      $info = $s.GetPlaybackInfo()
      $tl = $s.GetTimelineProperties()
      $status = [string]$info.PlaybackStatus
      $len = ($tl.EndTime - $tl.StartTime).TotalSeconds
      $pos = ($tl.Position - $tl.StartTime).TotalSeconds
      if ($status -eq 'Playing' -and $tl.LastUpdatedTime.Year -gt 2000) {
        $pos += ([DateTimeOffset]::Now - $tl.LastUpdatedTime).TotalSeconds
      }
      $key = ''
      if ($p.Thumbnail) {
        $key = [string]$s.SourceAppUserModelId + '|' + $p.Title + '|' + $p.Artist + '|' + $p.AlbumTitle
        if (-not $sent.ContainsKey($key)) {
          if ($sent.Count -gt 20) { $sent.Clear() }
          $sent[$key] = $true
          try {
            $ras = Await ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
            $st = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($ras)
            $ms = New-Object System.IO.MemoryStream
            $st.CopyTo($ms)
            $st.Dispose()
            if ($ms.Length -gt 0 -and $ms.Length -le 4MB) {
              $out.WriteLine((ConvertTo-Json -Compress @{ art_key = $key; art = [Convert]::ToBase64String($ms.ToArray()) }))
            }
          } catch { }
        }
      }
      [void]$players.Add(@{
        id = [string]$s.SourceAppUserModelId; status = $status
        title = [string]$p.Title; artist = [string]$p.Artist; album = [string]$p.AlbumTitle
        position = [double]$pos; length = [double]$len
        can_seek = [bool]$info.Controls.IsPlaybackPositionEnabled
        can_next = [bool]$info.Controls.IsNextEnabled
        can_previous = [bool]$info.Controls.IsPreviousEnabled
        art_key = $key })
    } catch { }
  }
  $out.WriteLine((ConvertTo-Json -Compress -Depth 4 @{ current = $curId; players = $players.ToArray() }))
  $out.Flush()
  Start-Sleep -Milliseconds 1000
}
`

type nowPlaying struct{}

// Run keeps the helper going while ctx lasts. If it keeps failing (an old
// Windows without media sessions, PowerShell blocked by policy), it gives
// up and the media state carries the volume with an empty player list.
func (nowPlaying) Run(ctx context.Context, update func([]remote.Player)) {
	fails := 0
	for ctx.Err() == nil {
		start := time.Now()
		err := runNowPlaying(ctx, update)
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

func runNowPlaying(ctx context.Context, update func([]remote.Player)) error {
	u := utf16.Encode([]rune(nowPlayingScript))
	b := make([]byte, len(u)*2)
	for i, c := range u {
		b[2*i], b[2*i+1] = byte(c), byte(c>>8)
	}
	cmd := exec.CommandContext(ctx, powershellPath(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
		"-WindowStyle", "Hidden", "-EncodedCommand", base64.StdEncoding.EncodeToString(b))
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return err
	}
	adoptChild(cmd.Process.Pid)

	art := map[string]string{}
	sc := bufio.NewScanner(stdout)
	sc.Buffer(make([]byte, 64<<10), 8<<20)
	lines := 0
	var last []byte
	for sc.Scan() {
		line := bytes.TrimPrefix(sc.Bytes(), []byte("\xef\xbb\xbf"))
		var l remote.SMTCLine
		if json.Unmarshal(line, &l) != nil {
			continue
		}
		if l.ArtKey != "" && l.Art != "" {
			if u, err := remote.ArtDataURL(l.Art); err == nil {
				if len(art) > 20 {
					art = map[string]string{}
				}
				art[l.ArtKey] = u
			}
			last = nil // re-send the players with their art
			continue
		}
		ss, err := l.Sessions()
		if err != nil {
			continue
		}
		lines++
		players := remote.ToPlayers(l.Current, ss, art)
		key, _ := json.Marshal(players)
		if !bytes.Equal(key, last) {
			last = key
			update(players)
		}
	}
	err = cmd.Wait()
	if msg := bytes.TrimSpace(stderr.Bytes()); len(msg) > 0 {
		if len(msg) > 300 {
			msg = msg[:300]
		}
		return fmt.Errorf("the PowerShell helper stopped after %d updates: %s", lines, msg)
	}
	if err == nil {
		return fmt.Errorf("the PowerShell helper exited after %d updates", lines)
	}
	return fmt.Errorf("the PowerShell helper stopped after %d updates: %v", lines, err)
}

var (
	jobOnce sync.Once
	job     windows.Handle
)

// adoptChild puts a child process in a job that Windows kills along with
// droplet, so a crash can't leave the helper running.
func adoptChild(pid int) {
	jobOnce.Do(func() {
		h, err := windows.CreateJobObject(nil, nil)
		if err != nil {
			return
		}
		info := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{}
		info.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
		if _, err := windows.SetInformationJobObject(h, windows.JobObjectExtendedLimitInformation,
			uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info))); err != nil {
			windows.CloseHandle(h)
			return
		}
		job = h // held open for droplet's lifetime
	})
	if job == 0 {
		return
	}
	p, err := windows.OpenProcess(windows.PROCESS_SET_QUOTA|windows.PROCESS_TERMINATE, false, uint32(pid))
	if err != nil {
		return
	}
	defer windows.CloseHandle(p)
	windows.AssignProcessToJobObject(job, p)
}
