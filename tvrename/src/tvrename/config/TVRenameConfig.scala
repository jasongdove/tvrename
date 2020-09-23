package tvrename.config

case class ApiKey(value: String) extends AnyVal
case class UserKey(value: String) extends AnyVal
case class Username(value: String) extends AnyVal

case class TVDBConfig(
  apiKey: ApiKey,
  userKey: UserKey,
  username: Username
)

case class TVRenameConfig(
  cacheFolder: String,
  tvdbConfig: TVDBConfig
)
