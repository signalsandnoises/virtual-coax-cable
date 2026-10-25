using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace Vcx.Sender;

public sealed class UnsupportedFormatException(string message) : Exception(message);

/// <summary>Sets a flag when the default render device changes (IMMNotificationClient callbacks arrive on a COM thread).</summary>
internal sealed class DefaultDeviceWatcher : NAudio.CoreAudioApi.Interfaces.IMMNotificationClient
{
    private volatile int _changed;
    public string? LastDefaultId;

    /// <summary>True once after the default render device (multimedia role) changed.</summary>
    public bool TakeChanged() => Interlocked.Exchange(ref _changed, 0) != 0;

    public void OnDefaultDeviceChanged(DataFlow flow, Role role, string defaultDeviceId)
    {
        if (flow != DataFlow.Render || role != Role.Multimedia) return;
        LastDefaultId = defaultDeviceId;
        _changed = 1;
    }
    public void OnDeviceStateChanged(string deviceId, DeviceState newState) { }
    public void OnDeviceAdded(string pwstrDeviceId) { }
    public void OnDeviceRemoved(string deviceId) { }
    public void OnPropertyValueChanged(string pwstrDeviceId, PropertyKey key) { }
}

/// <summary>
/// WASAPI shared-mode, event-driven loopback capture of the default render endpoint. Must be created,
/// used and disposed on the same (MTA) thread: the capture thread.
/// </summary>
internal sealed unsafe class LoopbackCapture : IDisposable
{
    private static readonly Guid IeeeFloatSubtype = new("00000003-0000-0010-8000-00aa00389b71");

    private readonly MMDeviceEnumerator _enumerator;
    private readonly MMDevice _device;
    private readonly AudioClient _client;
    private readonly AudioCaptureClient _capture;
    private readonly EventWaitHandle _event = new(false, EventResetMode.AutoReset);

    public string DeviceName { get; }
    public string DeviceId { get; }
    public string FormatDescription { get; }
    public IntPtr EventHandle => _event.SafeWaitHandle.DangerousGetHandle();
    public long DefaultPeriod100ns { get; }

    /// <summary>Called with interleaved float32 stereo, the frame count, the WASAPI device position (frames) of the
    /// first frame, and the SILENT / DATA_DISCONTINUITY buffer flags.</summary>
    public delegate void ChunkHandler(ReadOnlySpan<float> samples, int frames, ulong devicePosition, bool silent, bool discontinuity);

    public LoopbackCapture()
    {
        _enumerator = new MMDeviceEnumerator();
        try
        {
            _device = _enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            DeviceName = _device.FriendlyName;
            DeviceId = _device.ID;
            _client = _device.AudioClient;
            var fmt = _client.MixFormat;
            FormatDescription = $"{fmt.SampleRate} Hz, {fmt.Channels} ch, {fmt.BitsPerSample}-bit {fmt.Encoding}";
            bool isFloat = fmt.Encoding == WaveFormatEncoding.IeeeFloat ||
                           (fmt is WaveFormatExtensible ext && ext.SubFormat == IeeeFloatSubtype);
            if (fmt.SampleRate != Protocol.SampleRate || fmt.Channels != Protocol.Channels || !isFloat || fmt.BitsPerSample != 32)
                throw new UnsupportedFormatException(
                    $"default render device '{DeviceName}' mix format is {FormatDescription}; " +
                    "milestone 1 needs 48000 Hz, 2 ch, 32-bit float (no resampling). Change the device's default format in Windows Sound settings.");
            DefaultPeriod100ns = _client.DefaultDevicePeriod;
            // Shared + event-driven: periodicity must be 0; buffer 100 ms (we drain as soon as data arrives).
            _client.Initialize(AudioClientShareMode.Shared,
                AudioClientStreamFlags.Loopback | AudioClientStreamFlags.EventCallback,
                1_000_000, 0, fmt, Guid.Empty);
            _client.SetEventHandle(EventHandle);
            _capture = _client.AudioCaptureClient;
            _client.Start();
        }
        catch
        {
            Dispose();
            throw;
        }
    }

    /// <summary>Reads every packet currently available. Returns the number of WASAPI packets read.</summary>
    public int Drain(ChunkHandler onChunk)
    {
        int count = 0;
        while (_capture.GetNextPacketSize() > 0)
        {
            IntPtr p = _capture.GetBuffer(out int frames, out AudioClientBufferFlags flags, out long devicePosition, out long _);
            try
            {
                bool silent = (flags & AudioClientBufferFlags.Silent) != 0;
                bool discontinuity = (flags & AudioClientBufferFlags.DataDiscontinuity) != 0;
                if (frames > 0)
                    onChunk(new ReadOnlySpan<float>((void*)p, frames * Protocol.Channels), frames, (ulong)devicePosition, silent, discontinuity);
            }
            finally
            {
                _capture.ReleaseBuffer(frames);
            }
            count++;
        }
        return count;
    }

    public void Dispose()
    {
        try { _client?.Stop(); } catch { /* ignore */ }
        try { _capture?.Dispose(); } catch { /* ignore */ }
        try { _client?.Dispose(); } catch { /* ignore */ }
        try { _device?.Dispose(); } catch { /* ignore */ }
        try { _enumerator?.Dispose(); } catch { /* ignore */ }
        _event.Dispose();
    }
}
