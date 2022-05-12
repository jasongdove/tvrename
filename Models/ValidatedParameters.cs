namespace TvRename.Models;

public record ValidatedParameters(string Imdb, string Title, int Season, string Folder, int Confidence, bool DryRun);
