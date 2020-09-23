package tvrename.classifier

import java.time.LocalDate

import tvrename.config._

trait EpisodeClassifier {
  def findUnknownEpisodes(): Seq[UnknownEpisode]
  def renameEpisode(episode: UnknownEpisode, seasonNumber: SeasonNumber, episodeNumber: Int): (String, String)
}

case class UnknownEpisode(fileName: String, date: LocalDate)
