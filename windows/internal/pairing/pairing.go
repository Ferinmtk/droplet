// Package pairing follows a LAN join request (docs/local-first.md §4): a
// new device names itself with POST /api/device, gets a four-digit code and
// waits, polling GET /api/me, until the owner allows it from one of their
// devices (the "pending" flag goes) or denies it (the device is gone).
package pairing

import (
	"context"
	"errors"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/hub"
)

// State is where a join request stands.
type State string

const (
	// Pending: waiting for the owner to allow it.
	Pending State = "pending"
	// Approved: let in; the token now works everywhere.
	Approved State = "approved"
	// Declined: denied, or the request expired (the hub drops unanswered
	// requests after a day). The token is dead.
	Declined State = "declined"
	// Unknown: no answer this time (network trouble); ask again.
	Unknown State = "unknown"
)

// Every is how often to ask (the docs say every 2–3 s).
const Every = 2500 * time.Millisecond

// Evaluate reads one /api/me answer. code is the four digits, while pending.
func Evaluate(me *hub.Me, err error) (s State, code string) {
	switch {
	case err != nil:
		return Unknown, ""
	case me == nil:
		return Unknown, ""
	case me.Device == nil:
		return Declined, ""
	case me.Device.Pending:
		return Pending, me.Device.Code
	}
	return Approved, ""
}

// Done reports whether s ends the wait.
func (s State) Done() bool { return s == Approved || s == Declined }

// Wait asks me every interval until the request is approved or declined,
// or ctx ends. update, if set, hears every state change (and a new code).
func Wait(ctx context.Context, me func(context.Context) (*hub.Me, error), every time.Duration,
	update func(s State, code string)) (State, error) {
	last, lastCode := State(""), ""
	for {
		actx, cancel := context.WithTimeout(ctx, 15*time.Second)
		m, err := me(actx)
		cancel()
		if ctx.Err() != nil {
			return last, ctx.Err()
		}
		s, code := Evaluate(m, err)
		if s == Unknown && last != "" {
			s = last // a hiccup doesn't change where the request stands
			code = lastCode
		}
		if (s != last || code != lastCode) && update != nil {
			update(s, code)
		}
		last, lastCode = s, code
		if s.Done() {
			return s, nil
		}
		t := time.NewTimer(every)
		select {
		case <-ctx.Done():
			t.Stop()
			return last, ctx.Err()
		case <-t.C:
		}
	}
}

// ErrDeclined is a join request that was denied or expired.
var ErrDeclined = errors.New("the request to join was declined (or expired)")
