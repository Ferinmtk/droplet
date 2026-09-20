using System.Threading.Channels;
using Droplet.Core.Platform;
using Droplet.Windows.Shell;
using Microsoft.Extensions.Logging;
using ToastNotification = Windows.UI.Notifications.ToastNotification;
using ToastNotificationManager = Windows.UI.Notifications.ToastNotificationManager;
using ToastNotifier = Windows.UI.Notifications.ToastNotifier;
using XmlDocument = Windows.Data.Xml.Dom.XmlDocument;

namespace Droplet.Windows.Platform;

/// <summary>
/// Toasts, through Windows' notification API (Windows.UI.Notifications) from one worker.
/// Unpackaged, they're shown under droplet's AppUserModelID, which <see cref="Registration"/>
/// gives a name and an icon; packaged, under the package's own. If notifications can't be
/// shown (a stripped-down Windows), the error is logged and droplet carries on without them.
/// </summary>
internal sealed class ToastService : INotifications, IDisposable
{
    const string Group = "droplet";

    readonly Func<string> actionKey;
    readonly Func<Notification, bool> wanted;
    readonly ILogger log;
    readonly Channel<Action> jobs = Channel.CreateBounded<Action>(new BoundedChannelOptions(64) { FullMode = BoundedChannelFullMode.DropWrite, SingleReader = true });
    ToastNotifier? notifier;

    /// <param name="actionKey">The config's action key, for droplet: links.</param>
    /// <param name="wanted">Whether to show a notification now (the settings may have paused them).</param>
    /// <param name="log">Where failures go.</param>
    public ToastService(Func<string> actionKey, Func<Notification, bool> wanted, ILogger log)
    {
        this.actionKey = actionKey;
        this.wanted = wanted;
        this.log = log;
        _ = Task.Run(WorkAsync);
    }

    async Task WorkAsync()
    {
        await foreach (var job in jobs.Reader.ReadAllAsync().ConfigureAwait(false))
        {
            try
            {
                job();
            }
            catch (Exception e)
            {
                log.LogWarning("notification: {Error}", e.Message);
            }
        }
    }

    ToastNotifier Notifier() => notifier ??= Packaging.IsPackaged
        ? ToastNotificationManager.CreateToastNotifier()
        : ToastNotificationManager.CreateToastNotifier(Registration.AppId);

    /// <inheritdoc/>
    public void Show(Notification notification)
    {
        ArgumentNullException.ThrowIfNull(notification);
        if (!wanted(notification))
        {
            return;
        }
        var key = actionKey();
        if (!jobs.Writer.TryWrite(() =>
            {
                var xml = new XmlDocument();
                xml.LoadXml(ToastXml.Build(notification, key));
                var toast = new ToastNotification(xml) { Group = Group };
                var tag = ToastXml.Tag(notification.Tag);
                if (tag.Length > 0)
                {
                    toast.Tag = tag;
                }
                Notifier().Show(toast);
            }))
        {
            log.LogWarning("notification dropped (too many at once): {Title}", notification.Title);
        }
    }

    /// <inheritdoc/>
    public void Clear(string tag)
    {
        var t = ToastXml.Tag(tag);
        if (t.Length == 0)
        {
            return;
        }
        jobs.Writer.TryWrite(() =>
        {
            if (Packaging.IsPackaged)
            {
                ToastNotificationManager.History.Remove(t, Group);
            }
            else
            {
                ToastNotificationManager.History.Remove(t, Group, Registration.AppId);
            }
        });
    }

    /// <summary>Waits (up to <paramref name="timeout"/>) for queued toasts to be shown, for a short-lived process.</summary>
    public async Task FlushAsync(TimeSpan timeout)
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        if (jobs.Writer.TryWrite(() => done.TrySetResult()))
        {
            await Task.WhenAny(done.Task, Task.Delay(timeout)).ConfigureAwait(false);
        }
    }

    public void Dispose() => jobs.Writer.TryComplete();
}
