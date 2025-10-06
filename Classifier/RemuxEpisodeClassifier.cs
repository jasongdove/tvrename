using System.Text.RegularExpressions;

namespace TvRename.Classifier;

public static partial class RemuxEpisodeClassifier
{
    private static readonly List<string> VideoFileExtensions =
    [
        // only mkv is supported at the moment because ffmpeg cannot extract dvd subtitles (idx/sub)

        // ".mpg", ".mp2", ".mpeg", ".mpe", ".mpv", ".ogg", ".mp4",
        // ".m4p", ".m4v", ".avi", ".wmv", ".mov", ".mkv", ".ts", ".webm"

        ".mkv"
    ];

    public static List<string> FindKnownEpisodes(string targetFolder) =>
        Directory.EnumerateFiles(targetFolder, "*.*", SearchOption.TopDirectoryOnly)
            .Filter(f => VideoFileExtensions.Any(f.EndsWith))
            .Filter(f => KnownPatternRegex().Match(f).Success)
            .OrderBy(f => f)
            .ToList();

    public static IEnumerable<string> FindUnknownEpisodes(string targetFolder)
    {
        foreach (string file in Directory.EnumerateFiles(targetFolder, "*.*", SearchOption.TopDirectoryOnly)
                     .Filter(f => VideoFileExtensions.Any(f.EndsWith))
                     .Filter(f => !KnownPatternRegex().Match(f).Success)
                     .OrderBy(f => f))
        {
            yield return file;
        }
    }

    [GeneratedRegex(@".*s([0-9]{2})e([0-9]{2})(?:-e([0-9]{2}))?\..*")]
    private static partial Regex KnownPatternRegex();
}
