namespace TvRename.Models;

public abstract record ExtractedSubtitles(string FileName)
{
    public string BaseName => Path.Combine(
        Path.GetDirectoryName(FileName)!,
        Path.GetFileNameWithoutExtension(FileName));

    public static ExtractedSubtitles ForCodec(string codec, string fileName) =>
        codec switch
        {
            "subrip" or "mov_text" => new ExtractedSrtSubtitles(fileName),
            "dvd_subtitle" => new ExtractedDvdSubtitles(fileName),
            _ => new ExtractedPgsSubtitles(fileName)
        };
}

public record ExtractedSrtSubtitles(string FileName) : ExtractedSubtitles(FileName);

public record ExtractedDvdSubtitles(string FileName) : ExtractedSubtitles(FileName);

public record ExtractedPgsSubtitles(string FileName) : ExtractedSubtitles(FileName);
