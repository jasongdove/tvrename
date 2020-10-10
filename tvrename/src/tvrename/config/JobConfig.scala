package tvrename.config

import pureconfig.generic.FieldCoproductHint

case class SeriesId(value: Int) extends AnyVal
case class SeasonNumber(value: Int) extends AnyVal
case class EpisodeNumber(value: Int) extends AnyVal

sealed trait Command
object Rename extends Command
object Verify extends Command

sealed trait JobConfig {
  def mediaFolder: String
  def recursive: Option[Boolean]
  def template: String
}

object JobConfig {
  implicit val broadcastConfHint: FieldCoproductHint[JobConfig] = new FieldCoproductHint[JobConfig]("media-source") {
    override protected def fieldValue(name: String): String = name.dropRight("JobConfig".length).toLowerCase
  }
}

case class BroadcastJobConfig(
  mediaFolder: String,
  template: String,
  seriesId: SeriesId,
  seasonNumber: SeasonNumber,
  recursive: Option[Boolean]
) extends JobConfig

case class RemuxJobConfig(
  mediaFolder: String,
  template: String,
  seriesId: SeriesId,
  seriesName: String,
  seasonNumber: SeasonNumber,
  minimumConfidence: Option[Int],
  recursive: Option[Boolean]
) extends JobConfig
