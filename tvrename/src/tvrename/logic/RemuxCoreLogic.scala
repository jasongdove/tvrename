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
  logger: Logger
) extends CoreLogic {
  def run(): Unit = {
    subtitleDownloader.download()
    val unknownEpisodes = classifier.findUnknownEpisodes()
    unknownEpisodes.sortBy(_.fileName).foreach { episode =>
      logger.debug(episode.fileName)
      val subtitles = subtitleExtractor.extractFromFile(episode.fileName)
    }
  }
}
