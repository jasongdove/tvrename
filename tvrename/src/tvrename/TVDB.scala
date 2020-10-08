package tvrename

import tvrename.config._

import upickle.default

case class Login(token: String)
case class Links(first: Int, last: Int)
case class EpisodeData(firstAired: String, airedEpisodeNumber: Int)
case class Episodes(links: Links, data: Seq[EpisodeData])

trait TVDB {
  def episodesForSeason(seriesId: SeriesId, seasonNumber: SeasonNumber): Seq[EpisodeData]
}

class TVDBImpl(config: TVDBConfig) extends TVDB {

  implicit val loginR: default.Reader[Login] = upickle.default.macroR[Login]
  implicit val linksR: default.Reader[Links] = upickle.default.macroR[Links]
  implicit val episodeDataR: default.Reader[EpisodeData] = upickle.default.macroR[EpisodeData]
  implicit val episodeR: default.Reader[Episodes] = upickle.default.macroR[Episodes]

  private val baseUrl = "https://api.thetvdb.com"

  private lazy val jwt = upickle.default
    .read[Login](
      requests
        .post(
          s"$baseUrl/login",
          data = ujson
            .Obj(
              "apikey" -> config.apiKey.value,
              "username" -> config.username.value,
              "userkey" -> config.userKey.value
            )
            .render(),
          headers = Map("Content-Type" -> "application/json")
        )
        .text()
    )
    .token

  def episodesForSeason(seriesId: SeriesId, seasonNumber: SeasonNumber): Seq[EpisodeData] = {
    val firstPage = pagedEpisodesForSeason(seriesId, seasonNumber, 1)
    firstPage.data ++ Iterator
      .range(2, firstPage.links.last + 1)
      .flatMap(pagedEpisodesForSeason(seriesId, seasonNumber, _).data)
  }

  private def pagedEpisodesForSeason(seriesId: SeriesId, seasonNumber: SeasonNumber, page: Int): Episodes = {
    upickle.default.read[Episodes](
      requests
        .get(
          s"$baseUrl/series/${seriesId.value.toString}/episodes/query?airedSeason=${seasonNumber.value.toString}&page=${page.toString}",
          headers = Map("Authorization" -> s"Bearer $jwt", "Accept" -> "application/json")
        )
        .text()
    )
  }
}
