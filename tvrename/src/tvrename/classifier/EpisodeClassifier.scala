package tvrename.classifier

import tvrename._
import tvrename.config._
import cats.effect.IO

trait UnknownEpisode { def fileName: String }

abstract case class EpisodeClassifier[A <: UnknownEpisode](jobConfig: JobConfig, fileSystem: FileSystem) {
  def findUnknownEpisodes(): IO[List[A]]
}
