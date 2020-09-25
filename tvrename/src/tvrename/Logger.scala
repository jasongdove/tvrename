package tvrename

import ch.qos.logback.classic.Level
import org.slf4j.LoggerFactory

trait Logger {
  def log(msg: String)
}

object LoggerImpl extends Logger {
  val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
  logger.setLevel(Level.INFO)
  
  def log(msg: String): Unit = logger.info(msg)
}
