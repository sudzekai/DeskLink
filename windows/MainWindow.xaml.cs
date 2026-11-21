using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;

namespace DeskLink.Windows
{
    public partial class MainWindow : Window
    {
        private readonly AgentSettings _settings;
        private readonly HardwareService _hardware;
        private readonly AgentServer _server;
        private readonly ObservableCollection<UserCommand> _commands;
        private readonly System.Windows.Forms.NotifyIcon _tray;
        private bool _exit;

        public MainWindow()
        {
            InitializeComponent();
            _settings = AgentSettings.Load();
            _hardware = new HardwareService();
            _server = new(_settings, _hardware);
            _server.StatusChanged += message => Dispatcher.InvokeAsync(() => StatusText.Text = message);
            _server.Error += message => Dispatcher.InvokeAsync(() => LogList.Items.Insert(0, message));
            _commands = new(_settings.Commands);
            CommandsList.ItemsSource = _commands;
            KeyBox.Text = _settings.SharedKey;
            FolderBox.Text = _settings.ReceiveFolder;
            AutoStartBox.IsChecked = IsAutoStartEnabled();
            _tray = new System.Windows.Forms.NotifyIcon { Icon = System.Drawing.SystemIcons.Application, Text = "DeskLink", Visible = true, ContextMenuStrip = new() };
            _tray.ContextMenuStrip.Items.Add("Открыть", null, (_, _) => Dispatcher.Invoke(ShowWindow));
            _tray.ContextMenuStrip.Items.Add("Выход", null, (_, _) => Dispatcher.Invoke(Exit));
            _tray.DoubleClick += (_, _) => Dispatcher.Invoke(ShowWindow);
            try { _server.Start(); }
            catch (Exception exception) { LogList.Items.Insert(0, exception.Message); }
        }

        private void Selected(object sender, SelectionChangedEventArgs e)
        {
            if (CommandsList.SelectedItem is not UserCommand command) return;
            NameBox.Text = command.Name;
            CommandBox.Text = command.Command;
            IconBox.Text = command.Icon;
        }

        private void New(object sender, RoutedEventArgs e)
        {
            CommandsList.SelectedItem = null;
            NameBox.Clear();
            CommandBox.Clear();
            IconBox.Clear();
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
