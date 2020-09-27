import mill._, scalalib._

object jebml extends JavaModule {
  def ivyDeps = Agg(
    ivy"org.slf4j:slf4j-api:1.7.5",
    ivy"junit:junit:4.11",
    ivy"org.mockito:mockito-core:1.9.+",
    ivy"ch.qos.logback:logback-classic:1.2.3"
  )
}

object tvrename extends ScalaModule {
  def scalaVersion = "2.13.3"
  def moduleDeps = Seq(jebml)
  def ivyDeps = Agg(
    ivy"com.lihaoyi::os-lib:0.6.2",
    ivy"com.lihaoyi::upickle:0.9.5",
    ivy"com.lihaoyi::requests:0.5.1",
    ivy"com.github.pureconfig::pureconfig:0.13.0",
    ivy"org.rogach::scallop:3.5.1",
    ivy"ch.qos.logback:logback-classic:1.2.3",
    ivy"com.github.dnbn.submerge:submerge-api:1.9.2"
  )
}