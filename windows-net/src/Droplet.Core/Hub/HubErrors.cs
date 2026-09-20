namespace Droplet.Core.Hub;

/// <summary>Anything the hub answered that isn't success.</summary>
public class HubException : Exception
{
    /// <summary>Creates one.</summary>
    public HubException() { }

    /// <summary>Creates one with a message.</summary>
    public HubException(string message) : base(message) { }

    /// <summary>Creates one with a message and a cause.</summary>
    public HubException(string message, Exception inner) : base(message, inner) { }
}

/// <summary>The hub redirected to <c>/login</c>: it wants a PIN.</summary>
public sealed class PinRequiredException() : HubException("this hub asks for a PIN");

/// <summary><c>/login</c> rejected the PIN.</summary>
public sealed class WrongPinException() : HubException("wrong PIN");

/// <summary>The hub predates ringing.</summary>
public sealed class RingUnsupportedException() : HubException("ring not supported by this hub");

/// <summary>A plain 404: an unknown device, a file already gone...</summary>
public sealed class HubNotFoundException() : HubException("not found on the hub");

/// <summary>
/// The hub's <c>403 {"pair": true}</c>: this device hasn't been let in (yet, or any
/// more). It needs pairing, not a retry.
/// </summary>
public sealed class NotAllowedException() : HubException("this PC hasn't been allowed in to the hub");

/// <summary>A link code the hub refused: wrong, used or expired.</summary>
public sealed class BadLinkCodeException() : HubException("that code is wrong or has expired: make a new one");

/// <summary>The hub's 409 when registering a name that exists.</summary>
public sealed class NameTakenException(string message) : HubException(message);

/// <summary>Any other unexpected HTTP status.</summary>
public sealed class HubStatusException(int status, string? hubMessage)
    : HubException(string.IsNullOrEmpty(hubMessage) ? $"hub said {status}" : $"hub said {status}: {hubMessage}")
{
    /// <summary>The HTTP status.</summary>
    public int Status { get; } = status;

    /// <summary>The hub's own words, if it gave any.</summary>
    public string? HubMessage { get; } = hubMessage;
}
