import mill._, scalalib._

object tvrename extends ScalaModule {
  def scalaVersion = "2.13.3"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::os-lib:0.6.2",
    ivy"com.lihaoyi::upickle:0.9.5",
    ivy"com.lihaoyi::requests:0.5.1",
    ivy"com.github.pureconfig::pureconfig:0.13.0",
    ivy"org.rogach::scallop:3.5.1",
    ivy"com.github.kokorin:jebml:2.3",
    ivy"ch.qos.logback:logback-classic:1.2.3"
  )
}
