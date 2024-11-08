using System.Text;
using System.Text.RegularExpressions;
using CliWrap;
using SubtitlesParser.Classes;
using SubtitlesParser.Classes.Parsers;
using TvRename.Models;

namespace TvRename.Subtitles;

public class SubtitleProcessor
{
    private static readonly Regex BadApostrophe = new(@"(?<=\w)\s*['’](?=\w)");
    private static readonly Regex NamesAndDashes = new(@"-[\s\w\d#]*[:\s]*");
    private static readonly Regex Sdh = new(@"[\(\[].*[\)\]]");
    private readonly ILogger<SubtitleProcessor> _logger;

    public SubtitleProcessor(ILogger<SubtitleProcessor> logger) => _logger = logger;

    public async Task<Either<Exception, List<string>>> ConvertToLines(ExtractedSubtitles subtitles)
    {
        try
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

            _logger.LogDebug("Failed to convert lines");

            return new Exception("Failed to convert lines");
        }
        catch (Exception ex)
        {
            return ex;
        }
    }

    private async Task<Either<Exception, ExtractedSrtSubtitles>> Ocr(ExtractedDvdSubtitles dvd)
    {
        _logger.LogInformation("Converting DVD bitmap subtitles to text");

        string tessdataFolder = Path.Combine(Directory.GetCurrentDirectory(), "pgstosrt", "tessdata");

        CommandResult result = await Cli.Wrap("vobsub2srt")
            .WithArguments(new[] { "-l", "en", dvd.BaseName, "--tesseract-data", tessdataFolder })
            .WithValidation(CommandResultValidation.None)
            .ExecuteAsync(CancellationToken.None);

        string srtFileName = Path.ChangeExtension(dvd.FileName, "srt");

        if (result.ExitCode == 0 && File.Exists(srtFileName))
        {
            return new ExtractedSrtSubtitles(srtFileName, dvd.StreamNumber);
        }

        return new Exception($"VobSub2SRT failed to convert; exit code {result.ExitCode}");
    }

    private async Task<Either<Exception, ExtractedSrtSubtitles>> Ocr(ExtractedPgsSubtitles pgs)
    {
        _logger.LogInformation("Converting PGS bitmap subtitles to text");

        string srtFileName = Path.ChangeExtension(pgs.FileName, "srt");

        CommandResult result = await Cli.Wrap("dotnet")
            .WithArguments(new[] { "pgstosrt/PgsToSrt.dll", "--input", pgs.FileName, "--output", srtFileName })
            .WithValidation(CommandResultValidation.None)
            .ExecuteAsync(CancellationToken.None);

        if (result.ExitCode == 0 && File.Exists(srtFileName))
        {
            return new ExtractedSrtSubtitles(Path.ChangeExtension(pgs.FileName, "srt"), pgs.StreamNumber);
        }

        _logger.LogError("PgsToSrt failed to convert; exit code {ExitCode}", result.ExitCode);

        return new Exception($"PgsToSrt failed to convert; exit code {result.ExitCode}");
    }
}
