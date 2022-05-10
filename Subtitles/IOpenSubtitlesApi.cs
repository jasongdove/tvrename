using Refit;

namespace TvRename.Subtitles;

[Headers("Accept: application/json", "User-Agent: tvrename v1")]
public interface IOpenSubtitlesApi
{
    [Get("/search/imdbid-{imdb}/season-{seasonNumber}/sublanguageid-eng")]
    public Task<List<SearchResult>> Search(string imdb, int seasonNumber, CancellationToken cancellationToken);
}
