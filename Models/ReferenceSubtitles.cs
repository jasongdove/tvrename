namespace TvRename.Models;

public record ReferenceSubtitles(int SeasonNumber, List<int> EpisodeNumbers, string Contents, int Lines);
