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
        private readonly MediaService _media = new();
        private readonly BluetoothService _bluetooth = new();
        private readonly DiscoveryService _discovery = new();
        private readonly TcpListener _listener = new(IPAddress.Any, 47652);
        private readonly ConcurrentDictionary<string, DateTimeOffset> _nonces = new();
        private readonly ConcurrentDictionary<Guid, byte> _runningCommands = new();
        private readonly ConcurrentDictionary<Guid, UploadState> _uploads = new();
        private readonly ConcurrentDictionary<string, (string Endpoint, DateTimeOffset Seen)> _devices = new();
        private readonly CancellationTokenSource _stop = new();

        public event Action<string>? StatusChanged;
        public event Action<string>? Error;

        public IReadOnlyList<string> GetConnectedDevices()
        {
            var recent = DateTimeOffset.UtcNow - TimeSpan.FromSeconds(15);
            return _devices
                .Where(entry => entry.Value.Seen >= recent)
                .Select(entry => $"{entry.Key} · {entry.Value.Endpoint}")
                .ToList();
        }

        public void Start()
        {
            _listener.Start();
            _discovery.Start();
            _ = AcceptAsync();
            _bluetooth.Connected += HandleStreamsAsync;
            _ = StartBluetoothAsync();
            StatusChanged?.Invoke("Ожидание подключения · TCP 47652");
        }

        private async Task StartBluetoothAsync()
        {
            try
            {
                await _bluetooth.StartAsync();
                StatusChanged?.Invoke("Ожидание подключения · USB/TCP и Bluetooth");
            }
            catch (Exception exception)
            {
                Error?.Invoke("Bluetooth: " + exception.Message);
            }
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
            {
                client.ReceiveTimeout = 30000;
                client.SendTimeout = 30000;
                using var stream = client.GetStream();
                await HandleStreamsAsync(stream, stream, client.Client.RemoteEndPoint?.ToString() ?? "TCP");
            }
        }

        private async Task HandleStreamsAsync(Stream input, Stream output, string endpoint)
        {
            using (var reader = new StreamReader(input, Encoding.UTF8, false, 8192, true))
            using (var writer = new StreamWriter(output, new UTF8Encoding(false), 8192, true) { AutoFlush = true })
            {

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
                        var deviceId = root.GetProperty("deviceId").GetString() ?? "";
                        var action = root.GetProperty("action").GetString() ?? "";
                        var ciphertext = root.GetProperty("ciphertext").GetString() ?? "";
                        var signature = root.GetProperty("signature").GetString() ?? "";

                        if (!Authenticate(deviceId, nonce, action, ciphertext, signature, out var deviceKey))
                        {
                            await writer.WriteLineAsync("{\"ok\":false,\"error\":\"Authentication failed\"}");
                            break;
                        }

                        StatusChanged?.Invoke($"Подключено · {endpoint}");
                        _devices[deviceId] = (endpoint, DateTimeOffset.UtcNow);
                        object response;
                        try
                        {
                            using var decrypted = JsonDocument.Parse(Decrypt(deviceKey, nonce, action, ciphertext));
                            response = await DispatchAsync(action, decrypted.RootElement);
                        }
                        catch (Exception exception)
                        {
                            Error?.Invoke(exception.Message);
                            response = new { ok = false, error = exception.Message };
                        }
                        await writer.WriteLineAsync(JsonSerializer.Serialize(Encrypt(deviceKey, JsonSerializer.Serialize(response), nonce)));
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

        private bool Authenticate(string deviceId, string nonce, string action, string payload, string signature, out byte[] key)
        {
            key = [];
            if (nonce.Length != 24 || !_nonces.TryAdd(nonce, DateTimeOffset.UtcNow))
                return false;

            foreach (var entry in _nonces)
            {
                if (DateTimeOffset.UtcNow - entry.Value > TimeSpan.FromMinutes(5))
                    _nonces.TryRemove(entry.Key, out _);
            }

            try
            {
                var device = settings.TrustedDevices.SingleOrDefault(x => x.Id == deviceId);
                if (device is null)
                    return false;

                key = Convert.FromHexString(device.Key);
                var expected = HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(deviceId + "\n" + nonce + "\n" + action + "\n" + payload));
                return CryptographicOperations.FixedTimeEquals(expected, Convert.FromHexString(signature));
            }
            catch (FormatException)
            {
                return false;
            }
        }

        private static byte[] Decrypt(byte[] key, string nonce, string action, string ciphertext)
        {
            var combined = Convert.FromBase64String(ciphertext);
            if (combined.Length < 16)
                throw new InvalidDataException("Invalid encrypted payload");

            var plain = new byte[combined.Length - 16];
            using var aes = new AesGcm(key, 16);
            aes.Decrypt(Convert.FromHexString(nonce), combined.AsSpan(0, plain.Length), combined.AsSpan(plain.Length), plain, Encoding.UTF8.GetBytes(action));
            return plain;
        }

        private static object Encrypt(byte[] key, string response, string requestNonce)
        {
            var nonce = RandomNumberGenerator.GetBytes(12);
            var plain = Encoding.UTF8.GetBytes(response);
            var combined = new byte[plain.Length + 16];
            using var aes = new AesGcm(key, 16);
            aes.Encrypt(nonce, plain, combined.AsSpan(0, plain.Length), combined.AsSpan(plain.Length), Encoding.UTF8.GetBytes(requestNonce));
            return new { nonce = Convert.ToHexString(nonce), ciphertext = Convert.ToBase64String(combined) };
        }

        private async Task<object> DispatchAsync(string action, JsonElement payload)
        {
            switch (action)
            {
                case "telemetry":
                    return new { ok = true, data = hardware.Read() };

                case "media":
                    return new { ok = true, data = await _media.ReadAsync() };

                case "media.control":
                    await _media.ControlAsync(payload.GetProperty("operation").GetString() ?? "", payload.TryGetProperty("level", out var level) ? level.GetSingle() : null);
                    return new { ok = true };

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

                case "file.start":
                    var name = Path.GetFileName(payload.GetProperty("name").GetString() ?? "");
                    var size = payload.GetProperty("size").GetInt64();
                    if (string.IsNullOrWhiteSpace(name) || size < 0 || size > 2L * 1024 * 1024 * 1024)
                        return new { ok = false, error = "Invalid file" };

                    Directory.CreateDirectory(settings.ReceiveFolder);
                    var uploadId = Guid.NewGuid();
                    var temporary = Path.Combine(settings.ReceiveFolder, ".desklink-" + uploadId.ToString("N") + ".part");
                    var destination = Path.Combine(settings.ReceiveFolder, uploadId.ToString("N") + "_" + name);
                    _uploads[uploadId] = new UploadState(temporary, destination, size);
                    return new { ok = true, data = uploadId };

                case "file.chunk":
                    var chunkId = payload.GetProperty("id").GetGuid();
                    if (!_uploads.TryGetValue(chunkId, out var upload))
                        return new { ok = false, error = "Unknown upload" };

                    var bytes = Convert.FromBase64String(payload.GetProperty("data").GetString() ?? "");
                    if (bytes.Length == 0 || bytes.Length > 512 * 1024)
                        return new { ok = false, error = "Invalid chunk" };

                    await upload.Gate.WaitAsync();
                    try
                    {
                        if (upload.Written + bytes.Length > upload.Size)
                            return new { ok = false, error = "Chunk exceeds file size" };

                        await using var file = new FileStream(upload.TemporaryPath, FileMode.Append, FileAccess.Write, FileShare.None, 81920, true);
                        await file.WriteAsync(bytes);
                        upload.Written += bytes.Length;
                        upload.LastActivity = DateTimeOffset.UtcNow;
                    }
                    finally
                    {
                        upload.Gate.Release();
                    }

                    return new { ok = true, data = upload.Written };

                case "file.finish":
                    var finishId = payload.GetProperty("id").GetGuid();
                    if (!_uploads.TryRemove(finishId, out var completed))
                        return new { ok = false, error = "Unknown upload" };

                    await completed.Gate.WaitAsync();
                    try
                    {
                        if (completed.Written != completed.Size)
                        {
                            File.Delete(completed.TemporaryPath);
                            return new { ok = false, error = "Incomplete upload" };
                        }

                        if (completed.Size == 0)
                            await File.WriteAllBytesAsync(completed.TemporaryPath, []);

                        File.Move(completed.TemporaryPath, completed.DestinationPath);
                        return new { ok = true, data = completed.DestinationPath };
                    }
                    finally
                    {
                        completed.Gate.Dispose();
                    }

                case "file.cancel":
                    var cancelId = payload.GetProperty("id").GetGuid();
                    if (_uploads.TryRemove(cancelId, out var cancelled))
                    {
                        await cancelled.Gate.WaitAsync();
                        File.Delete(cancelled.TemporaryPath);
                        cancelled.Gate.Dispose();
                    }
                    return new { ok = true };

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
                var outputTask = DrainAsync(process.StandardOutput, 0);
                var errorTask = DrainAsync(process.StandardError, 1000);
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
                await process.WaitForExitAsync(timeout.Token);
                await outputTask;
                var error = await errorTask;
                if (process.ExitCode != 0)
                    Error?.Invoke($"{command.Name}: {error}");

                return new { ok = process.ExitCode == 0, error = error.Length > 1000 ? error[..1000] : error };
            }
            catch (OperationCanceledException)
            {
                if (!process.HasExited)
                    process.Kill(true);
                await process.WaitForExitAsync();
                Error?.Invoke($"{command.Name}: превышено время выполнения");
                return new { ok = false, error = "Command timed out" };
            }
        }

        private static async Task<string> DrainAsync(StreamReader reader, int maximumLength)
        {
            var captured = new StringBuilder();
            var buffer = new char[1024];
            int length;

            while ((length = await reader.ReadAsync(buffer)) > 0)
            {
                var remaining = maximumLength - captured.Length;
                if (remaining > 0)
                    captured.Append(buffer, 0, Math.Min(length, remaining));
            }

            return captured.ToString();
        }

        public ValueTask DisposeAsync()
        {
            _stop.Cancel();
            _listener.Stop();
            _bluetooth.Dispose();
            _discovery.Dispose();
            foreach (var upload in _uploads.Values)
            {
                File.Delete(upload.TemporaryPath);
                upload.Gate.Dispose();
            }
            _stop.Dispose();
            return ValueTask.CompletedTask;
        }

        private sealed class UploadState(string temporaryPath, string destinationPath, long size)
        {
            public string TemporaryPath { get; } = temporaryPath;
            public string DestinationPath { get; } = destinationPath;
            public long Size { get; } = size;
            public long Written { get; set; }
            public DateTimeOffset LastActivity { get; set; } = DateTimeOffset.UtcNow;
            public SemaphoreSlim Gate { get; } = new(1, 1);
        }
    }
}
