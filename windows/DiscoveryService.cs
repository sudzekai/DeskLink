using System.Net;
using System.Net.Sockets;
using System.Text;

namespace DeskLink.Windows
{
    internal sealed class DiscoveryService : IDisposable
    {
        private readonly UdpClient _socket = new(new IPEndPoint(IPAddress.Any, 47653));
        private readonly CancellationTokenSource _stop = new();

        public void Start() => _ = ListenAsync();

        private async Task ListenAsync()
        {
            while (!_stop.IsCancellationRequested)
            {
                try
                {
                    var message = await _socket.ReceiveAsync(_stop.Token);
                    if (!IsPrivate(message.RemoteEndPoint.Address))
                        continue;

                    if (Encoding.ASCII.GetString(message.Buffer) != "DESKLINK_DISCOVER")
                        continue;

                    var response = Encoding.ASCII.GetBytes("DESKLINK_AGENT");
                    await _socket.SendAsync(response, message.RemoteEndPoint, _stop.Token);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (SocketException)
                {
                    if (_stop.IsCancellationRequested)
                        break;
                }
            }
        }

        private static bool IsPrivate(IPAddress address)
        {
            var bytes = address.GetAddressBytes();
            if (bytes.Length != 4)
                return false;

            return bytes[0] == 10
                || bytes[0] == 127
                || (bytes[0] == 172 && bytes[1] is >= 16 and <= 31)
                || (bytes[0] == 192 && bytes[1] == 168);
        }

        public void Dispose()
        {
            _stop.Cancel();
            _socket.Dispose();
            _stop.Dispose();
        }
    }
}
