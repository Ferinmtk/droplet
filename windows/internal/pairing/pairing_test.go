package pairing

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/Ferinmtk/droplet/windows/internal/hub"
)

func pending(code string) *hub.Me {
	return &hub.Me{Device: &hub.Device{ID: "d1", Name: "maryanne", Pending: true, Code: code}}
}

var (
	approved = &hub.Me{Device: &hub.Device{ID: "d1", Name: "maryanne"}}
	declined = &hub.Me{}
	netErr   = errors.New("dial tcp: connection refused")
)

func TestEvaluate(t *testing.T) {
	for _, c := range []struct {
		me   *hub.Me
		err  error
		want State
		code string
	}{
		{pending("0421"), nil, Pending, "0421"},
		{approved, nil, Approved, ""},
		{declined, nil, Declined, ""},
		{nil, netErr, Unknown, ""},
		{nil, nil, Unknown, ""},
	} {
		s, code := Evaluate(c.me, c.err)
		if s != c.want || code != c.code {
			t.Errorf("Evaluate(%+v, %v) = %s %q, want %s %q", c.me, c.err, s, code, c.want, c.code)
		}
	}
}

// script answers /api/me from a list, then repeats the last answer.
type script struct {
	mu    sync.Mutex
	steps []step
	n     int
}

type step struct {
	me  *hub.Me
	err error
}

func (s *script) me(context.Context) (*hub.Me, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	st := s.steps[min(s.n, len(s.steps)-1)]
	s.n++
	return st.me, st.err
}

func run(t *testing.T, steps ...step) (State, []string) {
	t.Helper()
	sc := &script{steps: steps}
	var seen []string
	s, err := Wait(context.Background(), sc.me, time.Millisecond, func(s State, code string) {
		seen = append(seen, string(s)+":"+code)
	})
	if err != nil {
		t.Fatal(err)
	}
	return s, seen
}

func TestWaitPendingThenApproved(t *testing.T) {
	s, seen := run(t, step{pending("0421"), nil}, step{pending("0421"), nil}, step{nil, netErr},
		step{pending("0421"), nil}, step{approved, nil})
	if s != Approved {
		t.Fatalf("ended %s", s)
	}
	// the network hiccup in between isn't reported as a change
	want := []string{"pending:0421", "approved:"}
	if len(seen) != len(want) || seen[0] != want[0] || seen[1] != want[1] {
		t.Fatalf("updates: %v, want %v", seen, want)
	}
}

func TestWaitPendingThenDeclined(t *testing.T) {
	s, seen := run(t, step{pending("7156"), nil}, step{declined, nil})
	if s != Declined || len(seen) != 2 || seen[1] != "declined:" {
		t.Fatalf("ended %s, updates %v", s, seen)
	}
}

func TestWaitAlreadyApproved(t *testing.T) {
	// a PIN sign-in lets the device in at once
	if s, _ := run(t, step{approved, nil}); s != Approved {
		t.Fatalf("ended %s", s)
	}
}

func TestWaitStartsOffline(t *testing.T) {
	s, seen := run(t, step{nil, netErr}, step{nil, netErr}, step{pending("0001"), nil}, step{approved, nil})
	if s != Approved || seen[0] != "unknown:" {
		t.Fatalf("ended %s, updates %v", s, seen)
	}
}

func TestWaitCancelled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	sc := &script{steps: []step{{pending("0421"), nil}}}
	done := make(chan State)
	go func() {
		s, _ := Wait(ctx, sc.me, 5*time.Millisecond, nil)
		done <- s
	}()
	time.Sleep(30 * time.Millisecond)
	cancel()
	select {
	case s := <-done:
		if s != Pending {
			t.Fatalf("ended %s", s)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Wait didn't stop")
	}
}
