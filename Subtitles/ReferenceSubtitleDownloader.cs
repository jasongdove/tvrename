using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;
using Serilog;
using SubtitlesParser.Classes;
using SubtitlesParser.Classes.Parsers;

namespace TvRename.Subtitles;

public class ReferenceSubtitleDownloader
{
    private readonly Regex _badMilliseconds = new(@"([\d]{2}):([\d]{2}):([\d]{2}),([\d]{2})(?!\d)");
    private readonly Regex _badSeconds = new(@"([\d]{2}):([\d]{2}):(\d),([\d]{2,3})");
    private readonly string _imdb;
    private readonly int _seasonNumber;

    private readonly string _showTitle;
    private readonly string _targetFolder;

    public ReferenceSubtitleDownloader(string showTitle, string imdb, int seasonNumber, string targetFolder)
    {
        _showTitle = showTitle;
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

        // search open subtitles
        var client = new OpenSubtitlesApiClient();
        Either<Exception, List<EpisodeSearchResults>> maybeResults = await client.Search(_imdb, _seasonNumber);

        foreach (List<EpisodeSearchResults> results in maybeResults.RightToSeq())
        {
            if (results.Count == 0)
            {
                Log.Fatal(
                    "OpenSubtitles returned no results for show {ShowTitle} imdb {Imdb} season {SeasonNumber}",
                    _showTitle,
                    _imdb,
                    _seasonNumber);

                return 0;
            }

            int episodeCount = results.Max(e => e.EpisodeNumber) - results.Min(e => e.EpisodeNumber) + 1;
            Log.Information(
                "{ShowTitle} season {SeasonNumber} has {Count} episodes",
                _showTitle,
                _seasonNumber,
                episodeCount);
            await WriteEpisodeCount(referenceFolder, episodeCount);

            await DownloadEpisodes(referenceFolder, results);
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

    private async Task DownloadEpisodes(string referenceFolder, List<EpisodeSearchResults> results)
    {
        var parser = new SrtParser();

        using var client = new HttpClient();

        foreach (EpisodeSearchResults episode in results)
        {
            IOrderedEnumerable<SearchResult> sorted = episode.SearchResults
                .OrderBy(s => s.IsWebDl ? 0 : 1)
                .ThenByDescending(s => s.Score);

            string targetFile = Path.Combine(
                referenceFolder,
                $"{_showTitle} - s{_seasonNumber:00}e{episode.EpisodeNumber:00}.srt");

            if (!File.Exists(targetFile))
            {
                foreach (SearchResult searchResult in sorted.Filter(s => !string.IsNullOrWhiteSpace(s.SubDownloadLink)))
                {
                    Log.Information("Downloading {Url}", searchResult.SubDownloadLink);

                    string tempFile = Path.GetTempFileName();
                    Stream stream = await client.GetStreamAsync(new Uri(searchResult.SubDownloadLink!));
                    await using (var fs = new FileStream(tempFile, FileMode.OpenOrCreate))
                    {
                        await using (var gzs = new GZipStream(stream, CompressionMode.Decompress))
                        {
                            await gzs.CopyToAsync(fs);
                        }
                    }

                    string[] lines = await File.ReadAllLinesAsync(tempFile);
                    string cleaned = CleanupLines(lines);
                    await File.WriteAllTextAsync(tempFile, cleaned);

                    try
                    {
                        using (var ms = new MemoryStream(Encoding.UTF8.GetBytes(cleaned)))
                        {
                            Option<List<SubtitleItem>> _ = parser.ParseStream(ms, Encoding.UTF8);
                        }
                    }
                    catch (Exception ex)
                    {
                        Log.Error(ex, "Failed to validate subtitle {TempFile}", tempFile);
                        continue;
                    }

                    Log.Information("Validated as {SubtitleFile}", Path.GetFileName(targetFile));
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

            Match m1 = _badSeconds.Match(line);
            if (m1.Success)
            {
                nextLine = line.Replace(
                    m1.Value,
                    $"{m1.Groups[1].Value}:{m1.Groups[2].Value}:0{m1.Groups[3].Value},{m1.Groups[4].Value}");
            }

            Match m2 = _badMilliseconds.Match(line);
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
}
