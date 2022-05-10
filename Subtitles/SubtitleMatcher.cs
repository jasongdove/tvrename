using TvRename.Models;

namespace TvRename.Subtitles;

public static class SubtitleMatcher
{
    public static async Task<Option<MatchedEpisode>> Match(
        List<ReferenceSubtitles> referenceSubtitles,
        List<string> extractedLines) =>
        referenceSubtitles.Map(
                r =>
                {
                    int count = extractedLines.Count(
                        l => r.Contents.Contains(l, StringComparison.InvariantCultureIgnoreCase));
                    var confidence = (int)(count * 1.0 / extractedLines.Count * 100.0);
                    return new MatchedEpisode(r.SeasonNumber, r.EpisodeNumber, confidence);
                })
            .OrderByDescending(m => m.Confidence)
            .HeadOrNone();
}
