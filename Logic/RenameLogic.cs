using System.Text;
using System.Text.RegularExpressions;
using LanguageExt.UnsafeValueAccess;
using SubtitlesParser.Classes;
using SubtitlesParser.Classes.Parsers;
using TvRename.Classifier;
using TvRename.Models;
using TvRename.Subtitles;

namespace TvRename.Logic;

public class RenameLogic
{
    private static readonly Regex ReferencePattern = new(@".*s([\d]{2})e([\d]{2}).*");
    private static readonly Regex SeasonPattern = new(@".*([\d]{1,4}).*");

    private readonly ILogger<RenameLogic> _logger;

    private readonly ReferenceSubtitleDownloader _referenceSubtitleDownloader;
    private readonly SubtitleExtractor _subtitleExtractor;
    private readonly SubtitleProcessor _subtitleProcessor;

    public RenameLogic(
        ReferenceSubtitleDownloader referenceSubtitleDownloader,
        SubtitleExtractor subtitleExtractor,
        SubtitleProcessor subtitleProcessor,
        ILogger<RenameLogic> logger)
    {
        _referenceSubtitleDownloader = referenceSubtitleDownloader;
        _subtitleExtractor = subtitleExtractor;
        _subtitleProcessor = subtitleProcessor;
        _logger = logger;
    }

    public async Task<int> Run(
        string imdb,
        string? title,
        int? season,
        string folder,
        int? confidence,
        bool dryRun,
        CancellationToken cancellationToken)
    {
        try
        {
            return await RunImpl(imdb, title, season, folder, confidence, dryRun, cancellationToken);
        }
        catch (Exception ex) when (ex is TaskCanceledException or OperationCanceledException)
        {
            return 0;
        }
    }

    private async Task<int> RunImpl(
        string imdb,
        string? title,
        int? season,
        string folder,
        int? confidence,
        bool dryRun,
        CancellationToken cancellationToken)
    {
        string fullPath = Path.GetFullPath(folder);

        // TODO: validate parameters
        if (!Directory.Exists(fullPath))
        {
            Console.WriteLine("folder must be a directory");
        }

        string verifiedFileName = Path.Combine(fullPath, ".tvrename-verified");
        if (File.Exists(verifiedFileName))
        {
            return 0;
        }

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

        // download expected subtitles
        int _ = await _referenceSubtitleDownloader.Download(showTitle, imdb, seasonNumber, fullPath, cancellationToken);

        // load reference subtitles
        List<ReferenceSubtitles> referenceSubtitles = await LoadReferenceSubtitles(fullPath, cancellationToken);

        // find unknown episodes
        foreach (string unknownEpisode in RemuxEpisodeClassifier.FindUnknownEpisodes(fullPath))
        {
            if (cancellationToken.IsCancellationRequested)
            {
                return 2;
            }

            // probe and extract subtitles from episode
            Either<Exception, ExtractedSubtitles> extractResult =
                await _subtitleExtractor.ExtractSubtitles(unknownEpisode, cancellationToken);
            if (extractResult.IsLeft)
            {
                foreach (Exception exception in extractResult.LeftToSeq())
                {
                    _logger.LogError(exception, "Failed to extract subtitles");
                }

                continue;
            }

            // process subtitles
            ExtractedSubtitles extractedSubtitles = extractResult.RightToSeq().Head();
            Either<Exception, List<string>> extractedLines =
                await _subtitleProcessor.ConvertToLines(extractedSubtitles);
            foreach (List<string> lines in extractedLines.RightToSeq())
            {
                foreach (MatchedEpisode match in await SubtitleMatcher.Match(referenceSubtitles, lines))
                {
                    if (match.Confidence >= (confidence ?? 40))
                    {
                        _logger.LogInformation(
                            "Matched to Season {SeasonNumber} Episode {EpisodeNumber} with confidence {Confidence}",
                            match.SeasonNumber,
                            match.EpisodeNumber,
                            match.Confidence);

                        if (!dryRun)
                        {
                            string source =
                                $"{showTitle} - s{match.SeasonNumber:00}e{match.EpisodeNumber:00}.mkv";
                            string dest = Path.Combine(folder, source);

                            if (!File.Exists(dest))
                            {
                                _logger.LogInformation("Renaming {Source} to {Dest}", unknownEpisode, dest);
                                File.Move(unknownEpisode, dest);
                            }
                            else
                            {
                                _logger.LogError(
                                    "Destination file {Dest} already exists; will not overwrite",
                                    dest);
                            }
                        }
                    }
                    else
                    {
                        _logger.LogWarning(
                            "Match failed; confidence of {Confidence} is too low",
                            match.Confidence);
                    }
                }
            }
        }

        return 0;
    }

    private static async Task<List<ReferenceSubtitles>> LoadReferenceSubtitles(
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
                        allLines.AddRange(item.PlaintextLines.Map(s => s.Replace("-", string.Empty)));
                    }

                    var contents = string.Join(' ', allLines);
                    result.Add(new ReferenceSubtitles(seasonNumber, episodeNumber, contents));
                }
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
