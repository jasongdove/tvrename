package tvrename.logic

import cats.effect.IO
import tvrename._
import tvrename.classifier.{RemuxEpisodeClassifier, UnknownRemuxEpisode}
import tvrename.config._
import tvrename.subtitles._

class RemuxCoreLogic(
  jobConfig: RemuxJobConfig,
  dryRun: Boolean,
  classifier: RemuxEpisodeClassifier,
  subtitleDownloader: ReferenceSubtitleDownloader,
  subtitleExtractor: SubtitleExtractor,
  subtitleProcessor: SubtitleProcessor,
  subtitleMatcher: SubtitleMatcher,
  fileSystem: FileSystem,
  logger: Logger
) extends CoreLogic {
  def run(): IO[Unit] =
    IO {
      subtitleDownloader.download()
      val unknownEpisodes = classifier.findUnknownEpisodes()
      unknownEpisodes.sortBy(_.fileName).foreach { episode =>
        logger.info(fileSystem.getFileName(episode.fileName))
        //logger.debug(s"\tMovie hash ${episode.movieHash}")
        val matchedEpisodes = subtitleExtractor
          .extractFromEpisode(episode)
          .map(subtitleProcessor.convertToLines)
          .map(subtitleProcessor.cleanLines)
          .flatMap(subtitleMatcher.matchToReference)

        matchedEpisodes.foreach { episodeMatch =>
          val template = jobConfig.template
            .replace("[series]", jobConfig.seriesName)
            .replace("[season]", f"${episodeMatch.seasonNumber}%02d")
            .replace("[episode]", f"${episodeMatch.episodeNumber}%02d")

          val insertIndex = episode.fileName.lastIndexOf('.')
          val ext = episode.fileName.substring(insertIndex)
          val newFileName = template + ext
          val targetFile = fileSystem.absoluteToRelative(newFileName, episode.fileName)

          if (episodeMatch.confidence < jobConfig.minimumConfidence) {
            logger.warn(s"\t=> ${episodeMatch.confidence}% confidence too low; will not rename")
          } else if (targetFile == episode.fileName) {
            logger.info(s"\t=> ${episodeMatch.confidence}% NO CHANGE")
          } else if (dryRun) {
            logger.info(s"\t=> ${episodeMatch.confidence}% DRY RUN => ${template}")
          } else {
            logger.info(s"\t=> ${episodeMatch.confidence}% ${newFileName}")
            fileSystem.rename(episode.fileName, targetFile)
          }
        }
      }
    }
}

trait MatchStatus

case class FailedToMatch() extends MatchStatus
case class NoChangeRequired() extends MatchStatus
case class SuccessfullyMatched() extends MatchStatus

class VerifyRemuxCoreLogic(
  jobConfig: RemuxJobConfig,
  classifier: RemuxEpisodeClassifier,
  subtitleDownloader: ReferenceSubtitleDownloader,
  subtitleExtractor: SubtitleExtractor,
  subtitleProcessor: SubtitleProcessor,
  subtitleMatcher: SubtitleMatcher,
  fileSystem: FileSystem,
  logger: Logger
) extends CoreLogic {
  def run(): IO[Unit] =
    IO {
      val verifiedFileName = fileSystem.concatPaths(jobConfig.mediaFolder, ".tvrename-verified")

      if (fileSystem.exists(verifiedFileName)) {
        logger.info("Media folder has already been verified")
      } else {
        subtitleDownloader.download()
        val unknownEpisodes = classifier.findUnknownEpisodes()
        val matchResults = unknownEpisodes.sortBy(_.fileName).map { episode =>
          logger.info(fileSystem.getFileName(episode.fileName))

          val matchedEpisode = subtitleExtractor
            .extractFromEpisode(episode)
            .map(subtitleProcessor.convertToLines)
            .map(subtitleProcessor.cleanLines)
            .flatMap(subtitleMatcher.matchToReference)
            .map(m => (episode, getMatchStatus(episode, m)))

          matchedEpisode.foreach {
            case (episode, (matchStatus, episodeMatch)) =>
              val newFileName = getNewFileName(episode, episodeMatch)
              val targetFile = fileSystem.absoluteToRelative(newFileName, episode.fileName)

              if (episodeMatch.confidence < jobConfig.minimumConfidence) {
                logger.warn(s"\t=> ${episodeMatch.confidence} FAIL")
              } else if (targetFile == episode.fileName) {
                logger.info(s"\t=> ${episodeMatch.confidence}% OK")
              } else {
                logger.info(s"\t=> ${episodeMatch.confidence}% FAIL ${newFileName}")
              }
          }

          matchedEpisode.get._2._1
        }

        if (matchResults.size > 0 && matchResults.count(_.isInstanceOf[NoChangeRequired]) == matchResults.size) {
          fileSystem.writeToFile(verifiedFileName, "OK")
        }
      }
    }

  private def getNewFileName(episode: UnknownRemuxEpisode, episodeMatch: EpisodeMatch): String = {
    val template = jobConfig.template
      .replace("[series]", jobConfig.seriesName)
      .replace("[season]", f"${episodeMatch.seasonNumber}%02d")
      .replace("[episode]", f"${episodeMatch.episodeNumber}%02d")

    val insertIndex = episode.fileName.lastIndexOf('.')
    val ext = episode.fileName.substring(insertIndex)
    template + ext
  }

  private def getMatchStatus(episode: UnknownRemuxEpisode, episodeMatch: EpisodeMatch): (MatchStatus, EpisodeMatch) = {
    val newFileName = getNewFileName(episode, episodeMatch)
    val targetFile = fileSystem.absoluteToRelative(newFileName, episode.fileName)
    if (episodeMatch.confidence < jobConfig.minimumConfidence) {
      (FailedToMatch(), episodeMatch)
    } else if (episode.fileName == targetFile) {
      (NoChangeRequired(), episodeMatch)
    } else {
      (SuccessfullyMatched(), episodeMatch)
    }
  }
}
