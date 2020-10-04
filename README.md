# tvrename

Identify and rename television episode files.

## Modes

### Rename

The rename mode is used to identify and rename episodes. The `--dry-run` argument can be added to skip the final rename step.

```shell
tvrename rename --job /tmp/rename.conf
```
### Verify

The verify mode is used to identify episodes and verify that they are named correctly.

```shell
tvrename verify --job /tmp/verify.conf
```

## Supported job types (media sources)

- [Broadcast](#Broadcast)
  - This job type is used for daily over-the-air television episodes.
- [Remux](#Remux)
  - This job type is used for remuxed television episodes that contain embedded subtitles.

## Broadcast

Broadcast jobs identify and rename daily television episodes using the episode's modify time referenced against broadcast date metadata from TVDB.

### Dependencies

- [TVDB API credentials](https://thetvdb.com/api-information)

### Sample configuration

```
TODO
```

## Remux

Remux jobs retrieve "reference" subtitles from [opensubtitles.org](https://www.opensubtitles.org) for the configured season, extract (and OCR) subtitles from the episode files, and identify episode files by comparing extracted subtitles to reference subtitles. If the episode subtitles match the reference subtitles for at least some percent of lines (a configurable confidence), the episode files will be renamed using the desired template.

### Dependencies

- mkvextract from [mkvtoolnix](https://mkvtoolnix.download/)
- docker to run [PgsToSrt](https://github.com/Tentacule/PgsToSrt) for converting BluRay bitmap subtitles to text
- [VobSub2SRT](https://github.com/ruediger/VobSub2SRT) for converting DVD bitmap subtitles to text

### Sample configuration

```
media-source = remux
media-folder = /tmp/media/inbox
template = "[series] - s[season]e[episode]"
series-id = 0000000
series-name = Awesome Television Show
season-number = 1
minimum-confidence = 40
```