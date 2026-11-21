using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;

namespace DeskLink.Windows
{
    internal sealed class BluetoothService : IDisposable
    {
        public static readonly Guid ServiceId = Guid.Parse("6b777071-7476-47df-8dda-6e0a526df8bf");

        private RfcommServiceProvider? _provider;
        private StreamSocketListener? _listener;

        public event Func<Stream, Stream, string, Task>? Connected;

        public async Task StartAsync()
        {
            _provider = await RfcommServiceProvider.CreateAsync(RfcommServiceId.FromUuid(ServiceId));
            _listener = new StreamSocketListener();
            _listener.ConnectionReceived += OnConnectionReceived;
            await _listener.BindServiceNameAsync(_provider.ServiceId.AsString(), SocketProtectionLevel.BluetoothEncryptionWithAuthentication);
            _provider.StartAdvertising(_listener, true);
        }

        private async void OnConnectionReceived(StreamSocketListener sender, StreamSocketListenerConnectionReceivedEventArgs args)
        {
            using var socket = args.Socket;
            using var input = socket.InputStream.AsStreamForRead();
            using var output = socket.OutputStream.AsStreamForWrite();
            if (Connected is not null)
                await Connected(input, output, "Bluetooth");
        }

        public void Dispose()
        {
            _provider?.StopAdvertising();
            _listener?.Dispose();
        }
    }
}
