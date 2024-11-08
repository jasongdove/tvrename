namespace TvRename.Models;

public record MatchedEpisode(int SeasonNumber, int EpisodeNumber, double Confidence, double WeightedConfidence);
