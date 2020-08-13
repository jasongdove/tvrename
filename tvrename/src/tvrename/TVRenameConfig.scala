package tvrename

case class SeriesId(value: Int) extends AnyVal
case class SeasonNumber(value: Int) extends AnyVal
case class ApiKey(value: String) extends AnyVal
case class UserKey(value: String) extends AnyVal
case class Username(value: String) extends AnyVal

case class TVDBConfig(
  apiKey: ApiKey,
  userKey: UserKey,
  username: Username
)

case class TVRenameConfig(
  targetFolder: String,
  seriesId: SeriesId,
  seasonNumber: SeasonNumber,
  tvdbConfig: TVDBConfig
)
