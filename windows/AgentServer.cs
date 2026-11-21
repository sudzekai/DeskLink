using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows;

namespace DeskLink.Windows
{
    internal sealed class AgentServer(AgentSettings settings, HardwareService hardware) : IAsyncDisposable
    {
        private readonly TcpListener _listener = new(IPAddress.Any, 47652);
        private readonly ConcurrentDictionary<string, DateTimeOffset> _nonces = new();
        private readonly ConcurrentDictionary<Guid, byte> _runningCommands = new();
        private readonly CancellationTokenSource _stop = new();

        public event Action<string>? StatusChanged;
        public event Action<string>? Error;

        public void Start()
        {
            _listener.Start();
            _ = AcceptAsync();
            StatusChanged?.Invoke("Ожидание подключения · TCP 47652");
        }

        private async Task AcceptAsync()
        {
            while (!_stop.IsCancellationRequested)
            {
                try
                {
                    var client = await _listener.AcceptTcpClientAsync(_stop.Token);
                    _ = HandleAsync(client);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception exception)
                {
                    Error?.Invoke(exception.Message);
                }
            }
        }

        private async Task HandleAsync(TcpClient client)
        {
            using (client)
            using (var stream = client.GetStream())
            using (var reader = new StreamReader(stream, Encoding.UTF8, false, 8192, true))
            using (var writer = new StreamWriter(stream, new UTF8Encoding(false), 8192, true) { AutoFlush = true })
            {
                client.ReceiveTimeout = 30000;
                client.SendTimeout = 30000;

                try
                {
                    while (!_stop.IsCancellationRequested)
                    {
                        var line = await reader.ReadLineAsync(_stop.Token);
                        if (line is null)
                            break;

                        if (line.Length > 8 * 1024 * 1024)
                            break;

                        using var request = JsonDocument.Parse(line);
                        var root = request.RootElement;
                        var nonce = root.GetProperty("nonce").GetString() ?? "";
                        var action = root.GetProperty("action").GetString() ?? "";
                        var payload = root.GetProperty("payload").GetRawText();
                        var signature = root.GetProperty("signature").GetString() ?? "";

                        if (!Authenticate(nonce, action, payload, signature))
                        {
                            await writer.WriteLineAsync("{\"ok\":false,\"error\":\"Authentication failed\"}");
                            break;
                        }

                        StatusChanged?.Invoke($"Подключено · {client.Client.RemoteEndPoint}");
                        var response = await DispatchAsync(action, root.GetProperty("payload"));
                        await writer.WriteLineAsync(JsonSerializer.Serialize(response));
                    }
                }
                catch (Exception exception) when (exception is IOException or JsonException or SocketException or OperationCanceledException or KeyNotFoundException)
                {
                    Error?.Invoke(exception.Message);
                }
                finally
                {
                    StatusChanged?.Invoke("Ожидание подключения · TCP 47652");
                }
            }
        }

        private bool Authenticate(string nonce, string action, string payload, string signature)
        {
            if (nonce.Length != 32 || !_nonces.TryAdd(nonce, DateTimeOffset.UtcNow))
                return false;

            foreach (var entry in _nonces)
            {
                if (DateTimeOffset.UtcNow - entry.Value > TimeSpan.FromMinutes(5))
                    _nonces.TryRemove(entry.Key, out _);
            }

            try
            {
                var key = Convert.FromHexString(settings.SharedKey);
                var expected = HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(nonce + "\n" + action + "\n" + payload));
                return CryptographicOperations.FixedTimeEquals(expected, Convert.FromHexString(signature));
            }
            catch (FormatException)
            {
                return false;
            }
        }

        private async Task<object> DispatchAsync(string action, JsonElement payload)
        {
            switch (action)
            {
                case "telemetry":
                    return new { ok = true, data = hardware.Read() };

                case "commands":
                    return new { ok = true, data = settings.Commands.Select(x => new { x.Id, x.Name, x.Icon }) };

                case "run":
                    var id = payload.GetProperty("id").GetGuid();
                    var command = settings.Commands.SingleOrDefault(x => x.Id == id);
                    if (command is null)
                        return new { ok = false, error = "Unknown command" };

                    if (!_runningCommands.TryAdd(id, 0))
                        return new { ok = false, error = "Command already running" };

                    try
                    {
                        return await RunCommandAsync(command);
                    }
                    finally
                    {
                        _runningCommands.TryRemove(id, out _);
                    }

                case "clipboard.get":
                    var text = await System.Windows.Application.Current.Dispatcher.InvokeAsync(() => System.Windows.Clipboard.ContainsText() ? System.Windows.Clipboard.GetText() : "");
                    return new { ok = true, data = text };

                case "clipboard.set":
                    var content = payload.GetProperty("text").GetString() ?? "";
                    if (content.Length > 1_000_000)
                        return new { ok = false, error = "Clipboard text too large" };

                    await System.Windows.Application.Current.Dispatcher.InvokeAsync(() => System.Windows.Clipboard.SetText(content));
                    return new { ok = true };

                case "file":
                    var name = Path.GetFileName(payload.GetProperty("name").GetString() ?? "");
                    var bytes = Convert.FromBase64String(payload.GetProperty("data").GetString() ?? "");
                    if (string.IsNullOrWhiteSpace(name) || bytes.Length > 5_000_000)
                        return new { ok = false, error = "Invalid file chunk" };

                    Directory.CreateDirectory(settings.ReceiveFolder);
                    var target = Path.Combine(settings.ReceiveFolder, Guid.NewGuid().ToString("N") + "_" + name);
                    await File.WriteAllBytesAsync(target, bytes);
                    return new { ok = true, data = target };

                default:
                    return new { ok = false, error = "Unknown action" };
            }
        }

        private async Task<object> RunCommandAsync(UserCommand command)
        {
            using var process = new Process
            {
                StartInfo = new ProcessStartInfo("cmd.exe", "/d /s /c \"" + command.Command + "\"")
                {
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    RedirectStandardError = true,
                    RedirectStandardOutput = true
                }
            };

            try
            {
                process.Start();
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
                await process.WaitForExitAsync(timeout.Token);
                var error = await process.StandardError.ReadToEndAsync();
                if (process.ExitCode != 0)
                    Error?.Invoke($"{command.Name}: {error}");

                return new { ok = process.ExitCode == 0, error = error.Length > 1000 ? error[..1000] : error };
            }
            catch (OperationCanceledException)
            {
                process.Kill(true);
                Error?.Invoke($"{command.Name}: превышено время выполнения");
                return new { ok = false, error = "Command timed out" };
            }
        }

        public ValueTask DisposeAsync()
        {
            _stop.Cancel();
            _listener.Stop();
            _stop.Dispose();
            return ValueTask.CompletedTask;
        }
    }
}
