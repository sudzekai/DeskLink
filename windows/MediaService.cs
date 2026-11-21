using NAudio.CoreAudioApi;
using Windows.Media.Control;
using Windows.Storage.Streams;

namespace DeskLink.Windows
{
    internal sealed class MediaService
    {
        public async Task<object> ReadAsync()
        {
            var manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            var session = manager.GetCurrentSession();
            using var device = new MMDeviceEnumerator().GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            var volume = device.AudioEndpointVolume;

            if (session is null)
                return new { title = "", artist = "", playing = false, artwork = "", volume = volume.MasterVolumeLevelScalar, muted = volume.Mute };

            var properties = await session.TryGetMediaPropertiesAsync();
            var playback = session.GetPlaybackInfo();
            var artwork = "";

            if (properties.Thumbnail is not null)
            {
                using var stream = await properties.Thumbnail.OpenReadAsync();
                if (stream.Size <= 2_000_000)
                {
                    using var reader = new DataReader(stream);
                    var length = await reader.LoadAsync((uint)stream.Size);
                    var bytes = new byte[length];
                    reader.ReadBytes(bytes);
                    artwork = Convert.ToBase64String(bytes);
                }
            }

            return new
            {
                title = properties.Title,
                artist = properties.Artist,
                playing = playback.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing,
                artwork,
                volume = volume.MasterVolumeLevelScalar,
                muted = volume.Mute
            };
        }

        public async Task ControlAsync(string operation, float? level)
        {
            if (operation is "volume" or "mute")
            {
                using var device = new MMDeviceEnumerator().GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                if (operation == "volume") device.AudioEndpointVolume.MasterVolumeLevelScalar = Math.Clamp(level ?? 0, 0, 1);
                else device.AudioEndpointVolume.Mute = !device.AudioEndpointVolume.Mute;
                return;
            }

            var manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            var session = manager.GetCurrentSession() ?? throw new InvalidOperationException("Нет активной media session");

            switch (operation)
            {
                case "toggle": await session.TryTogglePlayPauseAsync(); break;
                case "next": await session.TrySkipNextAsync(); break;
                case "previous": await session.TrySkipPreviousAsync(); break;
                default: throw new ArgumentException("Неизвестная операция");
            }
        }
    }
}
