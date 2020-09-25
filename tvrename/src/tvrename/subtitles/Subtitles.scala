package tvrename.subtitles

import org.ebml.matroska.MatroskaFileTrack

sealed trait Subtitles {
  def baseFileName: String
  def extensions: Set[String]
  def priority: Int

  def fileNames: Set[String] = extensions.map(e => s"${baseFileName}.${e}")
  def primaryFileName = fileNames.head
}

object Subtitles {
  def fromTrack(track: MatroskaFileTrack, baseFileName: String): Option[Subtitles] =
    track.getCodecID match {
      case "S_VOBSUB"    => Some(VobSub(baseFileName))
      case "S_HDMV/PGS"  => Some(PGS(baseFileName))
      case "S_TEXT/UTF8" => Some(SubRip(baseFileName))
      case s @ _ =>
        println(s"Unexpected subtitle codec ${s}")
        None
    }
}

case class SubRip(baseFileName: String) extends Subtitles {
  def extensions = Set("srt")
  def priority = 1
}

case class VobSub(baseFileName: String) extends Subtitles {
  def extensions = Set("sub", "idx")
  def priority = 3
}

case class PGS(baseFileName: String) extends Subtitles {
  def extensions = Set("sup")
  def priority = 2
}
