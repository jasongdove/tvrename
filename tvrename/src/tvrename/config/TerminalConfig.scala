package tvrename.config

import org.rogach.scallop._

class TerminalConfig(arguments: Seq[String]) extends ScallopConf(arguments) {
  val renameCommand = new Subcommand("rename") {
    val job = opt[String](required = true)
    val dryRun = opt[Boolean](default = Some(false))
  }
  val verifyCommand = new Subcommand("verify") {
    val job = trailArg[String]()
  }
  addSubcommand(renameCommand)
  addSubcommand(verifyCommand)
  requireSubcommand()

  verify()
}
