package tvrename.logic

import tvrename._
import tvrename.config.RemuxJobConfig
import tvrename.classifier.RemuxEpisodeClassifier
import tvrename.subtitles.ReferenceSubtitleDownloader

class RemuxCoreLogic(
  config: RemuxJobConfig,
  classifier: RemuxEpisodeClassifier,
  subtitleDownloader: ReferenceSubtitleDownloader,
  logger: Logger
) extends CoreLogic {
  def run(): Unit = {
    subtitleDownloader.download()
    val unknownEpisodes = classifier.findUnknownEpisodes()
    unknownEpisodes.foreach(println)
  }
}
