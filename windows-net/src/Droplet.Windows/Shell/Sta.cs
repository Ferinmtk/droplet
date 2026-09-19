using System.Windows.Threading;

namespace Droplet.Windows.Shell;

/// <summary>
/// Runs work on a fresh single-threaded-apartment thread: WPF's imaging classes (PNG and
/// JPEG encoding) want one, and the UI thread shouldn't wait for them.
/// </summary>
internal static class Sta
{
    public static Task<T> Run<T>(Func<T> work)
    {
        var done = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            try
            {
                done.SetResult(work());
            }
            catch (Exception e)
            {
                done.SetException(e);
            }
            finally
            {
                Dispatcher.FromThread(Thread.CurrentThread)?.InvokeShutdown();
            }
        })
        {
            IsBackground = true,
            Name = "droplet imaging",
        };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return done.Task;
    }
}
