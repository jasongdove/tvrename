using Serilog;

namespace TvRename.Subtitles;

public class ReferenceSubtitleDownloader
{
    private readonly string _imdb;
    private readonly int _seasonNumber;
    private readonly string _targetFolder;

    public ReferenceSubtitleDownloader(string imdb, int seasonNumber, string targetFolder)
    {
        _imdb = imdb;
        _seasonNumber = seasonNumber;
        _targetFolder = targetFolder;
    }

    public async Task<int> Download()
    {
        string referenceFolder = Path.Combine(_targetFolder, ".tvrename", "reference");
        if (!Directory.Exists(referenceFolder))
        {
            Directory.CreateDirectory(referenceFolder);
        }

        Option<int> maybeExpectedEpisodeCount = await GetExpectedEpisodeCount(referenceFolder);
        int actualEpisodeCount = GetActualEpisodeCount(referenceFolder);

        if (maybeExpectedEpisodeCount == actualEpisodeCount)
        {
            return actualEpisodeCount;
        }

        // Log.Information("Actual episode count {Count}", actualEpisodeCount);
        // TODO: search open subtitles
        var client = new OpenSubtitlesApiClient();
        Either<Exception, List<EpisodeSearchResults>> maybeResults = await client.Search(_imdb, _seasonNumber);

        foreach (List<EpisodeSearchResults> results in maybeResults.RightToSeq())
        {
            if (results.Count == 0)
            {
                Log.Fatal(
                    "OpenSubtitles returned no results for imdb {Imdb} season {SeasonNumber}",
                    _imdb,
                    _seasonNumber);

                return 0;
            }

            int lastEpisode = results.Max(e => e.EpisodeNumber);
            Log.Information("Found {Count} episodes to download", lastEpisode);
            await WriteEpisodeCount(referenceFolder, lastEpisode);
        }

        return 0;
    }

    private static async Task<Option<int>> GetExpectedEpisodeCount(string referenceFolder)
    {
        string episodeCountFile = Path.Combine(referenceFolder, ".episode-count");
        if (File.Exists(episodeCountFile))
        {
            if (int.TryParse(await File.ReadAllTextAsync(episodeCountFile), out int episodeCount))
            {
                return episodeCount;
            }
        }

        return None;
    }

    private static int GetActualEpisodeCount(string referenceFolder) =>
        Directory.EnumerateFiles(referenceFolder, "*.srt", SearchOption.TopDirectoryOnly).Count();

    private static async Task WriteEpisodeCount(string referenceFolder, int episodeCount)
    {
        string episodeCountFile = Path.Combine(referenceFolder, ".episode-count");
        await File.WriteAllTextAsync(episodeCountFile, episodeCount.ToString());
    }
}
