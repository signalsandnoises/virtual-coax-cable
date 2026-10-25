using Windows.Networking.Connectivity;
using Windows.Networking.NetworkOperators;

namespace Vcx.Tray;

public sealed record HotspotStatus(bool Available, bool On, int Clients, int MaxClients, string? Ssid, bool? TurnsOffWhenIdle, string? Error)
{
    public string MenuText => !Available ? $"Mobile hotspot: unavailable{(Error != null ? $" ({Error})" : "")}"
        : On ? $"Mobile hotspot: on{(Ssid != null ? $" ({Ssid})" : "")}, {Clients} device{(Clients == 1 ? "" : "s")}"
        : "Mobile hotspot: off";
}

/// <summary>
/// Windows Mobile Hotspot via WinRT NetworkOperatorTetheringManager, shared from the internet connection profile
/// (Ethernet). Query is read-only. Start/keep-on change the hotspot and are only run on an explicit menu click.
/// </summary>
public static class Hotspot
{
    private static NetworkOperatorTetheringManager? Manager(out string? error)
    {
        error = null;
        var profile = NetworkInformation.GetInternetConnectionProfile();
        if (profile == null) { error = "no internet connection to share"; return null; }
        var cap = NetworkOperatorTetheringManager.GetTetheringCapabilityFromConnectionProfile(profile);
        if (cap != TetheringCapability.Enabled) { error = $"not supported here ({cap})"; return null; }
        return NetworkOperatorTetheringManager.CreateFromConnectionProfile(profile);
    }

    public static HotspotStatus Query()
    {
        try
        {
            var m = Manager(out var err);
            if (m == null) return new HotspotStatus(false, false, 0, 0, null, null, err);
            bool on = m.TetheringOperationalState == TetheringOperationalState.On;
            string? ssid = null;
            try { ssid = m.GetCurrentAccessPointConfiguration()?.Ssid; } catch { /* optional */ }
            bool? idleOff = null;
            try { idleOff = NetworkOperatorTetheringManager.IsNoConnectionsTimeoutEnabled(); } catch { /* older Windows */ }
            return new HotspotStatus(true, on, (int)m.ClientCount, (int)m.MaxClientCount, ssid, idleOff, null);
        }
        catch (Exception ex)
        {
            return new HotspotStatus(false, false, 0, 0, null, null, ex.Message);
        }
    }

    /// <summary>Turns the hotspot on (user action only). Returns a message for the user.</summary>
    public static async Task<string> StartAsync()
    {
        try
        {
            var m = Manager(out var err);
            if (m == null) return $"Can't start the hotspot: {err}";
            if (m.TetheringOperationalState == TetheringOperationalState.On) return "Mobile hotspot is already on.";
            var r = await m.StartTetheringAsync();
            return r.Status == TetheringOperationStatus.Success
                ? "Mobile hotspot started."
                : $"Can't start the hotspot: {r.Status}{(string.IsNullOrEmpty(r.AdditionalErrorMessage) ? "" : " - " + r.AdditionalErrorMessage)}";
        }
        catch (Exception ex)
        {
            return $"Can't start the hotspot: {ex.Message}";
        }
    }

    /// <summary>Turns off "power saving" (hotspot switching itself off when no devices are connected). User action only.</summary>
    public static async Task<string> KeepOnWhenIdleAsync()
    {
        try
        {
            await NetworkOperatorTetheringManager.DisableNoConnectionsTimeoutAsync();
            return NetworkOperatorTetheringManager.IsNoConnectionsTimeoutEnabled()
                ? "Windows did not change the hotspot power-saving setting."
                : "Hotspot will now stay on when no devices are connected.";
        }
        catch (Exception ex)
        {
            return $"Can't change the hotspot power-saving setting: {ex.Message}";
        }
    }
}
