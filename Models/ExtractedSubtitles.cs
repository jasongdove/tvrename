namespace TvRename.Models;

public abstract record ExtractedSubtitles(string FileName, int StreamNumber)
{
    public string BaseName => Path.Combine(
        Path.GetDirectoryName(FileName)!,
        Path.GetFileNameWithoutExtension(FileName));

    public static ExtractedSubtitles ForCodec(string codec, string fileName, int streamNumber) =>
        codec switch
        {
            "subrip" or "mov_text" => new ExtractedSrtSubtitles(fileName, streamNumber),
            "dvd_subtitle" => new ExtractedDvdSubtitles(fileName, streamNumber),
            _ => new ExtractedPgsSubtitles(fileName, streamNumber)
        };
}

public record ExtractedSrtSubtitles(string FileName, int StreamNumber) : ExtractedSubtitles(FileName, StreamNumber);

public record ExtractedDvdSubtitles(string FileName, int StreamNumber) : ExtractedSubtitles(FileName, StreamNumber);

public record ExtractedPgsSubtitles(string FileName, int StreamNumber) : ExtractedSubtitles(FileName, StreamNumber);
