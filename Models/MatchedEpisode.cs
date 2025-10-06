namespace TvRename.Models;

public record MatchedEpisode(int SeasonNumber, List<int> EpisodeNumbers, double Confidence, double WeightedConfidence);
