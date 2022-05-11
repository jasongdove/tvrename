using TvRename.Models;

namespace TvRename.Subtitles;

public static class SubtitleMatcher
{
    public static Option<MatchedEpisode> Match(
        IEnumerable<ReferenceSubtitles> referenceSubtitles,
        List<string> extractedLines) =>
        referenceSubtitles.Map(
                r =>
                {
                    int count = extractedLines.Count(
                        l => r.Contents.Contains(l, StringComparison.InvariantCultureIgnoreCase));
                    int confidence = Math.Clamp((int)(count * 1.0 / r.Lines * 100.0), 0, 100);
                    return new MatchedEpisode(r.SeasonNumber, r.EpisodeNumber, confidence);
                })
            .OrderByDescending(m => m.Confidence)
            .HeadOrNone();
}
