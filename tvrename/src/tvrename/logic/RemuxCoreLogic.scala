package tvrename.logic

import cats.effect.IO
import cats.implicits._
import tvrename._
import tvrename.classifier._
import tvrename.config._
import tvrename.subtitles._

class RemuxCoreLogic(
  jobConfig: RemuxJobConfig,
  dryRun: Boolean,
  classifier: RemuxEpisodeClassifier,
  downloader: ReferenceSubtitleDownloader,
  extractor: SubtitleExtractor,
  processor: SubtitleProcessor,
  matcher: SubtitleMatcher,
  fileSystem: FileSystem,
  logger: Logger
) extends CoreLogic {
  def run(): IO[Unit] =
    for {
      _ <- downloader.download()
      unknownEpisodes <- classifier.findUnknownEpisodes()
      _ <- unknownEpisodes.traverse(identifyAndRenameEpisode)
    } yield ()

  def identifyAndRenameEpisode(episode: UnknownRemuxEpisode): IO[Unit] =
    for {
      _ <- logger.debug(fileSystem.getFileName(episode.fileName))
      subtitledEpisode <- extractor.extractFromEpisode(episode)
      processedSubtitledEpisode <- processor.processEpisode(subtitledEpisode)
    } yield {
      matcher.matchEpisode(processedSubtitledEpisode) match {
        case Some(episode) => renameEpisode(episode)
        case None          => ()
      }
    }

  def renameEpisode(episode: MatchedSubtitledEpisode): IO[Unit] = {
    val template = jobConfig.template
      .replace("[series]", jobConfig.seriesName)
      .replace("[season]", f"${episode.seasonNumber}%02d")
      .replace("[episode]", f"${episode.episodeNumber}%02d")

    val insertIndex = episode.fileName.lastIndexOf('.')
    val ext = episode.fileName.substring(insertIndex)
    val newFileName = template + ext
    val targetFile = fileSystem.absoluteToRelative(newFileName, episode.fileName)

    if (episode.confidence < jobConfig.minimumConfidence) {
      logger.warn(s"\t=> ${episode.confidence}% confidence too low; will not rename")
    } else if (targetFile == episode.fileName) {
      logger.info(s"\t=> ${episode.confidence}% NO CHANGE")
    } else if (dryRun) {
      logger.info(s"\t=> ${episode.confidence}% DRY RUN => ${template}")
    } else {
      for {
        _ <- logger.info(s"\t=> ${episode.confidence}% ${newFileName}")
        _ <- fileSystem.rename(episode.fileName, targetFile)
      } yield ()
    }
  }
}

trait MatchStatus

case object FailedToMatch extends MatchStatus
case object NoChangeRequired extends MatchStatus
case object SuccessfullyMatched extends MatchStatus

class VerifyRemuxCoreLogic(
  jobConfig: RemuxJobConfig,
  classifier: RemuxEpisodeClassifier,
  downloader: ReferenceSubtitleDownloader,
  extractor: SubtitleExtractor,
  processor: SubtitleProcessor,
  matcher: SubtitleMatcher,
  fileSystem: FileSystem,
  logger: Logger
) extends CoreLogic {
  private val verifiedFileName = fileSystem.concatPaths(jobConfig.mediaFolder, ".tvrename-verified")

  def run(): IO[Unit] = {
    if (fileSystem.exists(verifiedFileName)) {
      logger.info("Media folder has already been verified")
    } else {
      for {
        _ <- downloader.download()
        unknownEpisodes <- classifier.findUnknownEpisodes()
        success <- identifyAndValidateEpisodes(unknownEpisodes)
        _ <- recordSuccess(success)
      } yield ()
    }
  }

  def identifyAndValidateEpisodes(episodes: List[UnknownRemuxEpisode]): IO[Boolean] =
    episodes.foldLeft[IO[Boolean]](IO.pure(true)) { (acc, episode) =>
      for {
        success <- identifyAndValidateEpisode(episode)
        accSuccess <- acc
      } yield accSuccess && success
    }

  def identifyAndValidateEpisode(episode: UnknownRemuxEpisode): IO[Boolean] =
    for {
      _ <- logger.debug(fileSystem.getFileName(episode.fileName))
      subtitledEpisode <- extractor.extractFromEpisode(episode)
      processedSubtitledEpisode <- processor.processEpisode(subtitledEpisode)
      matchStatus <- IO(matcher.matchEpisode(processedSubtitledEpisode))
      result <- matchStatus.map(validateEpisode).getOrElse(IO.pure(false))
    } yield result

  private def validateEpisode(episode: MatchedSubtitledEpisode): IO[Boolean] = {
    getMatchStatus(episode) match {
      case FailedToMatch =>
        logger.warn(s"\t=> ${episode.confidence} FAIL").flatMap(_ => IO.pure(false))
      case NoChangeRequired =>
        logger.info(s"\t=> ${episode.confidence}% OK").flatMap(_ => IO.pure(true))
      case SuccessfullyMatched =>
        val newFileName = getNewFileName(episode)
        logger.info(s"\t=> ${episode.confidence}% FAIL ${newFileName}").flatMap(_ => IO.pure(false))
    }
  }

  private def recordSuccess(success: Boolean): IO[Unit] =
    if (success)
      fileSystem.writeToFile(verifiedFileName, "OK")
    else
      IO.unit

  private def getNewFileName(episode: MatchedSubtitledEpisode): String = {
    val template = jobConfig.template
      .replace("[series]", jobConfig.seriesName)
      .replace("[season]", f"${episode.seasonNumber}%02d")
      .replace("[episode]", f"${episode.episodeNumber}%02d")

    val insertIndex = episode.fileName.lastIndexOf('.')
    val ext = episode.fileName.substring(insertIndex)
    template + ext
  }

  private def getMatchStatus(episode: MatchedSubtitledEpisode): MatchStatus = {
    val newFileName = getNewFileName(episode)
    val targetFile = fileSystem.absoluteToRelative(newFileName, episode.fileName)
    if (episode.confidence < jobConfig.minimumConfidence) {
      FailedToMatch
    } else if (episode.fileName == targetFile) {
      NoChangeRequired
    } else {
      SuccessfullyMatched
    }
  }
}
