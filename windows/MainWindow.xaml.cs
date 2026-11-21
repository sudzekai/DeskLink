using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;
using System.Text.Json;
using System.Windows.Media;

namespace DeskLink.Windows
{
    public partial class MainWindow : Window
    {
        private readonly AgentSettings _settings;
        private readonly HardwareService _hardware;
        private readonly AgentServer _server;
        private readonly ObservableCollection<UserCommand> _commands;
        private readonly ObservableCollection<TrustedDevice> _trusted;
        private readonly System.Windows.Forms.NotifyIcon _tray;
        private readonly Dictionary<string, int> _icons;
        private bool _exit;

        public MainWindow()
        {
            InitializeComponent();
            _settings = AgentSettings.Load();
            _hardware = new HardwareService();
            _server = new(_settings, _hardware);
            _server.StatusChanged += message => Dispatcher.InvokeAsync(() => StatusText.Text = message);
            _server.Error += message => Dispatcher.InvokeAsync(() => Log(message));
            _commands = new(_settings.Commands);
            _trusted = new(_settings.TrustedDevices);
            TrustedList.ItemsSource = _trusted;
            _icons = JsonSerializer.Deserialize<Dictionary<string, int>>(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Resources", "bootstrap-icons.json"))) ?? [];
            IconBox.ItemsSource = _icons.Keys.Order();
            IconPreview.FontFamily = new System.Windows.Media.FontFamily(new Uri("pack://application:,,,/"), "./Resources/#bootstrap-icons");
            CommandsList.ItemsSource = _commands;
            TrustedList.SelectedIndex = 0;
            FolderBox.Text = _settings.ReceiveFolder;
            var logPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "DeskLink", "errors.log");
            if (File.Exists(logPath))
            {
                foreach (var line in File.ReadLines(logPath).TakeLast(200).Reverse())
                    LogList.Items.Add(line);
            }
            AutoStartBox.IsChecked = IsAutoStartEnabled();
            _tray = new System.Windows.Forms.NotifyIcon { Icon = System.Drawing.SystemIcons.Application, Text = "DeskLink", Visible = true, ContextMenuStrip = new() };
            _tray.ContextMenuStrip.Items.Add("Открыть", null, (_, _) => Dispatcher.Invoke(ShowWindow));
            _tray.ContextMenuStrip.Items.Add("Выход", null, (_, _) => Dispatcher.Invoke(Exit));
            _tray.DoubleClick += (_, _) => Dispatcher.Invoke(ShowWindow);
            try { _server.Start(); }
            catch (Exception exception) { Log(exception.Message); }
        }

        private void Log(string message)
        {
            var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {message}";
            LogList.Items.Insert(0, line);
            if (LogList.Items.Count > 200) LogList.Items.RemoveAt(200);
            var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "DeskLink", "errors.log");
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.AppendAllText(path, line + Environment.NewLine);
        }

        private void Selected(object sender, SelectionChangedEventArgs e)
        {
            if (CommandsList.SelectedItem is not UserCommand command) return;
            NameBox.Text = command.Name;
            CommandBox.Text = command.Command;
            IconBox.Text = command.Icon;
            UpdateIconPreview();
        }

        private void New(object sender, RoutedEventArgs e)
        {
            CommandsList.SelectedItem = null;
            NameBox.Clear();
            CommandBox.Clear();
            IconBox.Text = "";
            IconPreview.Text = "";
        }

        private void IconSelected(object sender, SelectionChangedEventArgs e) => UpdateIconPreview();

        private void TrustedSelected(object sender, SelectionChangedEventArgs e)
        {
            var device = TrustedList.SelectedItem as TrustedDevice;
            DeviceIdBox.Text = device?.Id ?? "";
            KeyBox.Text = device?.Key ?? "";
        }

        private void AddDevice(object sender, RoutedEventArgs e)
        {
            var device = new TrustedDevice { Name = "Телефон " + (_trusted.Count + 1) };
            _trusted.Add(device);
            PersistDevices();
            TrustedList.SelectedItem = device;
        }

        private void RemoveDevice(object sender, RoutedEventArgs e)
        {
            if (TrustedList.SelectedItem is not TrustedDevice device)
                return;

            _trusted.Remove(device);
            PersistDevices();
            TrustedList.SelectedIndex = 0;
        }

        private void PersistDevices()
        {
            _settings.TrustedDevices = _trusted.ToList();
            _settings.Save();
        }

        private void UpdateIconPreview()
        {
            IconPreview.Text = _icons.TryGetValue(IconBox.Text, out var code) ? char.ConvertFromUtf32(code) : "";
        }

        private void Save(object sender, RoutedEventArgs e)
        {
            if (string.IsNullOrWhiteSpace(NameBox.Text) || string.IsNullOrWhiteSpace(CommandBox.Text)) return;
            var command = CommandsList.SelectedItem as UserCommand;
            if (command is null) { command = new(); _commands.Add(command); }
            command.Name = NameBox.Text.Trim();
            command.Command = CommandBox.Text.Trim();
            command.Icon = IconBox.Text.Trim();
            Persist();
            CommandsList.Items.Refresh();
            CommandsList.SelectedItem = command;
        }

        private void Delete(object sender, RoutedEventArgs e)
        {
            if (CommandsList.SelectedItem is not UserCommand command) return;
            _commands.Remove(command);
            Persist();
        }

        private void Up(object sender, RoutedEventArgs e) => Move(-1);
        private void Down(object sender, RoutedEventArgs e) => Move(1);

        private void Move(int offset)
        {
            var index = CommandsList.SelectedIndex;
            if (index < 0 || index + offset < 0 || index + offset >= _commands.Count) return;
            _commands.Move(index, index + offset);
            Persist();
        }

        private void Persist()
        {
            _settings.Commands = _commands.ToList();
            _settings.Save();
        }

        private async void Test(object sender, RoutedEventArgs e)
        {
            if (CommandsList.SelectedItem is not UserCommand command) return;
            try
            {
                using var process = Process.Start(new ProcessStartInfo("cmd.exe", "/d /s /c \"" + command.Command + "\"") { UseShellExecute = false, CreateNoWindow = true });
                if (process is null) throw new InvalidOperationException("Запуск не удался");
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
                await process.WaitForExitAsync(timeout.Token);
                ResultText.Text = $"Код завершения: {process.ExitCode}";
            }
            catch (Exception exception) { ResultText.Text = exception.Message; }
        }

        private void SaveFolder(object sender, RoutedEventArgs e)
        {
            Directory.CreateDirectory(FolderBox.Text);
            _settings.ReceiveFolder = FolderBox.Text;
            _settings.Save();
        }

        private void BrowseFolder(object sender, RoutedEventArgs e)
        {
            using var dialog = new System.Windows.Forms.FolderBrowserDialog { SelectedPath = FolderBox.Text };
            if (dialog.ShowDialog() == System.Windows.Forms.DialogResult.OK)
                FolderBox.Text = dialog.SelectedPath;
        }

        private static bool IsAutoStartEnabled()
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
            return key?.GetValue("DeskLink") is not null;
        }

        private void AutoStart(object sender, RoutedEventArgs e)
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true);
            if (AutoStartBox.IsChecked == true) key?.SetValue("DeskLink", "\"" + Environment.ProcessPath + "\"");
            else key?.DeleteValue("DeskLink", false);
        }

        private void ShowWindow() { Show(); WindowState = WindowState.Normal; Activate(); }
        private void Exit() { _exit = true; Close(); }

        protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
        {
            if (!_exit) { e.Cancel = true; Hide(); return; }
            _tray.Visible = false;
            _tray.Dispose();
            _server.DisposeAsync().AsTask().GetAwaiter().GetResult();
            _hardware.Dispose();
            base.OnClosing(e);
        }
    }
}
