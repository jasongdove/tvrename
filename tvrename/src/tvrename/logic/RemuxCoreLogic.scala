package tvrename.logic

import tvrename._
import tvrename.config.RemuxJobConfig
import tvrename.classifier.RemuxEpisodeClassifier
import tvrename.subtitles._
import org.ebml.io.FileDataSource
import org.ebml.matroska.MatroskaFile
import org.ebml.matroska.MatroskaFileTrack.TrackType

class RemuxCoreLogic(
  config: RemuxJobConfig,
  classifier: RemuxEpisodeClassifier,
  subtitleDownloader: ReferenceSubtitleDownloader,
  subtitleExtractor: SubtitleExtractor,
  subtitleProcessor: SubtitleProcessor,
  logger: Logger
) extends CoreLogic {
  def run(): Unit = {
    subtitleDownloader.download()
    val unknownEpisodes = classifier.findUnknownEpisodes()
    unknownEpisodes.sortBy(_.fileName).foreach { episode =>
      logger.debug(episode.fileName)
      val cleanedText = subtitleExtractor
        .extractFromFile(episode.fileName)
        .map(subtitleProcessor.convertToLines)
        .map(subtitleProcessor.cleanLines)

      cleanedText match {
        case Some(value) =>
          logger.info("Successfully converted to lines of text")
        case None =>
          logger.info("Unable to extract subtitles lines of text")
      }
    }
  }
}
