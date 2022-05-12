using System.Diagnostics.CodeAnalysis;
using System.Text;
using CliWrap;
using CliWrap.Buffered;
using Newtonsoft.Json;
using TvRename.Models;

namespace TvRename.Subtitles;

public class SubtitleExtractor
{
    private readonly string _extractedFolder;

    private readonly ILogger<SubtitleExtractor> _logger;

    public SubtitleExtractor(ILogger<SubtitleExtractor> logger)
    {
        _logger = logger;

        Option<string> maybeCacheFolder = Environment.GetEnvironmentVariable("CACHE_FOLDER");
        string cacheFolder = maybeCacheFolder.Match(
            cacheFolder => cacheFolder,
            () => Path.Combine(
                Environment.GetFolderPath(
                    Environment.SpecialFolder.LocalApplicationData,
                    Environment.SpecialFolderOption.Create),
                "tvrename",
                "cache"));

        _extractedFolder = Path.Combine(cacheFolder, "extracted");
    }

    public async Task<Either<Exception, ExtractedSubtitles>> ExtractSubtitles(
        string fileName,
        CancellationToken cancellationToken)
    {
        string hash = OpenSubtitlesHasher.ComputeMovieHash(fileName);
        _logger.LogInformation("Found episode {File} with hash {Hash}", Path.GetFileName(fileName), hash);

        string srtFileName = GetFileName(
            hash,
            new ProbeResult.FFprobeStream(0, "subrip", string.Empty, 0, new ProbeResult.FFprobeDisposition(0)));

        if (File.Exists(srtFileName))
        {
            return new ExtractedSrtSubtitles(srtFileName);
        }

        ProbeStreamsResult streamsResult = await ProbeStreams(fileName, cancellationToken);
        if (streamsResult.MaybeSubtitles.IsNone)
        {
            if (Directory.Exists("/app/autosub"))
            {
                _logger.LogInformation("Generating subtitles file {File}", srtFileName);

                int audioChannels = streamsResult.MaybeAudio.Map(s => s.channels).IfNone(2);

                if (await GenerateSubtitles(fileName, srtFileName, audioChannels, cancellationToken))
                {
                    return new ExtractedSrtSubtitles(srtFileName);
                }
            }
            else
            {
                return new NotSupportedException(
                    "Subtitle generation via speech-to-text is not supported outside of docker");
            }
        }

        foreach (ProbeResult.FFprobeStream stream in streamsResult.MaybeSubtitles)
        {
            _logger.LogInformation(
                "Probed subtitles stream index {Index} with codec {Codec}",
                stream.index,
                stream.codec_name);

            string targetFile = GetFileName(hash, stream);
            if (File.Exists(targetFile))
            {
                return ExtractedSubtitles.ForCodec(stream.codec_name, targetFile);
            }

            _logger.LogInformation("Extracting subtitles to: {File}", targetFile);

            if (await ExtractSubtitlesStream(fileName, stream, targetFile, cancellationToken))
            {
                return ExtractedSubtitles.ForCodec(stream.codec_name, targetFile);
            }
        }

        // this shouldn't happen
        return new Exception("Unable to probe for subtitles");
    }

    private async Task<bool> GenerateSubtitles(
        string fileName,
        string srtFileName,
        int audioChannels,
        CancellationToken cancellationToken)
    {
        string expectedOutputFile = Path.Combine(
            "/app/autosub/output",
            Path.ChangeExtension(Path.GetFileName(fileName), "srt"));

        string audioFilter = audioChannels switch
        {
            1 => "pan=gain=1",
            2 => "pan=mono|c0<FL+FR",
            _ => "pan=mono|c0=FC"
        };

        BufferedCommandResult result = await Cli.Wrap("python3.7")
            .WithWorkingDirectory("/app/autosub")
            .WithArguments(
                new[]
                {
                    "-m", "autosub.main",
                    "--file", fileName,
                    "--engine", "ds",
                    "--format", "srt",
                    "--audio-filter", audioFilter
                })
            .WithValidation(CommandResultValidation.None)
            .ExecuteBufferedAsync(cancellationToken);

        if (result.ExitCode == 0 && File.Exists(expectedOutputFile))
        {
            File.Move(expectedOutputFile, srtFileName);
            return true;
        }

        _logger.LogError("Failed to generate subtitles. {Error}", result.StandardError);
        return false;
    }

    private async Task<bool> ExtractSubtitlesStream(
        string inputFile,
        ProbeResult.FFprobeStream stream,
        string outputFile,
        CancellationToken cancellationToken)
    {
        BufferedCommandResult result = await Cli.Wrap("mkvextract")
            .WithArguments(new[] { inputFile, "tracks", $"{stream.index}:{outputFile}" })
            .WithValidation(CommandResultValidation.None)
            .ExecuteBufferedAsync(cancellationToken);

        if (result.ExitCode == 0)
        {
            return true;
        }

        _logger.LogError("Failed to extract subtitles. {Error}", result.StandardError);
        return false;
    }

    private async Task<ProbeStreamsResult> ProbeStreams(
        string fileName,
        CancellationToken cancellationToken)
    {
        BufferedCommandResult result = await Cli.Wrap("ffprobe")
            .WithArguments(new[] { "-v", "quiet", "-print_format", "json", "-show_streams", "-i", fileName })
            .WithValidation(CommandResultValidation.None)
            .ExecuteBufferedAsync(Encoding.UTF8, cancellationToken);

        if (result.ExitCode != 0)
        {
            _logger.LogWarning("FFprobe exited with code {Code}", result.ExitCode);
            return new ProbeStreamsResult(None, None);
        }

        Option<ProbeResult.FFprobe> maybeProbeOutput =
            JsonConvert.DeserializeObject<ProbeResult.FFprobe>(result.StandardOutput);

        Option<ProbeResult.FFprobeStream> maybeSubtitles = maybeProbeOutput
            .Map(ff => ff.streams.Filter(s => s.codec_type == "subtitle")).Flatten()
            .OrderBy(s => s.disposition.@default == 1 ? 0 : 1)
            .ThenBy(s => CodecPriority(s.codec_name))
            .HeadOrNone();

        // only return the audio stream if there are no subtitles
        Option<ProbeResult.FFprobeStream> maybeAudio = None;
        if (maybeSubtitles.IsNone)
        {
            maybeAudio = maybeProbeOutput
                .Map(ff => ff.streams.Filter(s => s.codec_type == "audio")).Flatten()
                .OrderBy(s => s.disposition.@default == 1 ? 0 : 1)
                .HeadOrNone();
        }

        return new ProbeStreamsResult(maybeAudio, maybeSubtitles);
    }

    private static int CodecPriority(string codecName) =>
        codecName switch
        {
            "subrip" or "mov_text" => 0,
            "dvd_subtitle" => 1,
            _ => 2 // pgs
        };

    private string GetFileName(string hash, ProbeResult.FFprobeStream stream)
    {
        string folderOne = hash[..2];
        string folderTwo = hash[2..4];

        string targetFolder = Path.Combine(_extractedFolder, folderOne, folderTwo);
        if (!Directory.Exists(targetFolder))
        {
            Directory.CreateDirectory(targetFolder);
        }

        string baseFileName = Path.Combine(targetFolder, hash);

        return $"{baseFileName}.{Extension(stream.codec_name)}";
    }

    private static string Extension(string codecName) =>
        codecName switch
        {
            "subrip" or "mov_text" => "srt",
            "dvd_subtitle" => "sub",
            "hdmv_pgs_subtitle" => "sup",
            _ => throw new NotSupportedException(codecName)
        };

    [SuppressMessage("ReSharper", "IdentifierTypo")]
    [SuppressMessage("ReSharper", "ClassNeverInstantiated.Global")]
    [SuppressMessage("ReSharper", "InconsistentNaming")]
    public class ProbeResult
    {
        public record FFprobe(List<FFprobeStream> streams);

        public record FFprobeDisposition(int @default);

        public record FFprobeStream(
            int index,
            string codec_name,
            string codec_type,
            int channels,
            FFprobeDisposition disposition);
    }

    private record ProbeStreamsResult(
        Option<ProbeResult.FFprobeStream> MaybeAudio,
        Option<ProbeResult.FFprobeStream> MaybeSubtitles);
}
