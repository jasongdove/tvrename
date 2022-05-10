using System.Text.RegularExpressions;

namespace TvRename.Classifier;

public class RemuxEpisodeClassifier
{
    private static readonly List<string> VideoFileExtensions = new()
    {
        // only mkv is supported at the moment because ffmpeg cannot extract dvd subtitles (idx/sub)

        // ".mpg", ".mp2", ".mpeg", ".mpe", ".mpv", ".ogg", ".mp4",
        // ".m4p", ".m4v", ".avi", ".wmv", ".mov", ".mkv", ".ts", ".webm"

        ".mkv"
    };

    private static readonly Regex KnownPattern = new(@".*s([0-9]{2})e([0-9]{2})\..*");

    public static IEnumerable<string> FindUnknownEpisodes(string targetFolder)
    {
        foreach (string file in Directory.EnumerateFiles(targetFolder, "*.*", SearchOption.TopDirectoryOnly)
                     .Filter(f => VideoFileExtensions.Any(f.EndsWith))
                     .Filter(f => KnownPattern.Match(f).Success == false))
        {
            yield return file;
        }
    }
}
