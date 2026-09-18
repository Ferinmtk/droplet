// Package hub is a client for a droplet hub's HTTP API.
package hub

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	deviceCookie  = "droplet_device"
	sessionCookie = "session"
)

var (
	// ErrPINRequired means the hub redirected to /login: it wants a PIN.
	ErrPINRequired = errors.New("this hub asks for a PIN")
	// ErrWrongPIN means /login rejected the PIN.
	ErrWrongPIN = errors.New("wrong PIN")
	// ErrRingUnsupported means the hub predates ringing.
	ErrRingUnsupported = errors.New("ring not supported by this hub")
	// ErrNotFound is a plain 404 (unknown device, file already gone, ...).
	ErrNotFound = errors.New("not found on the hub")
	// ErrNotRegistered means the hub doesn't know this device's cookie.
	ErrNotRegistered = errors.New("this PC isn't registered with the hub (open Settings)")
)

// NameTakenError is the hub's 409 when registering a name that exists.
type NameTakenError struct{ Msg string }

func (e *NameTakenError) Error() string { return e.Msg }

// StatusError is any other unexpected HTTP status.
type StatusError struct {
	Code int
	Msg  string
}

func (e *StatusError) Error() string {
	if e.Msg != "" {
		return fmt.Sprintf("hub said %d: %s", e.Code, e.Msg)
	}
	return fmt.Sprintf("hub said %d %s", e.Code, http.StatusText(e.Code))
}

// Client talks to one hub as one device. Cookies are kept by hand rather
// than in a jar: they are the device's persistent identity and live in the config.
type Client struct {
	Base    *url.URL
	Token   string // droplet_device cookie
	Session string // Flask session cookie from a PIN login
	HTTP    *http.Client
	// Transfer is used for uploads and downloads, which have no overall timeout.
	Transfer *http.Client
}

// New makes a client for the hub at base (e.g. https://t15.tail7375fe.ts.net).
func New(base, token, session string) (*Client, error) {
	u, err := ParseHubURL(base)
	if err != nil {
		return nil, err
	}
	noRedirect := func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	return &Client{
		Base:     u,
		Token:    token,
		Session:  session,
		HTTP:     &http.Client{Timeout: 20 * time.Second, CheckRedirect: noRedirect},
		Transfer: &http.Client{CheckRedirect: noRedirect},
	}, nil
}

// ParseHubURL validates and normalises a hub URL typed by a person.
func ParseHubURL(s string) (*url.URL, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil, errors.New("hub URL is empty")
	}
	if !strings.Contains(s, "://") {
		s = "https://" + s
	}
	u, err := url.Parse(s)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, fmt.Errorf("%q isn't a hub URL (expected e.g. %s)", s, "https://t15.tail7375fe.ts.net")
	}
	u.Path = strings.TrimRight(u.Path, "/")
	u.RawQuery, u.Fragment = "", ""
	return u, nil
}

// HubName is a short name for the hub, e.g. "t15" for t15.tail7375fe.ts.net.
func (c *Client) HubName() string {
	h := c.Base.Hostname()
	if i := strings.IndexByte(h, '.'); i > 0 && !isIP(h) {
		return h[:i]
	}
	return h
}

func isIP(h string) bool {
	return strings.Trim(h, "0123456789.") == "" || strings.Contains(h, ":")
}

// URL returns the absolute URL for a hub path such as "/api/files".
func (c *Client) URL(path string) string {
	return c.Base.String() + path
}

func (c *Client) newRequest(ctx context.Context, method, path string, body io.Reader) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, method, c.URL(path), body)
	if err != nil {
		return nil, err
	}
	var cookies []string
	if c.Token != "" {
		cookies = append(cookies, deviceCookie+"="+c.Token)
	}
	if c.Session != "" {
		cookies = append(cookies, sessionCookie+"="+c.Session)
	}
	if len(cookies) > 0 {
		req.Header.Set("Cookie", strings.Join(cookies, "; "))
	}
	if c.Token != "" {
		// how helpers identify themselves (docs/remote.md); the cookie above
		// is kept for hubs from before bearer tokens
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}
	req.Header.Set("User-Agent", "droplet-windows/1 (Windows)")
	return req, nil
}

// do sends req and turns redirects-to-login and error statuses into errors.
// On success the caller owns resp.Body.
func (c *Client) do(hc *http.Client, req *http.Request) (*http.Response, error) {
	resp, err := hc.Do(req)
	if err != nil {
		return nil, err
	}
	c.absorbCookies(resp)
	if resp.StatusCode >= 300 && resp.StatusCode < 400 {
		resp.Body.Close()
		if strings.Contains(resp.Header.Get("Location"), "/login") {
			return nil, ErrPINRequired
		}
		return nil, &StatusError{Code: resp.StatusCode, Msg: "unexpected redirect to " + resp.Header.Get("Location")}
	}
	if resp.StatusCode >= 400 {
		defer resp.Body.Close()
		msg := errorMessage(resp)
		switch resp.StatusCode {
		case http.StatusConflict:
			return nil, &NameTakenError{Msg: msg}
		case http.StatusNotFound:
			return nil, ErrNotFound
		}
		return nil, &StatusError{Code: resp.StatusCode, Msg: msg}
	}
	return resp, nil
}

func errorMessage(resp *http.Response) string {
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	var body struct {
		Error string `json:"error"`
	}
	if json.Unmarshal(data, &body) == nil && body.Error != "" {
		return body.Error
	}
	return ""
}

// absorbCookies keeps a new device or session cookie if the hub sets one.
func (c *Client) absorbCookies(resp *http.Response) {
	for _, ck := range resp.Cookies() {
		switch ck.Name {
		case deviceCookie:
			if ck.MaxAge >= 0 && ck.Value != "" {
				c.Token = ck.Value
			}
		case sessionCookie:
			if ck.MaxAge >= 0 && ck.Value != "" {
				c.Session = ck.Value
			}
		}
	}
}

func (c *Client) getJSON(ctx context.Context, path string, out any) error {
	req, err := c.newRequest(ctx, http.MethodGet, path, nil)
	if err != nil {
		return err
	}
	resp, err := c.do(c.HTTP, req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return decode(resp, out)
}

func decode(resp *http.Response, out any) error {
	if out == nil {
		_, _ = io.Copy(io.Discard, resp.Body)
		return nil
	}
	if ct := resp.Header.Get("Content-Type"); !strings.Contains(ct, "json") {
		return fmt.Errorf("hub sent %q where JSON was expected — is the hub URL right?", ct)
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

func (c *Client) post(ctx context.Context, path, contentType string, body io.Reader, out any) error {
	req, err := c.newRequest(ctx, http.MethodPost, path, body)
	if err != nil {
		return err
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	resp, err := c.do(c.HTTP, req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return decode(resp, out)
}

func (c *Client) postForm(ctx context.Context, path string, form url.Values, out any) error {
	return c.post(ctx, path, "application/x-www-form-urlencoded", strings.NewReader(form.Encode()), out)
}

// --- API types ---------------------------------------------------------------

// Device is a named device as seen in /api/files and /api/me.
type Device struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Online bool   `json:"online"`
	Push   bool   `json:"push"`
	Self   bool   `json:"self"`
}

// File is an item in a folder listing.
type File struct {
	Name  string `json:"name"`
	Size  int64  `json:"size"`
	Mtime int64  `json:"mtime"`
	Image bool   `json:"image"`
	From  string `json:"from,omitempty"`
}

// Files is GET /api/files.
type Files struct {
	Received []File         `json:"received"`
	Shared   []File         `json:"shared"`
	Inbox    []File         `json:"inbox"`
	Devices  []Device       `json:"devices"`
	Unread   map[string]int `json:"unread"`
}

// Self returns this device from the listing, if registered.
func (f *Files) Self() *Device {
	for i := range f.Devices {
		if f.Devices[i].Self {
			return &f.Devices[i]
		}
	}
	return nil
}

// Others are the devices other than this one.
func (f *Files) Others() []Device {
	var out []Device
	for _, d := range f.Devices {
		if !d.Self {
			out = append(out, d)
		}
	}
	return out
}

// Me is GET /api/me.
type Me struct {
	Device    *Device `json:"device"`
	Suggested *string `json:"suggested"`
	HubURL    *string `json:"hub_url"`
}

// Message is one chat message.
type Message struct {
	ID   string  `json:"id"`
	From string  `json:"from"`
	To   string  `json:"to"`
	Text string  `json:"text"`
	TS   float64 `json:"ts"`
}

// Ring is an active ring aimed at this device.
type Ring struct {
	ID   string  `json:"id"`
	From string  `json:"from"`
	TS   float64 `json:"ts"`
}

// --- endpoints ---------------------------------------------------------------

// Me fetches /api/me.
func (c *Client) Me(ctx context.Context) (*Me, error) {
	var me Me
	return &me, c.getJSON(ctx, "/api/me", &me)
}

// Files fetches /api/files (which also marks this device online).
func (c *Client) Files(ctx context.Context) (*Files, error) {
	var f Files
	if err := c.getJSON(ctx, "/api/files", &f); err != nil {
		return nil, err
	}
	return &f, nil
}

// Login posts the PIN; on success the session cookie is kept in c.Session.
func (c *Client) Login(ctx context.Context, pin string) error {
	req, err := c.newRequest(ctx, http.MethodPost, "/login", strings.NewReader(url.Values{"pin": {pin}}.Encode()))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	c.absorbCookies(resp)
	// success redirects home; a wrong PIN re-renders the form with 200
	if resp.StatusCode >= 300 && resp.StatusCode < 400 {
		return nil
	}
	return ErrWrongPIN
}

// Register names this device (or renames it, when already registered).
// A new registration's cookie is kept in c.Token.
func (c *Client) Register(ctx context.Context, name string) (*Device, error) {
	body, _ := json.Marshal(map[string]string{"name": name})
	var d Device
	if err := c.post(ctx, "/api/device", "application/json", strings.NewReader(string(body)), &d); err != nil {
		return nil, err
	}
	if c.Token == "" {
		return nil, errors.New("the hub didn't hand out a device cookie")
	}
	return &d, nil
}

// Linked is the hub's answer to a link code: the device this app now
// belongs to, and its own token for it.
type Linked struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Token string `json:"token"`
}

// ErrBadLinkCode is the hub refusing a link code (wrong, used or expired).
var ErrBadLinkCode = errors.New("that code is wrong or has expired: make a new one")

// Link trades a six-digit code from "Set up remote control of this device"
// for a token on that device, so this app and the PC's browser are one
// device on the hub. The new token replaces c.Token.
func (c *Client) Link(ctx context.Context, code, client string) (*Linked, error) {
	body, _ := json.Marshal(map[string]string{"code": code, "client": client})
	// a token from an earlier registration mustn't come along: the hub
	// doesn't need it, and it would be the identity for this request
	c.Token = ""
	var out Linked
	err := c.post(ctx, "/api/device/link", "application/json", strings.NewReader(string(body)), &out)
	var se *StatusError
	switch {
	case errors.As(err, &se) && se.Code == http.StatusForbidden:
		return nil, ErrBadLinkCode
	case errors.Is(err, ErrNotFound):
		return nil, errors.New("this hub can't link apps yet (update the hub)")
	case err != nil:
		return nil, err
	case out.Token == "" || out.ID == "":
		return nil, errors.New("the hub's answer had no device token")
	}
	c.Token = out.Token
	return &out, nil
}

// RemoveDevice removes a device from the hub (as the web app's Devices list does).
func (c *Client) RemoveDevice(ctx context.Context, id string) error {
	return c.post(ctx, "/api/device/"+url.PathEscape(id)+"/remove", "", nil, nil)
}

// Chat fetches the thread with another device (and marks it read on the hub).
func (c *Client) Chat(ctx context.Context, deviceID string) ([]Message, error) {
	var out struct {
		Messages []Message `json:"messages"`
	}
	if err := c.getJSON(ctx, "/api/chat/"+url.PathEscape(deviceID), &out); err != nil {
		return nil, err
	}
	return out.Messages, nil
}

// SendText sends a chat message to a device, or saves a text file on the hub when to is "hub".
func (c *Client) SendText(ctx context.Context, to, text string) error {
	err := c.postForm(ctx, "/text", url.Values{"text": {text}, "to": {to}}, nil)
	if errors.Is(err, ErrNotFound) {
		return fmt.Errorf("no device %q on the hub", to)
	}
	var se *StatusError
	if errors.As(err, &se) && se.Code == 400 && to != "hub" && c.Token == "" {
		return ErrNotRegistered
	}
	return err
}

// Delete removes a file from this device's inbox on the hub.
func (c *Client) DeleteInbox(ctx context.Context, name string) error {
	return c.post(ctx, "/delete/inbox/"+url.PathEscape(name), "", nil, nil)
}

// Download streams an inbox file into w.
func (c *Client) Download(ctx context.Context, name string, w io.Writer) (int64, error) {
	req, err := c.newRequest(ctx, http.MethodGet, "/d/inbox/"+url.PathEscape(name), nil)
	if err != nil {
		return 0, err
	}
	resp, err := c.do(c.Transfer, req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	n, err := io.Copy(w, resp.Body)
	if err == nil && resp.ContentLength >= 0 && n != resp.ContentLength {
		err = fmt.Errorf("download of %s stopped at %d of %d bytes", name, n, resp.ContentLength)
	}
	return n, err
}

// Progress is called during uploads with bytes sent so far and the total.
type Progress func(sent, total int64)

// Upload sends files to a device (by id) or to the hub ("hub").
// It streams from disk, so file size is limited only by the hub.
func (c *Client) Upload(ctx context.Context, to string, paths []string, progress Progress) ([]string, error) {
	var total int64
	for _, p := range paths {
		st, err := os.Stat(p)
		if err != nil {
			return nil, err
		}
		if st.IsDir() {
			return nil, fmt.Errorf("%s is a folder; droplet sends files (zip the folder first)", filepath.Base(p))
		}
		total += st.Size()
	}
	pr, pw := io.Pipe()
	mw := multipart.NewWriter(pw)
	go func() {
		err := func() error {
			var sent int64
			for _, p := range paths {
				f, err := os.Open(p)
				if err != nil {
					return err
				}
				part, err := mw.CreateFormFile("files", filepath.Base(p))
				if err == nil {
					_, err = io.Copy(part, &countingReader{r: f, n: &sent, total: total, fn: progress})
				}
				f.Close()
				if err != nil {
					return err
				}
			}
			return mw.Close()
		}()
		pw.CloseWithError(err)
	}()
	req, err := c.newRequest(ctx, http.MethodPost, "/upload?to="+url.QueryEscape(to), pr)
	if err != nil {
		pr.Close()
		return nil, err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	resp, err := c.do(c.Transfer, req)
	if err != nil {
		pr.CloseWithError(err)
		if errors.Is(err, ErrNotFound) {
			return nil, fmt.Errorf("no device %q on the hub", to)
		}
		var se *StatusError
		if errors.As(err, &se) && se.Code == http.StatusRequestEntityTooLarge {
			return nil, errors.New("too big for this hub (see DROPLET_MAX_MB on the hub)")
		}
		return nil, err
	}
	defer resp.Body.Close()
	var out struct {
		Saved []string `json:"saved"`
	}
	if err := decode(resp, &out); err != nil {
		return nil, err
	}
	return out.Saved, nil
}

// UploadData sends one in-memory file (a screenshot, say) to a device or the hub.
func (c *Client) UploadData(ctx context.Context, to, name string, data []byte) error {
	var body strings.Builder
	mw := multipart.NewWriter(&body)
	part, err := mw.CreateFormFile("files", name)
	if err != nil {
		return err
	}
	if _, err := part.Write(data); err != nil {
		return err
	}
	if err := mw.Close(); err != nil {
		return err
	}
	req, err := c.newRequest(ctx, http.MethodPost, "/upload?to="+url.QueryEscape(to), strings.NewReader(body.String()))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	resp, err := c.do(c.Transfer, req)
	if errors.Is(err, ErrNotFound) {
		return fmt.Errorf("no device %q on the hub", to)
	}
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	var out struct {
		Saved []string `json:"saved"`
	}
	if err := decode(resp, &out); err != nil {
		return err
	}
	if len(out.Saved) == 0 {
		return errors.New("the hub didn't save the file")
	}
	return nil
}

type countingReader struct {
	r     io.Reader
	n     *int64
	total int64
	fn    Progress
}

func (c *countingReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	*c.n += int64(n)
	if c.fn != nil && n > 0 {
		c.fn(*c.n, c.total)
	}
	return n, err
}

// --- ringing -------------------------------------------------------------------

// ActiveRing returns the ring aimed at this device, or nil.
// ErrRingUnsupported means the hub has no ring endpoints.
func (c *Client) ActiveRing(ctx context.Context) (*Ring, error) {
	var out struct {
		Ring *Ring `json:"ring"`
	}
	err := c.getJSON(ctx, "/api/ring", &out)
	if errors.Is(err, ErrNotFound) {
		return nil, ErrRingUnsupported
	}
	return out.Ring, err
}

// StopRing silences the ring aimed at this device.
func (c *Client) StopRing(ctx context.Context) error {
	err := c.post(ctx, "/api/ring/stop", "", nil, nil)
	if errors.Is(err, ErrNotFound) {
		return ErrRingUnsupported
	}
	return err
}

// RingDevice rings another device, or the hub when target is "hub".
func (c *Client) RingDevice(ctx context.Context, target string) error {
	// a 404 here can mean "no ringing" or "no such device"; ask which
	if _, err := c.ActiveRing(ctx); errors.Is(err, ErrRingUnsupported) {
		return err
	}
	path := "/api/device/" + url.PathEscape(target) + "/ring"
	if target == "hub" {
		path = "/api/hub/ring"
	}
	err := c.post(ctx, path, "", nil, nil)
	if errors.Is(err, ErrNotFound) {
		return fmt.Errorf("no device %q on the hub", target)
	}
	return err
}

// Resolve turns a name, id or "hub" into a destination id, using the device list.
func Resolve(target string, devices []Device) (id, name string, err error) {
	t := strings.TrimSpace(target)
	if strings.EqualFold(t, "hub") {
		return "hub", "the hub", nil
	}
	for _, d := range devices {
		if d.ID == t {
			return d.ID, d.Name, nil
		}
	}
	for _, d := range devices {
		if strings.EqualFold(d.Name, t) {
			return d.ID, d.Name, nil
		}
	}
	var names []string
	for _, d := range devices {
		if !d.Self {
			names = append(names, d.Name)
		}
	}
	if len(names) == 0 {
		return "", "", fmt.Errorf("no device called %q (the hub has no other devices; use \"hub\")", t)
	}
	return "", "", fmt.Errorf("no device called %q; try one of: hub, %s", t, strings.Join(names, ", "))
}
