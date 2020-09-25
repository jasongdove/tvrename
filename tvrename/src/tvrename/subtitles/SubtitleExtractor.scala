package tvrename.subtitles

import tvrename._
import tvrename.config.TVRenameConfig
import org.ebml.io.FileDataSource
import org.ebml.matroska.MatroskaFile
import org.ebml.matroska.MatroskaFileTrack.TrackType
import org.ebml.matroska.MatroskaFileTrack

sealed trait Subtitles {
  def trackNumber: Int
  def priority: Int
}

object Subtitles {
  def fromTrack(track: MatroskaFileTrack): Subtitles =
    track.getCodecID match {
      case "S_VOBSUB" => VobSub(track.getTrackNo - 1)
    }
}

case class SubRip(trackNumber: Int) extends Subtitles {
  def priority = 1
}

case class VobSub(trackNumber: Int) extends Subtitles {
  def priority = 3
}

case class PGS(trackNumber: Int) extends Subtitles {
  def priority = 2
}

trait SubtitleExtractor {
  def extractFromFile(fileName: String): Subtitles
}

class SubtitleExtractorImpl(config: TVRenameConfig, fileSystem: FileSystem, logger: Logger) extends SubtitleExtractor {
  def extractFromFile(fileName: String): Subtitles = {
    val dataSource = new FileDataSource(fileName)
    val matroska = new MatroskaFile(dataSource)
    matroska.readFile()
    var subtitles = matroska.getTrackList
      .filter(t => t.getTrackType == TrackType.SUBTITLE)
      .map(Subtitles.fromTrack)
      .sortBy(s => s.priority)
      .head

    val tempFileName = fileSystem.getTempFileName()
    fileSystem.call("mkvextract", fileName, "tracks", s"${subtitles.trackNumber}:${tempFileName}")
    //val cacheFileName = ??
    //fileSystem.rename(tempFileName, cacheFileName)

    subtitles
  }
}
