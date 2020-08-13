package tvrename

trait Logger {
  def log(msg: String)
}

object LoggerImpl extends Logger {
  def log(msg: String): Unit = println(msg)
}
