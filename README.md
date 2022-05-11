# TvRename

Identify and rename television episode files.

## Modes

### Rename

The rename mode is used to identify and rename episodes. The `--dry-run` argument can be added to skip the final rename step.

```shell
docker run -it --rm \
  -e PUID=1000 -e PGID=1000 \
  -v "/tmp/tv:/tmp/tv:rw" \
  -v tvrenamecache:/cache \
  jasongdove/tvrename:develop \
  rename --imdb 0101049 "/tmp/tv/some/show/season 01"
```
### Verify

The verify mode is used to identify episodes and verify that they are named correctly.

```shell
docker run -it --rm \
  -e PUID=1000 -e PGID=1000 \
  -v "/tmp/tv:/tmp/tv:rw" \
  -v tvrenamecache:/cache \
  jasongdove/tvrename:develop \
  verify --imdb 0101049 "/tmp/tv/some/show/season 01"
```
