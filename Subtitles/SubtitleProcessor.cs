using System.Text;
using CliWrap;
using Serilog;
using SubtitlesParser.Classes;
using TvRename.Models;

namespace TvRename.Subtitles;

public static class SubtitleProcessor
{
    public static async Task Process(ExtractedSubtitles subtitles)
    {
        Either<Exception, List<string>> lines = await ConvertToLines(subtitles);
        Log.Information("Convert result {Result}", lines.Match(l => l.Count.ToString(), ex => ex.ToString()));
    }

    private static async Task<Either<Exception, List<string>>> ConvertToLines(ExtractedSubtitles subtitles)
    {
        Either<Exception, ExtractedSrtSubtitles> subRip = subtitles switch
        {
            ExtractedSrtSubtitles srt => srt,
            ExtractedDvdSubtitles dvd => await Ocr(dvd),
            ExtractedPgsSubtitles pgs => await Ocr(pgs),
            _ => throw new IndexOutOfRangeException(nameof(subtitles))
        };

        foreach (ExtractedSrtSubtitles srt in subRip.RightToSeq())
        {
            var parser = new SubtitlesParser.Classes.Parsers.SrtParser();
            await using FileStream fs = File.OpenRead(srt.FileName);
            Option<List<SubtitleItem>> parsed = parser.ParseStream(fs, Encoding.UTF8);
            return parsed.Map(list => list.SelectMany(l => l.PlaintextLines)).Flatten().ToList();
        }

        return new List<string>();
    }

    private static async Task<Either<Exception, ExtractedSrtSubtitles>> Ocr(ExtractedDvdSubtitles dvd)
    {
        Log.Information("Converting DVD bitmap subtitles to text");

        CommandResult result = await Cli.Wrap("vobsub2srt")
            .WithArguments(new[] { "-l", "en", dvd.BaseName })
            .WithValidation(CommandResultValidation.None)
            .ExecuteAsync(CancellationToken.None);

        return result.ExitCode switch
        {
            0 => new ExtractedSrtSubtitles(Path.ChangeExtension(dvd.FileName, "srt")),
            _ => new Exception($"VobSub2SRT exited with code {result.ExitCode}")
        };
    }

    private static async Task<Either<Exception, ExtractedSrtSubtitles>> Ocr(ExtractedPgsSubtitles pgs)
    {
        Log.Information("Converting PGS bitmap subtitles to text");

        string srtFileName = Path.ChangeExtension(pgs.FileName, "srt");

        CommandResult result = await Cli.Wrap("dotnet")
            .WithArguments(new[] { "pgstosrt/PgsToSrt.dll", "--input", pgs.FileName, "--output", srtFileName })
            .WithValidation(CommandResultValidation.None)
            .ExecuteAsync(CancellationToken.None);

        return result.ExitCode switch
        {
            0 => new ExtractedSrtSubtitles(Path.ChangeExtension(pgs.FileName, "srt")),
            _ => new Exception($"PgsToSrt exited with code {result.ExitCode}")
        };
    }
}
