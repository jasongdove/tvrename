namespace TvRename.Subtitles;

public class SearchResult
{
    public string SubFileName { get; set; }
    public string? InfoFormat { get; set; }
    public string SubFormat { get; set; }
    public string SeriesSeason { get; set; }
    public string SeriesEpisode { get; set; }
    public string SubDownloadLink { get; set; }
    public double Score { get; set; }

    public bool IsWebDL => Optional(InfoFormat).IfNone(string.Empty).ToLowerInvariant() == "web-dl";
}
