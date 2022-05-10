using System.Text;
using System.Text.RegularExpressions;
using CliWrap;
using Serilog;
using SubtitlesParser.Classes;
using SubtitlesParser.Classes.Parsers;
using TvRename.Models;

namespace TvRename.Subtitles;

public static class SubtitleProcessor
{
    private static readonly Regex BadApostrophe = new(@"(?<=\w)\s*['’](?=\w)");
    private static readonly Regex NamesAndDashes = new(@"-[\s\w\d#]*[:\s]*");
    private static readonly Regex Sdh = new(@"[\(\[].*[\)\]]");

    public static async Task<Either<Exception, List<string>>> ConvertToLines(ExtractedSubtitles subtitles)
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
            var parser = new SrtParser();
            await using FileStream fs = File.OpenRead(srt.FileName);
            Option<List<SubtitleItem>> parsed = parser.ParseStream(fs, Encoding.UTF8);
            return parsed.Map(list => list.SelectMany(l => l.PlaintextLines))
                .Flatten()
                .Map(line => line.ToLowerInvariant())
                .Map(line => line.Replace("||", "ll"))
                .Map(line => BadApostrophe.Replace(line, "'"))
                .Map(line => NamesAndDashes.Replace(line, string.Empty))
                .Map(line => line.Trim())
                .Filter(line => !string.IsNullOrWhiteSpace(line))
                .Filter(line => !Sdh.Match(line).Success)
                .ToList();
        }

        return new Exception("Failed to convert lines");
    }

    private static async Task<Either<Exception, ExtractedSrtSubtitles>> Ocr(ExtractedDvdSubtitles dvd)
    {
        Log.Information("Converting DVD bitmap subtitles to text");

        string tessdataFolder = Path.Combine(Directory.GetCurrentDirectory(), "pgstosrt", "tessdata");

        CommandResult result = await Cli.Wrap("vobsub2srt")
            .WithArguments(new[] { "-l", "en", dvd.BaseName, "--tesseract-data", tessdataFolder })
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
