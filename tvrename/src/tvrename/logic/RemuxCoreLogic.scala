package tvrename.logic

import tvrename._
import tvrename.config._
import tvrename.classifier.RemuxEpisodeClassifier
import tvrename.subtitles._
import org.ebml.io.FileDataSource
import org.ebml.matroska.MatroskaFile
import org.ebml.matroska.MatroskaFileTrack.TrackType

class RemuxCoreLogic(
  jobConfig: RemuxJobConfig,
  classifier: RemuxEpisodeClassifier,
  subtitleDownloader: ReferenceSubtitleDownloader,
  subtitleExtractor: SubtitleExtractor,
  subtitleProcessor: SubtitleProcessor,
  subtitleMatcher: SubtitleMatcher,
  fileSystem: FileSystem,
  logger: Logger
) extends CoreLogic {
  def run(): Unit = {
    subtitleDownloader.download()
    val unknownEpisodes = classifier.findUnknownEpisodes()
    unknownEpisodes.sortBy(_.fileName).foreach { episode =>
      logger.debug(episode.fileName)
      val matchedEpisodes = subtitleExtractor
        .extractFromFile(episode.fileName)
        .map(subtitleProcessor.convertToLines)
        .map(subtitleProcessor.cleanLines)
        .flatMap(subtitleMatcher.matchToReference)

      matchedEpisodes.foreach { episodeMatch =>
        val template = jobConfig.template
          .replace("[series]", jobConfig.seriesName)
          .replace("[season]", f"${episodeMatch.seasonNumber}%02d")
          .replace("[episode]", f"${episodeMatch.episodeNumber}%02d")

        logger.info(s"Matched with ${episodeMatch.confidence}% confidence to ${template}")

        val insertIndex = episode.fileName.lastIndexOf('.')
        val ext = episode.fileName.substring(insertIndex)
        val newFileName = template + ext
        
        if (episodeMatch.confidence < jobConfig.minimumConfidence) {
          logger.warn("Confidence too low; will not rename")
        } else if (jobConfig.dryRun) {
          logger.info(s"=> DRY RUN")
        } else {
          logger.info(s"=> ${newFileName}")
          val targetFile = fileSystem.absoluteToRelative(newFileName, episode.fileName)
          fileSystem.rename(episode.fileName, targetFile)
        }
      }
    }
  }
}
