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
  dryRun: Boolean,
  classifier: RemuxEpisodeClassifier,
  subtitleDownloader: ReferenceSubtitleDownloader,
  subtitleExtractor: SubtitleExtractor,
  subtitleProcessor: SubtitleProcessor,
  subtitleMatcher: SubtitleMatcher,
  fileSystem: FileSystem,
  logger: Logger
) extends CoreLogic {
  def run(): Unit = {
    subtitleDownloader.downloadIfNeeded()
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
          logger.warn("\t=> Confidence too low; will not rename")
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
