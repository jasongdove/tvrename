# TvRename

Identify and rename television episode files.

## Modes

### Rename

The rename mode is used to identify and rename episodes. The `--dry-run` argument can be added to skip the final rename step.

```shell
./TvRename rename --imdb 012345 "/some/show/season 01"
```
### Verify

The verify mode is used to identify episodes and verify that they are named correctly.

```shell
./TvRename verify --imdb 012345 "/some/show/season 01"
```

### Dependencies

- `ffprobe` from [ffmpeg](https://ffmpeg.org/download.html)
- `mkvextract` from [mkvtoolnix](https://mkvtoolnix.download/)
- [VobSub2SRT](https://github.com/ruediger/VobSub2SRT)
- [dotnet](https://dotnet.microsoft.com/en-us/download) (for running PgsToSrt)
- [PgsToSrt](https://github.com/Tentacule/PgsToSrt)
  - `PgsToSrt` release contains a `net6` folder whose contents should be extracted into a `pgstosrt` subfolder
- [Tesseract Data](https://github.com/tesseract-ocr/tessdata/blob/main/eng.traineddata)
  - This data should be copied to a `pgstosrt/tessdata` subfolder
