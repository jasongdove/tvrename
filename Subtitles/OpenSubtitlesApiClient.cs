using Refit;
using Serilog;

namespace TvRename.Subtitles;

public class OpenSubtitlesApiClient
{
    public async Task<Either<Exception, List<EpisodeSearchResults>>> Search(string imdb, int seasonNumber)
    {
        try
        {
            // var httpClient = new HttpClient(new HttpClientDiagnosticsHandler(new HttpClientHandler()))
            //     { BaseAddress = new Uri("https://rest.opensubtitles.org") };

            IOpenSubtitlesApi service = RestService.For<IOpenSubtitlesApi>("https://rest.opensubtitles.org");
            // IOpenSubtitlesApi service = RestService.For<IOpenSubtitlesApi>(httpClient);
            var cts = new CancellationTokenSource();
            cts.CancelAfter(TimeSpan.FromSeconds(5));
            return await service.Search(imdb, seasonNumber, cts.Token).Map(Project);
        }
        catch (OperationCanceledException ex)
        {
            Log.Error(ex, "Timeout searching OpenSubtitles");
            return ex;
        }
        catch (Exception ex)
        {
            Log.Error(ex, "Error searching OpenSubtitles");
            return ex;
        }
    }

    private static List<EpisodeSearchResults> Project(List<SearchResult> searchResults) =>
        searchResults
            .Filter(s => !s.SubFileName.Contains(".ita.", StringComparison.InvariantCultureIgnoreCase))
            .Filter(s => s.SubFormat.Equals("srt", StringComparison.InvariantCultureIgnoreCase))
            .GroupBy(s => int.Parse(s.SeriesEpisode))
            .Map(g => new EpisodeSearchResults(g.Key, g.ToList()))
            .OrderBy(esr => esr.EpisodeNumber)
            .ToList();
}
