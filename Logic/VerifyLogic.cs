using TvRename.Classifier;
using TvRename.Models;
using TvRename.Subtitles;

namespace TvRename.Logic;

public class VerifyLogic : BaseLogic
{
    private readonly ILogger<VerifyLogic> _logger;
    private readonly ReferenceSubtitleDownloader _referenceSubtitleDownloader;
    private readonly SubtitleExtractor _subtitleExtractor;
    private readonly SubtitleMatcher _subtitleMatcher;
    private readonly SubtitleProcessor _subtitleProcessor;

    public VerifyLogic(
        ReferenceSubtitleDownloader referenceSubtitleDownloader,
        SubtitleExtractor subtitleExtractor,
        SubtitleProcessor subtitleProcessor,
        SubtitleMatcher subtitleMatcher,
        ILogger<VerifyLogic> logger) : base(logger)
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
        CancellationToken cancellationToken)
    {
        try
        {
            return await ValidateAndRun(imdb, title, season, folder, confidence, false, cancellationToken);
        }
        catch (Exception ex) when (ex is TaskCanceledException or OperationCanceledException)
        {
            return 2;
        }
    }

    protected override async Task<int> Run(ValidatedParameters parameters, CancellationToken cancellationToken)
    {
        // download expected subtitles
        int downloadCount = await _referenceSubtitleDownloader.Download(parameters, cancellationToken);

        // load reference subtitles
        List<ReferenceSubtitles> referenceSubtitles =
            await LoadReferenceSubtitles(parameters.Folder, cancellationToken);

        int referenceCount = referenceSubtitles.Sum(s => s.EpisodeNumbers.Count);
        if (downloadCount != referenceCount)
        {
            _logger.LogError(
                "Available subtitles count {DownloadCount} doesn't match reference subtitles count {ReferenceCount}",
                downloadCount,
                referenceCount);

            return 1;
        }

        List<string> knownEpisodes = RemuxEpisodeClassifier.FindKnownEpisodes(parameters.Folder);
        if (knownEpisodes.Count != referenceSubtitles.Count)
        {
            _logger.LogError(
                "Known episodes count {KnownCount} doesn't match reference subtitles count {ReferenceCount}",
                knownEpisodes.Count,
                referenceSubtitles.Count);

            return 1;
        }

        var success = 0;

        foreach (string knownEpisode in knownEpisodes)
        {
            if (cancellationToken.IsCancellationRequested)
            {
                return 2;
            }

            // probe and extract subtitles from episode
            Either<Exception, List<ExtractedSubtitles>> extractResult =
                await _subtitleExtractor.ExtractSubtitles(knownEpisode, cancellationToken);
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
                string episodeNumbers = match.EpisodeNumbers.Count > 1
                    ? $"e{match.EpisodeNumbers.First():00}-e{match.EpisodeNumbers.Last():00}"
                    : $"e{match.EpisodeNumbers.First():00}";
                string nameSegment = $"s{match.SeasonNumber:00}{episodeNumbers}";

                if (match.Confidence * 100 >= parameters.Confidence)
                {
                    if (knownEpisode.Contains(nameSegment))
                    {
                        _logger.LogInformation(
                            "Verified OK with confidence {Confidence}",
                            Math.Clamp((int)(match.Confidence * 100.0), 0, 100));
                        success++;
                    }
                    else
                    {
                        _logger.LogWarning(
                            "Verify failed; matched s{SeasonNumber:00}{episodeNumbers} with confidence {Confidence}",
                            match.SeasonNumber,
                            episodeNumbers,
                            Math.Clamp((int)(match.Confidence * 100.0), 0, 100));
                    }
                }
                else
                {
                    _logger.LogWarning(
                        "Verify failed; confidence of {Confidence} is too low",
                        Math.Clamp((int)(match.Confidence * 100.0), 0, 100));
                }
            }
        }

        if (success != knownEpisodes.Count)
        {
            _logger.LogError("Some episodes failed to verify");
            return 1;
        }

        _logger.LogInformation("All episodes successfully verified");
        string verifiedFileName = Path.Combine(parameters.Folder, ".tvrename-verified");
        await File.WriteAllTextAsync(verifiedFileName, "OK", cancellationToken);

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
