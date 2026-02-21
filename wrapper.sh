#! /usr/bin/env bash

FULL_ARGS=( "$@" )

set_uidgid () {
  groupmod -o -g "$PGID" abc
  usermod -o -u "$PUID" abc
}

run_tvrename () {
  chown -R "$PUID":"$PGID" /cache
  set_uidgid
  exec s6-setuidgid abc \
    /app/TvRename "${FULL_ARGS[@]}"
}

usermod -a -G root abc

run_tvrename
