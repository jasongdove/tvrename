package tvrename.subtitles

trait SubtitleMatcher {
    def matchToReference(episodeLines: Seq[String]): Option[EpisodeMatch]
}