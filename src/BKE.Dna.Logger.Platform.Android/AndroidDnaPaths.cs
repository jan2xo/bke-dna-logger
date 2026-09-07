using Android.Content;

namespace BKE.Dna.Logger.Platform.Android;

internal static class AndroidDnaPaths
{
    public static string CaptureRoot(Context context)
    {
        ArgumentNullException.ThrowIfNull(context);
        var filesDirectory = context.FilesDir?.AbsolutePath
            ?? throw new InvalidOperationException("Android app files directory is unavailable.");

        return Path.Combine(filesDirectory, "dna", "captures");
    }
}
