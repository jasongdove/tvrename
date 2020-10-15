package tvrename.logic

import cats.effect.IO
import cats.implicits._
import tvrename._
import tvrename.classifier._
import tvrename.config._

class BroadcastCoreLogic(
  jobConfig: BroadcastJobConfig,
  dryRun: Boolean,
  scraper: BroadcastScraper,
  classifier: BroadcastEpisodeClassifier,
  fileSystem: FileSystem,
  logger: Logger
) extends CoreLogic {
  def run(): IO[Unit] =
    for {
      unknownEpisodes <- classifier.findUnknownEpisodes()
      episodesForSeason <- scraper.episodesForSeason(jobConfig.seasonNumber)
      _ <- renameEpisodes(unknownEpisodes, episodesForSeason)
    } yield ()

  def renameEpisodes(unknownEpisodes: List[UnknownBroadcastEpisode], episodesForSeason: List[Episode]): IO[Unit] = {
    val aired = new Aired(episodesForSeason)
    unknownEpisodes.traverse_ {
      case episode @ aired(episodeNumber) =>
        for {
          _ <- logger.debug(episode.fileName)
          _ <- renameEpisode(episode, jobConfig.seasonNumber, episodeNumber)
        } yield ()
      case episode =>
        for {
          _ <- logger.debug(episode.fileName)
          _ <- logger.warn("\t=> unable to identify episode; will not rename")
        } yield ()
    }
  }

  def renameEpisode(
    episode: UnknownBroadcastEpisode,
    seasonNumber: SeasonNumber,
    episodeNumber: Int
  ): IO[Unit] = {
    val sourceFile = episode.fileName

    val formattedSeason = f"${seasonNumber.value}%02d"
    val formattedEpisode = f"${episodeNumber}%02d"
    val insertIndex = sourceFile.lastIndexOf('.')
    val ext = sourceFile.substring(insertIndex)
    val titleSeasonAndEpisode = jobConfig.template
      .replace("[season]", formattedSeason)
      .replace("[episode]", formattedEpisode)

    val newFileName = titleSeasonAndEpisode + ext
    val targetFile = fileSystem.absoluteToRelative(newFileName, sourceFile)

    if (targetFile == episode.fileName) {
      logger.info("\t=> NO CHANGE")
    } else if (dryRun) {
      logger.info(s"\t=> DRY RUN => ${newFileName}")
    } else {
      for {
        _ <- logger.info(s"\t=> ${newFileName}")
        _ <- fileSystem.rename(episode.fileName, targetFile)
      } yield ()
    }
  }
}

class Aired(episodes: List[Episode]) {
  def unapply(episode: UnknownBroadcastEpisode): Option[Int] =
    episodes
      .find(_.airDate == episode.date)
      .map(_.episodeNumber.value)
}
