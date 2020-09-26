package tvrename.config

case class SeriesId(value: Int) extends AnyVal
case class SeasonNumber(value: Int) extends AnyVal

sealed trait JobConfig {
  def mediaFolder: String
  //def recursive: Boolean
  def template: String
}

case class BroadcastJobConfig(
  mediaFolder: String,
  template: String,
  seriesId: SeriesId,
  seasonNumber: SeasonNumber
) extends JobConfig

case class RemuxJobConfig(
  mediaFolder: String,
  template: String,
  seriesId: SeriesId,
  seriesName: String,
  seasonNumber: SeasonNumber,
  minimumConfidence: Int = 40,
  dryRun: Boolean = false
) extends JobConfig

object JobConfig {
  import pureconfig.generic.FieldCoproductHint

  implicit val broadcastConfHint = new FieldCoproductHint[JobConfig]("media-source") {
    override protected def fieldValue(name: String): String = name.dropRight("JobConfig".length).toLowerCase
  }
}
