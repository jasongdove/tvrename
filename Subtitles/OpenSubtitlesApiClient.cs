using Refit;

namespace TvRename.Subtitles;

public class OpenSubtitlesApiClient
{
    private readonly ILogger<OpenSubtitlesApiClient> _logger;

    public OpenSubtitlesApiClient(ILogger<OpenSubtitlesApiClient> logger) => _logger = logger;

    public async Task<Either<Exception, List<EpisodeSearchResults>>> Search(string imdb, int seasonNumber)
    {
        try
        {
            IOpenSubtitlesApi service = RestService.For<IOpenSubtitlesApi>("https://rest.opensubtitles.org");
            var cts = new CancellationTokenSource();
            cts.CancelAfter(TimeSpan.FromSeconds(5));
            return await service.Search(imdb, seasonNumber, cts.Token).Map(Project);
        }
        catch (OperationCanceledException ex)
        {
            _logger.LogError(ex, "Timeout searching OpenSubtitles");
            return ex;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error searching OpenSubtitles");
            return ex;
        }
    }

    private static List<EpisodeSearchResults> Project(List<SearchResult> searchResults) =>
        searchResults
            .Filter(s => s.SubFileName is not null && s.SubFormat is not null && s.SeriesEpisode is not null)
            .Filter(s => !s.SubFileName!.Contains(".ita.", StringComparison.InvariantCultureIgnoreCase))
            .Filter(s => s.SubFormat!.Equals("srt", StringComparison.InvariantCultureIgnoreCase))
            .GroupBy(s => int.Parse(s.SeriesEpisode!))
            .Map(g => new EpisodeSearchResults(g.Key, g.ToList()))
            .OrderBy(esr => esr.EpisodeNumber)
            .ToList();
}
