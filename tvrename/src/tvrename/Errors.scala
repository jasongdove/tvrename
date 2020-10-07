package tvrename

sealed trait TVRenameError
case object InvalidJobConfiguration extends TVRenameError
