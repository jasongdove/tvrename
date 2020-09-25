package tvrename

import ch.qos.logback.classic.Level
import org.slf4j.LoggerFactory

trait Logger {
  def debug(msg: String)
  def info(msg: String)
  def warn(msg: String)
}

object LoggerImpl extends Logger {
  val logger = LoggerFactory
    .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    .asInstanceOf[ch.qos.logback.classic.Logger]

  logger.setLevel(Level.DEBUG)

  def debug(msg: String): Unit =
    if (logger.isDebugEnabled) logger.debug(msg)

  def info(msg: String): Unit =
    if (logger.isInfoEnabled) logger.info(msg)

  def warn(msg: String): Unit =
    if (logger.isWarnEnabled) logger.warn(msg)
}
