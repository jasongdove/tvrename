package tvrename

trait CoreLogic {
  def run(): Unit
}

class CoreLogicImpl(config: TVRenameConfig, tvdb: TVDB, classifier: EpisodeClassifier, logger: Logger)
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
  def unapply(episode: UnknownEpisode): Option[Int] =
    episodes
      .find(_.firstAired == episode.date.toString)
      .map(_.airedEpisodeNumber)
}
