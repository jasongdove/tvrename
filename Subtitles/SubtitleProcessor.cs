using System.Text;
using System.Text.RegularExpressions;
using CliWrap;
using SubtitlesParser.Classes;
using SubtitlesParser.Classes.Parsers;
using TvRename.Models;

namespace TvRename.Subtitles;

public partial class SubtitleProcessor(ILogger<SubtitleProcessor> logger)
{
    public async Task<Either<Exception, List<string>>> ConvertToLines(
        ExtractedSubtitles subtitles,
        CancellationToken cancellationToken)
    {
        try
        {
            Either<Exception, ExtractedSrtSubtitles> subRip = subtitles switch
            {
                ExtractedSrtSubtitles srt => srt,
                ExtractedDvdSubtitles dvd => await Ocr(dvd, cancellationToken),
                ExtractedPgsSubtitles pgs => await Ocr(pgs, cancellationToken),
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
                    .Map(line => BadApostrophe().Replace(line, "'"))
                    .Map(line => NamesAndDashes().Replace(line, string.Empty))
                    .Map(line => line.Trim())
                    .Filter(line => !string.IsNullOrWhiteSpace(line))
                    .Filter(line => !Sdh().Match(line).Success)
                    .ToList();
            }

            logger.LogDebug("Failed to convert lines");

            return new Exception("Failed to convert lines");
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            return ex;
        }
    }

    private async Task<Either<Exception, ExtractedSrtSubtitles>> Ocr(
        ExtractedDvdSubtitles dvd,
        CancellationToken cancellationToken)
    {
        logger.LogInformation("Converting DVD bitmap subtitles to text");

        string tessdataFolder = Path.Combine(Directory.GetCurrentDirectory(), "pgstosrt", "tessdata");
        string idxFileName = Path.ChangeExtension(dvd.FileName, "idx");
        string srtFileName = Path.ChangeExtension(dvd.FileName, "srt");

        CommandResult result = await Cli.Wrap("subtile-ocr")
            .WithArguments(["-l", "eng", "-o", srtFileName, idxFileName])
            .WithEnvironmentVariables(new Dictionary<string, string?> { { "TESSDATA_PREFIX", tessdataFolder } })
            .WithValidation(CommandResultValidation.None)
            .ExecuteAsync(cancellationToken);

        if (result.ExitCode == 0 && File.Exists(srtFileName))
        {
            return new ExtractedSrtSubtitles(srtFileName, dvd.StreamNumber);
        }

        return new Exception($"subtile-ocr failed to convert; exit code {result.ExitCode}");
    }

    private async Task<Either<Exception, ExtractedSrtSubtitles>> Ocr(
        ExtractedPgsSubtitles pgs,
        CancellationToken cancellationToken)
    {
        logger.LogInformation("Converting PGS bitmap subtitles to text");

        string srtFileName = Path.ChangeExtension(pgs.FileName, "srt");

        CommandResult result = await Cli.Wrap("dotnet")
            .WithArguments(["pgstosrt/PgsToSrt.dll", "--input", pgs.FileName, "--output", srtFileName])
            .WithValidation(CommandResultValidation.None)
            .ExecuteAsync(cancellationToken);

        if (result.ExitCode == 0 && File.Exists(srtFileName))
        {
            return new ExtractedSrtSubtitles(Path.ChangeExtension(pgs.FileName, "srt"), pgs.StreamNumber);
        }

        logger.LogError("PgsToSrt failed to convert; exit code {ExitCode}", result.ExitCode);

        return new Exception($"PgsToSrt failed to convert; exit code {result.ExitCode}");
    }

    [GeneratedRegex(@"(?<=\w)\s*['’](?=\w)")]
    private static partial Regex BadApostrophe();

    [GeneratedRegex(@"-[\s\w\d#]*[:\s]*")]
    private static partial Regex NamesAndDashes();

    [GeneratedRegex(@"[\(\[].*[\)\]]")]
    private static partial Regex Sdh();
}
