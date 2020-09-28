package tvrename.config

import pureconfig.generic.FieldCoproductHint

case class SeriesId(value: Int) extends AnyVal
case class SeasonNumber(value: Int) extends AnyVal

sealed trait Command
object Rename extends Command
object Verify extends Command

sealed trait JobConfig {
  def mediaFolder: String
  def recursive: Boolean
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
  recursive: Boolean = false
) extends JobConfig

case class RemuxJobConfig(
  mediaFolder: String,
  template: String,
  seriesId: SeriesId,
  seriesName: String,
  seasonNumber: SeasonNumber,
  minimumConfidence: Int = 40,
  recursive: Boolean = false
) extends JobConfig
