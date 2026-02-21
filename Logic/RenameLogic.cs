using TvRename.Classifier;
using TvRename.Models;
using TvRename.Subtitles;

namespace TvRename.Logic;

public class RenameLogic : BaseLogic
{
    private readonly ILogger<RenameLogic> _logger;

    private readonly ReferenceSubtitleDownloader _referenceSubtitleDownloader;
    private readonly SubtitleExtractor _subtitleExtractor;
    private readonly SubtitleMatcher _subtitleMatcher;
    private readonly SubtitleProcessor _subtitleProcessor;

    public RenameLogic(
        ReferenceSubtitleDownloader referenceSubtitleDownloader,
        SubtitleExtractor subtitleExtractor,
        SubtitleProcessor subtitleProcessor,
        SubtitleMatcher subtitleMatcher,
        ILogger<RenameLogic> logger)
        : base(logger)
    {
        _referenceSubtitleDownloader = referenceSubtitleDownloader;
        _subtitleExtractor = subtitleExtractor;
        _subtitleProcessor = subtitleProcessor;
        _subtitleMatcher = subtitleMatcher;
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
            return await ValidateAndRun(imdb, title, season, folder, confidence, dryRun, cancellationToken);
        }
        catch (Exception ex) when (ex is TaskCanceledException or OperationCanceledException)
        {
            return 2;
        }
    }

    protected override async Task<int> Run(ValidatedParameters parameters, CancellationToken cancellationToken)
    {
        // download expected subtitles
        int _ = await _referenceSubtitleDownloader.Download(parameters, cancellationToken);

        // load reference subtitles
        List<ReferenceSubtitles> referenceSubtitles =
            await LoadReferenceSubtitles(parameters.Folder, cancellationToken);

        // find unknown episodes
        foreach (string unknownEpisode in RemuxEpisodeClassifier.FindUnknownEpisodes(parameters.Folder))
        {
            if (cancellationToken.IsCancellationRequested)
            {
                return 2;
            }

            // ignore files that no longer exist
            if (!File.Exists(unknownEpisode))
            {
                continue;
            }

            // probe and extract subtitles from episode
            Either<Exception, List<ExtractedSubtitles>> extractResult =
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
            List<ExtractedSubtitles> extractedSubtitles = extractResult.RightToSeq().Head();
            IEnumerable<Task<Option<MatchedEpisode>>>? matchTasks =
                extractedSubtitles.Map(es => Match(referenceSubtitles, es, cancellationToken));
            Option<MatchedEpisode>[] matches = await Task.WhenAll(matchTasks);
            Option<MatchedEpisode> maybeBestMatch = matches.Somes()
                .OrderByDescending(m => m.Confidence)
                .HeadOrNone();
            foreach (MatchedEpisode match in maybeBestMatch)
            {
                if (match.Confidence * 100 >= parameters.Confidence)
                {
                    _logger.LogInformation(
                        "Matched s{SeasonNumber:00}e{EpisodeNumbers} with confidence {Confidence}",
                        match.SeasonNumber,
                        string.Join("-e", match.EpisodeNumbers.Select(e => $"{e:00}")),
                        Math.Clamp((int)(match.Confidence * 100.0), 0, 100));

                    if (!parameters.DryRun)
                    {
                        string episodeNumbers = string.Join("-e", match.EpisodeNumbers.Select(e => $"{e:00}"));
                        string source =
                            $"{parameters.Title} - s{match.SeasonNumber:00}e{episodeNumbers}.mkv";
                        string dest = Path.Combine(parameters.Folder, source);

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
                        Math.Clamp((int)(match.Confidence * 100.0), 0, 100));
                }
            }
        }

        return 0;
    }

    private async Task<Option<MatchedEpisode>> Match(
        List<ReferenceSubtitles> referenceSubtitles,
        ExtractedSubtitles extractedSubtitles,
        CancellationToken cancellationToken)
    {
        Either<Exception, List<string>> extractedLines =
            await _subtitleProcessor.ConvertToLines(extractedSubtitles, cancellationToken);
        foreach (List<string> lines in extractedLines.RightToSeq())
        {
            return _subtitleMatcher.Match(referenceSubtitles, lines);
        }

        return None;
    }
}
