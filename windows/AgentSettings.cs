using System.Text.Json;

namespace DeskLink.Windows
{
    internal sealed class AgentSettings
    {
        public string SharedKey { get; set; } = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32));
        public string ReceiveFolder { get; set; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "DeskLink");
        public List<UserCommand> Commands { get; set; } = [];

        private static string FilePath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "DeskLink", "settings.json");

        public static AgentSettings Load()
        {
            try
            {
                if (File.Exists(FilePath))
                    return JsonSerializer.Deserialize<AgentSettings>(File.ReadAllText(FilePath)) ?? new();
            }
            catch (JsonException)
            {
            }

            var settings = new AgentSettings();
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
}
