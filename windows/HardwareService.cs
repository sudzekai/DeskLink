using LibreHardwareMonitor.Hardware;

namespace DeskLink.Windows
{
    internal sealed class HardwareService : IDisposable
    {
        private readonly Computer _computer = new()
        {
            IsCpuEnabled = true,
            IsGpuEnabled = true,
            IsMemoryEnabled = true,
            IsStorageEnabled = true
        };

        public HardwareService() => _computer.Open();

        public Dictionary<string, float?> Read()
        {
            lock (_computer)
                return ReadCore();
        }

        private Dictionary<string, float?> ReadCore()
        {
            var result = new Dictionary<string, float?>
            {
                ["cpuLoad"] = null,
                ["cpuTemperature"] = null,
                ["cpuClock"] = null,
                ["gpuLoad"] = null,
                ["gpuTemperature"] = null,
                ["gpuMemory"] = null,
                ["ramLoad"] = null,
                ["ramUsed"] = null,
                ["ramTotal"] = null,
                ["diskRead"] = null,
                ["diskWrite"] = null,
                ["diskLoad"] = null
            };

            float? memoryUsed = null;
            float? memoryAvailable = null;

            foreach (var hardware in _computer.Hardware)
            {
                hardware.Update();

                foreach (var subhardware in hardware.SubHardware)
                    subhardware.Update();

                var category = hardware.HardwareType.ToString();
                var sensors = hardware.Sensors.Concat(hardware.SubHardware.SelectMany(x => x.Sensors));

                foreach (var sensor in sensors)
                {
                    if (sensor.Value is not float value)
                        continue;

                    var name = sensor.Name.ToLowerInvariant();
                    var type = sensor.SensorType;

                    if (category == "Cpu")
                    {
                        if (type == SensorType.Load && name.Contains("total"))
                            result["cpuLoad"] = value;

                        if (type == SensorType.Temperature
                            && value is > 15 and < 130
                            && (result["cpuTemperature"] is null || name.Contains("package") || name.Contains("tctl")))
                            result["cpuTemperature"] = value;

                        if (type == SensorType.Clock && value > 0 && name.Contains("core") && result["cpuClock"] is null)
                            result["cpuClock"] = value;
                    }
                    else if (category.StartsWith("Gpu"))
                    {
                        if (type == SensorType.Load && name.Contains("core"))
                            result["gpuLoad"] = value;

                        if (type == SensorType.Temperature && value is > 15 and < 130 && result["gpuTemperature"] is null)
                            result["gpuTemperature"] = value;

                        if (type == SensorType.SmallData && name.Contains("used"))
                            result["gpuMemory"] = value;
                    }
                    else if (category == "Memory")
                    {
                        if (type == SensorType.Load)
                            result["ramLoad"] = value;

                        if (type == SensorType.Data && name.Contains("used"))
                            memoryUsed = value;

                        if (type == SensorType.Data && name.Contains("available"))
                            memoryAvailable = value;
                    }
                    else if (category == "Storage")
                    {
                        if (type == SensorType.Throughput && name.Contains("read"))
                            result["diskRead"] = value;

                        if (type == SensorType.Throughput && name.Contains("write"))
                            result["diskWrite"] = value;

                        if (type == SensorType.Load && result["diskLoad"] is null)
                            result["diskLoad"] = value;
                    }
                }
            }

            result["ramUsed"] = memoryUsed;
            if (memoryUsed is not null && memoryAvailable is not null)
                result["ramTotal"] = memoryUsed + memoryAvailable;

            return result;
        }

        public void Dispose() => _computer.Close();
    }
}
