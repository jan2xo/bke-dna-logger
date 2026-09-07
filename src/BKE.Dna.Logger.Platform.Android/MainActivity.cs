using Android.App;
using Android.OS;
using Android.Widget;

namespace BKE.Dna.Logger.Platform.Android;

[Activity(MainLauncher = true, Exported = true)]
public sealed class MainActivity : Activity
{
    protected override void OnCreate(Bundle? savedInstanceState)
    {
        base.OnCreate(savedInstanceState);

        var captureRoot = AndroidDnaPaths.CaptureRoot(this);
        Directory.CreateDirectory(captureRoot);

        var status = new TextView(this)
        {
            Text = $"BKE DNA Android foundation ready.\n\nDNA root:\n{captureRoot}\n\nGeckoView runtime adapter: next stacked gate."
        };

        status.SetPadding(48, 64, 48, 48);
        SetContentView(status);
    }
}
