using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;
using SubtitlesParser.Classes;
using SubtitlesParser.Classes.Parsers;
using TvRename.Models;

namespace TvRename.Subtitles;

public partial class ReferenceSubtitleDownloader
{
    private readonly ILogger<ReferenceSubtitleDownloader> _logger;

    private readonly OpenSubtitlesApiClient _openSubtitlesApiClient;

    public ReferenceSubtitleDownloader(
        OpenSubtitlesApiClient openSubtitlesApiClient,
        ILogger<ReferenceSubtitleDownloader> logger)
    {
        _openSubtitlesApiClient = openSubtitlesApiClient;
        _logger = logger;
    }

    public async Task<int> Download(ValidatedParameters parameters, CancellationToken cancellationToken)
    {
        string referenceFolder = Path.Combine(parameters.Folder, ".tvrename", "reference");
        if (!Directory.Exists(referenceFolder))
        {
            Directory.CreateDirectory(referenceFolder);
        }

        Option<int> maybeExpectedEpisodeCount = await GetExpectedEpisodeCount(referenceFolder);
        foreach (int expectedEpisodeCount in maybeExpectedEpisodeCount)
        {
            return expectedEpisodeCount;
        }

        // search open subtitles
        Either<Exception, List<EpisodeSearchResults>> maybeResults =
            await _openSubtitlesApiClient.Search(parameters.Imdb, parameters.Season, cancellationToken);

        foreach (List<EpisodeSearchResults> results in maybeResults.RightToSeq())
        {
            if (results.Count == 0)
            {
                _logger.LogError(
                    "OpenSubtitles returned no results for show {ShowTitle} imdb {Imdb} season {SeasonNumber}",
                    parameters.Title,
                    parameters.Imdb,
                    parameters.Season);

                return 0;
            }

            int episodeCount = results.Max(e => e.EpisodeNumber) - results.Min(e => e.EpisodeNumber) + 1;
            _logger.LogInformation(
                "{ShowTitle} season {SeasonNumber} has {Count} episodes",
                parameters.Title,
                parameters.Season,
                episodeCount);
            await WriteEpisodeCount(referenceFolder, episodeCount);

            await DownloadEpisodes(referenceFolder, parameters.Title, parameters.Season, results, cancellationToken);

            return episodeCount;
        }

        return 0;
    }

    private static async Task<Option<int>> GetExpectedEpisodeCount(string referenceFolder)
    {
        string episodeCountFile = Path.Combine(referenceFolder, ".episode-count");
        if (File.Exists(episodeCountFile) && int.TryParse(
                await File.ReadAllTextAsync(episodeCountFile),
                out int episodeCount))
        {
            return episodeCount;
        }

        return None;
    }

    private static async Task WriteEpisodeCount(string referenceFolder, int episodeCount)
    {
        string episodeCountFile = Path.Combine(referenceFolder, ".episode-count");
        await File.WriteAllTextAsync(episodeCountFile, episodeCount.ToString());
    }

    private async Task DownloadEpisodes(
        string referenceFolder,
        string showTitle,
        int seasonNumber,
        List<EpisodeSearchResults> results,
        CancellationToken cancellationToken)
    {
        var parser = new SrtParser();

        using var client = new HttpClient();

        foreach (EpisodeSearchResults episode in results)
        {
            if (cancellationToken.IsCancellationRequested)
            {
                return;
            }

            IOrderedEnumerable<SearchResult> sorted = episode.SearchResults
                .OrderBy(s => s.IsWebDl ? 0 : 1)
                .ThenByDescending(s => s.Score);

            string targetFile = Path.Combine(
                referenceFolder,
                $"{showTitle} - s{seasonNumber:00}e{episode.EpisodeNumber:00}.srt");

            if (!File.Exists(targetFile))
            {
                foreach (SearchResult searchResult in sorted.Filter(s => !string.IsNullOrWhiteSpace(s.SubDownloadLink)))
                {
                    if (cancellationToken.IsCancellationRequested)
                    {
                        return;
                    }

                    _logger.LogInformation("Downloading {Url}", searchResult.SubDownloadLink);

                    string tempFile = Path.GetTempFileName();
                    Stream stream = await client.GetStreamAsync(
                        new Uri(searchResult.SubDownloadLink!),
                        cancellationToken);
                    await using (var fs = new FileStream(tempFile, FileMode.OpenOrCreate))
                    {
                        await using (var gzs = new GZipStream(stream, CompressionMode.Decompress))
                        {
                            await gzs.CopyToAsync(fs, cancellationToken);
                        }
                    }

                    string[] lines = await File.ReadAllLinesAsync(tempFile, cancellationToken);
                    string cleaned = CleanupLines(lines);
                    await File.WriteAllTextAsync(tempFile, cleaned, cancellationToken);

                    try
                    {
                        await using (var ms = new MemoryStream(Encoding.UTF8.GetBytes(cleaned)))
                        {
                            Option<List<SubtitleItem>> _ = parser.ParseStream(ms, Encoding.UTF8);
                        }
                    }
                    catch (Exception ex)
                    {
                        _logger.LogError(ex, "Failed to validate subtitle {TempFile}", tempFile);
                        continue;
                    }

                    _logger.LogInformation("Validated as {SubtitleFile}", Path.GetFileName(targetFile));
                    File.Move(tempFile, targetFile);
                    break;
                }
            }
        }
    }

    private string CleanupLines(IEnumerable<string> lines)
    {
        var sb = new StringBuilder();

        // many subrip files are formatted with timestamps like 00:00:0,000 or 00:00:00,00 which isn't valid for the parser
        // we don't really care about timestamps, though, so we'll try to make it valid
        foreach (string line in lines)
        {
            string nextLine = line;

            Match m1 = BadSeconds().Match(line);
            if (m1.Success)
            {
                nextLine = line.Replace(
                    m1.Value,
                    $"{m1.Groups[1].Value}:{m1.Groups[2].Value}:0{m1.Groups[3].Value},{m1.Groups[4].Value}");
            }

            Match m2 = BadMilliseconds().Match(line);
            if (m2.Success)
            {
                nextLine = line.Replace(
                    m2.Value,
                    $"{m2.Groups[1].Value}:{m2.Groups[2].Value}:{m2.Groups[3].Value},{m2.Groups[4].Value}0");
            }

            sb.AppendLine(nextLine);
        }

        sb.AppendLine();
        return sb.ToString();
    }

    [GeneratedRegex(@"([\d]{2}):([\d]{2}):([\d]{2}),([\d]{2})(?!\d)")]
    private static partial Regex BadMilliseconds();

    [GeneratedRegex(@"([\d]{2}):([\d]{2}):(\d),([\d]{2,3})")]
    private static partial Regex BadSeconds();
}
