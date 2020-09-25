package tvrename

import os.copy.over

trait FileSystem {
  def walk(path: String): IndexedSeq[String]
  def getModifyTime(path: String): Long
  def rename(source: String, dest: String): Unit
  def absoluteToRelative(path: String, relativeTo: String): String
  def relativeTo(path: String, relativeTo: String): String
  def makeDirs(path: String): Unit
  def streamCommandToFile(stream: geny.Readable, command: String, targetFile: String)
  def exists(path: String): Boolean
  def getTempFileName(): String
  def call(command: String*)
  def read(path: String): String
}

object FileSystemImpl extends FileSystem {
  override def walk(path: String): IndexedSeq[String] = os.walk(os.Path(path)).map(_.toString)

  override def getModifyTime(path: String): Long = os.mtime(os.Path(path))

  override def rename(source: String, dest: String): Unit =
    os.move(os.Path(source), os.Path(dest), replaceExisting = true)

  override def absoluteToRelative(path: String, relativeTo: String): String =
    (os.Path(relativeTo) / os.up / os.RelPath(path)).toString

  override def relativeTo(path: String, relativeTo: String): String =
    os.Path(path).relativeTo(os.Path(relativeTo)).toString

  override def makeDirs(path: String): Unit = os.makeDir.all(os.Path(path))

  override def streamCommandToFile(stream: geny.Readable, command: String, targetFile: String): Unit =
    os.proc(command).call(stdin = stream, stdout = os.Path(targetFile))

  override def exists(path: String): Boolean =
    os.exists(os.Path(path))

  override def getTempFileName(): String = os.temp().toString

  override def call(command: String*): Unit = os.proc(command).call(mergeErrIntoOut = true)

  override def read(path: String): String = os.read(os.Path(path))
}
