package tvrename.logic

import cats.effect.IO
import tvrename._
import tvrename.classifier._
import tvrename.config.BroadcastJobConfig

class BroadcastCoreLogic(config: BroadcastJobConfig, tvdb: TVDB, classifier: BroadcastEpisodeClassifier, logger: Logger)
    extends CoreLogic {
  def run(): IO[Unit] =
    for {
      unknownEpisodes <- classifier.findUnknownEpisodes()
    } yield {
      val episodesForSeason = tvdb.episodesForSeason(config.seriesId, config.seasonNumber)
      val aired = new Aired(episodesForSeason)

      unknownEpisodes.foreach {
        case episode @ aired(episodeNumber) =>
          for {
            result <- classifier.renameEpisode(episode, config.seasonNumber, episodeNumber)
            _ <- logger.info(s"Moved ${result._1} to ${result._2}")
          } yield ()
        case episode =>
          logger.warn(s"Unable to find episode number for ${episode.fileName}")
      }
    }
}

class Aired(episodes: Seq[EpisodeData]) {
  def unapply(episode: UnknownBroadcastEpisode): Option[Int] =
    episodes
      .find(_.firstAired == episode.date.toString)
      .map(_.airedEpisodeNumber)
}
