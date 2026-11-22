using System.Diagnostics;

namespace DeskLink.Windows
{
    internal sealed class AdbUsbService : IDisposable
    {
        private const string Port = "tcp:47652";
        private readonly Func<bool> _enabled;
        private readonly Action<string> _statusChanged;
        private readonly CancellationTokenSource _cancellation = new();
        private string? _lastStatus;

        public AdbUsbService(Func<bool> enabled, Action<string> statusChanged)
        {
            _enabled = enabled;
            _statusChanged = statusChanged;
        }

        public void Start() => _ = Task.Run(() => MonitorAsync(_cancellation.Token));

        private async Task MonitorAsync(CancellationToken cancellationToken)
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                try
                {
                    if (!_enabled())
                    {
                        SetStatus("Автоматическое USB-подключение выключено");
                    }
                    else
                    {
                        await CheckDevicesAsync(cancellationToken);
                    }

                    await Task.Delay(TimeSpan.FromSeconds(5), cancellationToken);
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    break;
                }
                catch (Exception exception)
                {
                    SetStatus($"USB: {exception.Message}");
                    try
                    {
                        await Task.Delay(TimeSpan.FromSeconds(5), cancellationToken);
                    }
                    catch (OperationCanceledException)
                    {
                        break;
                    }
                }
            }
        }

        private async Task CheckDevicesAsync(CancellationToken cancellationToken)
        {
            var adb = FindAdb();
            if (adb is null)
            {
                SetStatus("ADB не найден. Установите Android SDK Platform Tools.");
                return;
            }

            var devices = await RunAsync(adb, ["devices"], cancellationToken);
            if (devices.ExitCode != 0)
            {
                SetStatus($"ADB: {devices.Error.Trim()}");
                return;
            }

            var authorized = new List<string>();
            var unauthorized = false;
            foreach (var line in devices.Output.Split('\n'))
            {
                var parts = line.Trim().Split('\t', 2);
                if (parts.Length != 2) continue;

                if (parts[1].Trim() == "device") authorized.Add(parts[0]);
                if (parts[1].Trim() == "unauthorized") unauthorized = true;
            }

            if (authorized.Count == 0)
            {
                SetStatus(unauthorized ? "Подтвердите USB-отладку на телефоне" : "Ожидание телефона по USB");
                return;
            }

            foreach (var serial in authorized)
            {
                var existing = await RunAsync(adb, ["-s", serial, "reverse", "--list"], cancellationToken);
                if (existing.ExitCode != 0 || !existing.Output.Split('\n').Any(line => line.Contains($"{Port} {Port}", StringComparison.Ordinal)))
                {
                    var reverse = await RunAsync(adb, ["-s", serial, "reverse", Port, Port], cancellationToken);
                    if (reverse.ExitCode != 0)
                    {
                        SetStatus($"USB {serial}: {reverse.Error.Trim()}");
                        return;
                    }
                }
            }

            SetStatus($"USB готов: {string.Join(", ", authorized)}");
        }

        private void SetStatus(string status)
        {
            if (status == _lastStatus) return;

            _lastStatus = status;
            _statusChanged(status);
        }

        private static string? FindAdb()
        {
            var sdkRoots = new[]
            {
                Environment.GetEnvironmentVariable("ANDROID_HOME"),
                Environment.GetEnvironmentVariable("ANDROID_SDK_ROOT"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Android", "Sdk")
            };

            foreach (var root in sdkRoots)
            {
                if (string.IsNullOrWhiteSpace(root)) continue;

                var path = Path.Combine(root, "platform-tools", "adb.exe");
                if (File.Exists(path)) return path;
            }

            foreach (var directory in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
            {
                if (string.IsNullOrWhiteSpace(directory)) continue;

                var path = Path.Combine(directory.Trim('"'), "adb.exe");
                if (File.Exists(path)) return path;
            }

            return null;
        }

        private static async Task<(int ExitCode, string Output, string Error)> RunAsync(string adb, string[] arguments, CancellationToken cancellationToken)
        {
            var startInfo = new ProcessStartInfo(adb)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };

            foreach (var argument in arguments) startInfo.ArgumentList.Add(argument);

            using var process = Process.Start(startInfo) ?? throw new InvalidOperationException("ADB не запущен");
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(10));
            var output = process.StandardOutput.ReadToEndAsync(timeout.Token);
            var error = process.StandardError.ReadToEndAsync(timeout.Token);
            try
            {
                await process.WaitForExitAsync(timeout.Token);
                return (process.ExitCode, await output, await error);
            }
            catch (OperationCanceledException)
            {
                if (!process.HasExited) process.Kill(true);
                throw;
            }
        }

        public void Dispose() => _cancellation.Cancel();
    }
}
