package tvrename

trait FileSystem {
  def walk(path: String): IndexedSeq[String]
  def getModifyTime(path: String): Long
  def rename(source: String, dest: String): Unit
  def absoluteToRelative(path: String, relativeTo: String): String
}

object FileSystemImpl extends FileSystem {
  override def walk(path: String): IndexedSeq[String] = os.walk(os.Path(path)).map(_.toString)

  override def getModifyTime(path: String): Long = os.mtime(os.Path(path))

  override def rename(source: String, dest: String): Unit = os.move(os.Path(source), os.Path(dest))

  override def absoluteToRelative(path: String, relativeTo: String): String =
    (os.Path(relativeTo) / os.up / os.RelPath(path)).toString
}
