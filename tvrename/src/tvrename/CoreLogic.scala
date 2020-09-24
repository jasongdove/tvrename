package tvrename

import tvrename.config._
import tvrename.classifier._

trait CoreLogic {
  def run(): Unit
}

class BroadcastCoreLogic(config: BroadcastJobConfig, tvdb: TVDB, classifier: BroadcastEpisodeClassifier, logger: Logger)
    extends CoreLogic {
  def run(): Unit = {
    val unknownEpisodes = classifier.findUnknownEpisodes()
    val episodesForSeason = tvdb.episodesForSeason(config.seriesId, config.seasonNumber)
    val aired = new Aired(episodesForSeason)

    unknownEpisodes.foreach {
      case episode @ aired(episodeNumber) =>
        val (originalName, newName) = classifier.renameEpisode(episode, config.seasonNumber, episodeNumber)
        logger.log(s"Moved $originalName to $newName")
      case episode =>
        logger.log(s"Unable to find episode number for ${episode.fileName}")
    }
  }
}

class Aired(episodes: Seq[EpisodeData]) {
  def unapply(episode: UnknownBroadcastEpisode): Option[Int] =
    episodes
      .find(_.firstAired == episode.date.toString)
      .map(_.airedEpisodeNumber)
}

class RemuxCoreLogic(config: RemuxJobConfig, classifier: RemuxEpisodeClassifier, logger: Logger) extends CoreLogic {
  def run(): Unit = {
    val unknownEpisodes = classifier.findUnknownEpisodes()
    unknownEpisodes.foreach(println)
  }
}
