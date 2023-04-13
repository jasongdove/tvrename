using System.Text;
using System.Text.RegularExpressions;
using LanguageExt.UnsafeValueAccess;
using SubtitlesParser.Classes;
using SubtitlesParser.Classes.Parsers;
using TvRename.Models;

namespace TvRename.Logic;

public abstract class BaseLogic
{
    private static readonly Regex ReferencePattern = new(@".*(?:s)(\d+)[ex](\d+).*", RegexOptions.IgnoreCase);
    private static readonly Regex SeasonPattern = new(@"(\d+)");

    private readonly ILogger _logger;

    protected BaseLogic(ILogger logger) => _logger = logger;

    protected async Task<int> ValidateAndRun(
        string imdb,
        string? title,
        int? season,
        string folder,
        int? confidence,
        bool dryRun,
        CancellationToken cancellationToken)
    {
        // folder path must exist and be a directory
        string fullPath = Path.GetFullPath(folder);
        if (!Directory.Exists(fullPath))
        {
            _logger.LogCritical("Folder {Folder} must be a directory", fullPath);
            return 1;
        }

        // if contents have already been verified, bail out        
        string verifiedFileName = Path.Combine(fullPath, ".tvrename-verified");
        if (File.Exists(verifiedFileName))
        {
            return 0;
        }

        // try to get title and season number from folders if not passed in
        Option<string> maybeShowTitle = GetTitleFromFolder(Optional(title), fullPath);
        Option<int> maybeSeasonNumber = GetSeasonFromFolder(Optional(season), fullPath);
        if (maybeShowTitle.IsNone || maybeSeasonNumber.IsNone)
        {
            _logger.LogError(
                "Unable to detect show title {ShowTitle} or season number {SeasonNumber}",
                maybeShowTitle,
                maybeSeasonNumber);
            return 1;
        }

        string showTitle = maybeShowTitle.ValueUnsafe();
        int seasonNumber = maybeSeasonNumber.ValueUnsafe();

        _logger.LogInformation("Detected show title {ShowTitle}", showTitle);
        _logger.LogInformation("Detected season number {SeasonNumber}", seasonNumber);

        var parameters = new ValidatedParameters(
            imdb,
            showTitle,
            seasonNumber,
            fullPath,
            confidence ?? 40,
            dryRun);

        return await Run(parameters, cancellationToken);
    }

    protected abstract Task<int> Run(ValidatedParameters parameters, CancellationToken cancellationToken);

    protected static async Task<List<ReferenceSubtitles>> LoadReferenceSubtitles(
        string targetFolder,
        CancellationToken cancellationToken)
    {
        var parser = new SrtParser();
        var result = new List<ReferenceSubtitles>();

        string referenceFolder = Path.Combine(targetFolder, ".tvrename", "reference");
        foreach (string referenceFile in Directory.EnumerateFiles(
                     referenceFolder,
                     "*.srt",
                     SearchOption.TopDirectoryOnly))
        {
            cancellationToken.ThrowIfCancellationRequested();

            Match match = ReferencePattern.Match(referenceFile);
            if (match.Success)
            {
                var seasonNumber = int.Parse(match.Groups[1].Value);
                var episodeNumber = int.Parse(match.Groups[2].Value);
                await using FileStream fs = File.OpenRead(referenceFile);
                foreach (List<SubtitleItem> parsed in Optional(parser.ParseStream(fs, Encoding.UTF8)))
                {
                    var allLines = new List<string>();
                    foreach (SubtitleItem item in parsed)
                    {
                        allLines.AddRange(
                            item.PlaintextLines.Map(s => s.Replace("-", string.Empty).ToLowerInvariant()));
                    }

                    var contents = string.Join(' ', allLines);
                    result.Add(new ReferenceSubtitles(seasonNumber, episodeNumber, contents, allLines.Count));
                }
            }
        }

        foreach (string referenceFile in Directory.EnumerateFiles(
                     referenceFolder,
                     "*.txt",
                     SearchOption.TopDirectoryOnly))
        {
            cancellationToken.ThrowIfCancellationRequested();

            Match match = ReferencePattern.Match(referenceFile);
            if (match.Success)
            {
                var seasonNumber = int.Parse(match.Groups[1].Value);
                var episodeNumber = int.Parse(match.Groups[2].Value);
                await using FileStream fs = File.OpenRead(referenceFile);
                using var sr = new StreamReader(fs, Encoding.UTF8);
                var allLines = new List<string>();
                while (await sr.ReadLineAsync() is { } line)
                {
                    allLines.Add(line.Replace("-", string.Empty).ToLowerInvariant());
                }

                var contents = string.Join(' ', allLines);
                result.Add(new ReferenceSubtitles(seasonNumber, episodeNumber, contents, allLines.Count));
            }
        }

        return result;
    }

    private static Option<string> GetTitleFromFolder(Option<string> maybeTitle, string folder)
    {
        foreach (string title in maybeTitle)
        {
            return title;
        }

        foreach (DirectoryInfo seasonFolder in Optional(new DirectoryInfo(folder)))
        {
            foreach (DirectoryInfo showFolder in Optional(seasonFolder.Parent))
            {
                const string PATTERN = @"^(.*?)[\s.]+?[.\(](\d{4})[.\)].*$";
                Match match = Regex.Match(showFolder.Name, PATTERN);
                return match.Success ? match.Groups[1].Value : showFolder.Name;
            }
        }

        return None;
    }

    private static Option<int> GetSeasonFromFolder(Option<int> maybeSeason, string folder)
    {
        foreach (int season in maybeSeason)
        {
            return season;
        }

        foreach (DirectoryInfo seasonFolder in Optional(new DirectoryInfo(folder)))
        {
            Match seasonMatch = SeasonPattern.Match(seasonFolder.Name);
            if (seasonMatch.Success && int.TryParse(seasonMatch.Groups[1].ValueSpan, out int seasonNumber))
            {
                return seasonNumber;
            }

            if (seasonFolder.Name.EndsWith("specials", StringComparison.OrdinalIgnoreCase))
            {
                return 0;
            }
        }

        return None;
    }
}
