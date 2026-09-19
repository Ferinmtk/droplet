using Droplet.Core.Platform;
using Droplet.Windows.Platform;

namespace Droplet.Windows.Tests;

public sealed class KeyTests
{
    [Theory]
    [InlineData("Enter", 0x0D, false)]
    [InlineData(" ", 0x20, false)]
    [InlineData("ArrowLeft", 0x25, true)]
    [InlineData("Delete", 0x2E, true)]
    [InlineData("PageDown", 0x22, true)]
    [InlineData("a", 0x41, false)]
    [InlineData("Z", 0x5A, false)]
    [InlineData("7", 0x37, false)]
    [InlineData("F1", 0x70, false)]
    [InlineData("F12", 0x7B, false)]
    [InlineData("F24", 0x87, false)]
    [InlineData("Meta", 0x5B, true)]
    [InlineData("MediaPlayPause", 0xB3, true)]
    [InlineData("AudioVolumeMute", 0xAD, true)]
    public void Named_keys_map_to_virtual_keys(string name, int vk, bool extended)
    {
        Assert.True(Keys.TryLookup(name, out var k));
        Assert.Equal(new VirtualKey((ushort)vk, extended), k);
    }

    [Theory]
    [InlineData("F0")]
    [InlineData("F25")]
    [InlineData("Fx")]
    [InlineData("é")]
    [InlineData("/")]
    [InlineData("")]
    [InlineData(null)]
    [InlineData("Hyper")]
    public void Other_names_are_not_keys(string? name) => Assert.False(Keys.TryLookup(name, out _));

    [Fact]
    public void Modifiers_come_in_a_fixed_order_without_duplicates_and_accept_aliases()
    {
        var mods = Keys.Mods(["Win", "shift", "control", "CTRL", "bogus", "option"]);
        Assert.Equal([new VirtualKey(Keys.LControl), new VirtualKey(Keys.LMenu), new VirtualKey(Keys.LShift), new VirtualKey(Keys.LWin, true)], mods);
        Assert.Empty(Keys.Mods(null));
    }
}

public sealed class TranslatorTests
{
    static InputEvent Move(double dx, double dy) => new("move", dx, dy);

    [Fact]
    public void Fractions_of_pixels_carry_over_and_moves_merge()
    {
        var t = new InputTranslator();
        var strokes = t.Translate(Enumerable.Repeat(Move(0.3, -0.3), 10).ToList());
        var m = Assert.Single(strokes);
        Assert.Equal((StrokeKind.Move, 3, -3), (m.Kind, m.Dx, m.Dy));
        Assert.Empty(t.Translate([Move(0.4, 0)]));
        Assert.Equal(1, Assert.Single(t.Translate([Move(0.6, 0)])).Dx);
    }

    [Fact]
    public void Moves_are_clamped_and_nan_is_ignored()
    {
        var t = new InputTranslator();
        Assert.Equal(10000, Assert.Single(t.Translate([Move(1e9, 0)])).Dx);
        Assert.Empty(t.Translate([Move(double.NaN, double.NaN)]));
    }

    [Fact]
    public void Scrolling_down_is_a_negative_wheel_delta_with_fractions_kept()
    {
        var t = new InputTranslator();
        var s = Assert.Single(t.Translate([new InputEvent("scroll", 0, 1)]));
        Assert.Equal((StrokeKind.Wheel, -120), (s.Kind, s.Delta));
        Assert.Empty(t.Translate([new InputEvent("scroll", 0, 0.005)]));
        var h = Assert.Single(t.Translate([new InputEvent("scroll", 0.5, 0)]));
        Assert.Equal((StrokeKind.HWheel, 60), (h.Kind, h.Delta));
    }

    [Fact]
    public void A_click_while_held_releases_first_and_release_all_lets_go()
    {
        var t = new InputTranslator();
        t.Translate([new InputEvent("button", Button: "left", Down: true)]);
        var click = t.Translate([new InputEvent("click", Button: "left", Count: 2)]);
        Assert.Equal([false, true, false, true, false], click.Select(s => s.Down));
        Assert.Empty(t.Release());
        t.Translate([new InputEvent("button", Button: "right", Down: true)]);
        var released = Assert.Single(t.Release());
        Assert.Equal((MouseButton.Right, false), (released.Button, released.Down));
        Assert.Empty(t.Translate([new InputEvent("click", Button: "back")]));
    }

    [Fact]
    public void Clicks_are_capped_at_three() =>
        Assert.Equal(6, new InputTranslator().Translate([new InputEvent("click", Count: 9)]).Count);

    [Fact]
    public void Text_is_typed_as_unicode_with_enter_and_tab_as_keys()
    {
        var t = new InputTranslator();
        var s = t.Translate([new InputEvent("text", Text: "a\r\nb\t\U0001F600")]);
        Assert.Equal(
        [
            StrokeKind.Unicode, StrokeKind.Unicode, StrokeKind.Key, StrokeKind.Key, StrokeKind.Unicode, StrokeKind.Unicode,
            StrokeKind.Key, StrokeKind.Key, StrokeKind.Unicode, StrokeKind.Unicode, StrokeKind.Unicode, StrokeKind.Unicode,
        ], s.Select(x => x.Kind));
        Assert.Equal(Keys.Return, s[2].Key.Vk);
        Assert.Equal(Keys.Tab, s[6].Key.Vk);
        Assert.Equal("\U0001F600", new string([s[8].Unit, s[10].Unit]));
    }

    [Fact]
    public void Shortcuts_press_modifiers_around_the_key_and_unknown_characters_are_typed()
    {
        var t = new InputTranslator();
        var s = t.Translate([new InputEvent("key", Key: "c", Mods: ["ctrl"])]);
        Assert.Equal([(Keys.LControl, true), ((ushort)'C', true), ((ushort)'C', false), (Keys.LControl, false)], s.Select(x => (x.Key.Vk, x.Down)));
        var typed = t.Translate([new InputEvent("key", Key: "é")]);
        Assert.NotEmpty(typed);
        Assert.All(typed, x => Assert.Equal(StrokeKind.Unicode, x.Kind));
        Assert.Empty(t.Translate([new InputEvent("key", Key: "é", Mods: ["alt"])]));
        Assert.Equal(2, t.Translate([new InputEvent("key", Key: "Shift", Mods: ["shift"])]).Count);
    }
}

public sealed class PointerTests
{
    static readonly ScreenRect TwoMonitors = new(-1920, 0, 3840, 1080);

    [Fact]
    public void Steps_stay_inside_the_virtual_screen()
    {
        Assert.Equal(new ScreenPoint(-1920, 0), PointerTracker.Step(new ScreenPoint(0, 0), -5000, -10, TwoMonitors));
        Assert.Equal(new ScreenPoint(1919, 1079), PointerTracker.Step(new ScreenPoint(0, 0), 5000, 5000, TwoMonitors));
    }

    [Fact]
    public void Absolute_coordinates_aim_at_the_middle_of_the_pixel()
    {
        Assert.Equal((8, 30), PointerTracker.Normalize(new ScreenPoint(-1920, 0), TwoMonitors));
        Assert.Equal((65527, 65505), PointerTracker.Normalize(new ScreenPoint(1919, 1079), TwoMonitors));
        Assert.Equal((0, 0), PointerTracker.Normalize(new ScreenPoint(5, 5), default));
    }

    [Fact]
    public void A_stale_cursor_reading_continues_from_the_last_target()
    {
        var p = new PointerTracker();
        var t0 = DateTimeOffset.UnixEpoch;
        Assert.Equal(new ScreenPoint(10, 10), p.Base(new ScreenPoint(10, 10), t0));
        p.Moved(new ScreenPoint(10, 10), new ScreenPoint(20, 20), t0);
        Assert.Equal(new ScreenPoint(20, 20), p.Base(new ScreenPoint(10, 10), t0.AddMilliseconds(10)));
        Assert.Equal(new ScreenPoint(10, 10), p.Base(new ScreenPoint(10, 10), t0.AddMilliseconds(100)));
        Assert.Equal(new ScreenPoint(15, 15), p.Base(new ScreenPoint(15, 15), t0.AddMilliseconds(10)));
    }
}
