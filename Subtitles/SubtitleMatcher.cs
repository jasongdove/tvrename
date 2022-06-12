using TvRename.Models;

namespace TvRename.Subtitles;

public class SubtitleMatcher
{
    private readonly ILogger<SubtitleMatcher> _logger;

    public SubtitleMatcher(ILogger<SubtitleMatcher> logger) => _logger = logger;

    public Option<MatchedEpisode> Match(
        IEnumerable<ReferenceSubtitles> referenceSubtitles,
        List<string> extractedLines)
    {
        var allMatches = referenceSubtitles.Map(
                r =>
                {
                    int count = extractedLines.Count(l => r.Contents.Contains(l));
                    double confidence = count * 1.0 / r.Lines;
                    return new MatchedEpisode(r.SeasonNumber, r.EpisodeNumber, confidence);
                })
            .OrderByDescending(m => m.Confidence).ToList();

        foreach (MatchedEpisode match in allMatches)
        {
            _logger.LogDebug("{@Match}", match);
        }

        return allMatches.HeadOrNone();
    }
}
