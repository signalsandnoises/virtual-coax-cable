using Vcx.Sender;
using Xunit;

namespace Vcx.Sender.Tests;

public class ToastDebouncerTests
{
    [Fact]
    public void FirstConnectionToasts()
    {
        Assert.True(new ToastDebouncer().OnConnected());
    }

    [Fact]
    public void QuickReconnectIsSilent()
    {
        var t = new ToastDebouncer();
        t.OnConnected();
        t.OnLost(100, "lost");
        Assert.Null(t.Tick(100 + ToastDebouncer.LostDelayS - 1));
        Assert.False(t.OnConnected());
        Assert.Null(t.Tick(200));
    }

    [Fact]
    public void LongLossToastsOnceThenReconnectToasts()
    {
        var t = new ToastDebouncer();
        t.OnConnected();
        t.OnLost(100, "lost");
        Assert.Equal("lost", t.Tick(100 + ToastDebouncer.LostDelayS));
        Assert.Null(t.Tick(200));
        t.OnLost(200, "lost again");
        Assert.Null(t.Tick(300));
        Assert.True(t.OnConnected());
    }
}
