using System.Text.Json;

namespace DeskLink.Windows
{
    internal sealed class AgentSettings
    {
        public string SharedKey { get; set; } = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32));
        public List<TrustedDevice> TrustedDevices { get; set; } = [];
        public string ReceiveFolder { get; set; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "DeskLink");
        public List<UserCommand> Commands { get; set; } = [];
        public bool AutoUsbAdb { get; set; } = true;

        private static string FilePath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "DeskLink", "settings.json");

        public static AgentSettings Load()
        {
            try
            {
                if (File.Exists(FilePath))
                {
                    var loaded = JsonSerializer.Deserialize<AgentSettings>(File.ReadAllText(FilePath)) ?? new();
                    using var document = JsonDocument.Parse(File.ReadAllText(FilePath));
                    if (!document.RootElement.TryGetProperty("TrustedDevices", out _))
                    {
                        loaded.TrustedDevices.Add(new TrustedDevice { Name = "Основной телефон", Id = "primary", Key = loaded.SharedKey });
                        loaded.Save();
                    }
                    return loaded;
                }
            }
            catch (JsonException)
            {
            }

            var settings = new AgentSettings();
            settings.TrustedDevices.Add(new TrustedDevice { Name = "Основной телефон", Id = "primary", Key = settings.SharedKey });
            settings.Save();
            return settings;
        }

        public void Save()
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            var temporaryPath = FilePath + ".tmp";
            File.WriteAllText(temporaryPath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
            File.Move(temporaryPath, FilePath, true);
        }
    }

    internal sealed class UserCommand
    {
        public Guid Id { get; set; } = Guid.NewGuid();
        public string Name { get; set; } = "";
        public string Command { get; set; } = "";
        public string Icon { get; set; } = "";
    }

    internal sealed class TrustedDevice
    {
        public string Name { get; set; } = "Новое устройство";
        public string Id { get; set; } = Guid.NewGuid().ToString("N");
        public string Key { get; set; } = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32));
    }
}
