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

    public async Task<Either<Exception, List<ExtractedSubtitles>>> ExtractSubtitles(
        string fileName,
        CancellationToken cancellationToken)
    {
        string hash = OpenSubtitlesHasher.ComputeMovieHash(fileName);
        _logger.LogInformation("Found episode {File} with hash {Hash}", Path.GetFileName(fileName), hash);

        // check for generated subtitles
        string srtFileName = GetFileName(
            hash,
            new ProbeResult.FFprobeStream(-1, "subrip", string.Empty, 0, new ProbeResult.FFprobeDisposition(0, 0)));

        if (File.Exists(srtFileName))
        {
            return new List<ExtractedSubtitles> { new ExtractedSrtSubtitles(srtFileName, -1) };
        }

        // find all subtitle streams
        ProbeStreamsResult streamsResult = await ProbeStreams(fileName, cancellationToken);
        if (!streamsResult.MaybeSubtitles.Any())
        {
            Option<string> maybeWhisperModel = Environment.GetEnvironmentVariable("WHISPER_MODEL");
            if (maybeWhisperModel.IsSome)
            {
                _logger.LogInformation("Generating subtitles file {File}", srtFileName);

                int audioChannels = streamsResult.MaybeAudio.Map(s => s.channels).IfNone(2);
                string model = maybeWhisperModel.IfNone(string.Empty);

                if (await GenerateSubtitles(fileName, srtFileName, audioChannels, model, cancellationToken))
                {
                    return new List<ExtractedSubtitles> { new ExtractedSrtSubtitles(srtFileName, -1) };
                }
            }
            else
            {
                return new NotSupportedException(
                    "Subtitle generation via speech-to-text requires the WHISPER_MODEL environment variable to be set");
            }
        }

        var result = new List<ExtractedSubtitles>();
        foreach (ProbeResult.FFprobeStream stream in streamsResult.MaybeSubtitles)
        {
            _logger.LogInformation(
                "Probed subtitles stream index {Index} with codec {Codec}",
                stream.index,
                stream.codec_name);

            string targetFile = GetFileName(hash, stream);
            string targetSrtFile = GetFileName(
                hash,
                new ProbeResult.FFprobeStream(
                    stream.index,
                    "subrip",
                    string.Empty,
                    0,
                    new ProbeResult.FFprobeDisposition(0, 0)));

            if (File.Exists(targetSrtFile))
            {
                result.Add(ExtractedSubtitles.ForCodec("subrip", targetSrtFile, stream.index));
            }
            else if (File.Exists(targetFile))
            {
                result.Add(ExtractedSubtitles.ForCodec(stream.codec_name, targetFile, stream.index));
            }
            else
            {
                _logger.LogInformation("Extracting subtitles to: {File}", targetFile);

                if (await ExtractSubtitlesStream(fileName, stream, targetFile, cancellationToken))
                {
                    result.Add(ExtractedSubtitles.ForCodec(stream.codec_name, targetFile, stream.index));
                }
            }
        }

        if (result.Any())
        {
            return result;
        }

        // this shouldn't happen
        return new Exception("Unable to probe for subtitles");
    }

    private async Task<bool> GenerateSubtitles(
        string fileName,
        string srtFileName,
        int audioChannels,
        string model,
        CancellationToken cancellationToken)
    {
        string tempWav = Path.GetTempFileName() + ".wav";

        string audioFilter = audioChannels switch
        {
            1 => "pan=gain=1,speechnorm",
            2 => "pan=mono|c0<FL+FR,speechnorm",
            _ => "pan=mono|c0=FC,speechnorm"
        };

        BufferedCommandResult ffmpegResult = await Cli.Wrap("ffmpeg")
            .WithArguments([
                "-i", fileName,
                "-vn", "-map", "0:a:0",
                "-af", audioFilter,
                "-ar", "16000",
                "-ac", "1",
                "-y", tempWav
            ])
            .WithValidation(CommandResultValidation.None)
            .ExecuteBufferedAsync(cancellationToken);

        if (ffmpegResult.ExitCode != 0)
        {
            _logger.LogError("Failed to extract audio. {Error}", ffmpegResult.StandardError);
            return false;
        }

        // whisper-cli writes to {prefix}.srt
        string srtPrefix = Path.ChangeExtension(tempWav, null);

        BufferedCommandResult whisperResult = await Cli.Wrap("whisper-cli")
            .WithArguments([
                "-m", model,
                "-f", tempWav,
                "--output-srt",
                "--output-file", srtPrefix
            ])
            .WithValidation(CommandResultValidation.None)
            .ExecuteBufferedAsync(cancellationToken);

        File.Delete(tempWav);

        string generatedSrt = srtPrefix + ".srt";
        if (whisperResult.ExitCode == 0 && File.Exists(generatedSrt))
        {
            File.Move(generatedSrt, srtFileName);
            return true;
        }

        _logger.LogError("Failed to generate subtitles. {Error}", whisperResult.StandardError);
        return false;
    }

    private async Task<bool> ExtractSubtitlesStream(
        string inputFile,
        ProbeResult.FFprobeStream stream,
        string outputFile,
        CancellationToken cancellationToken)
    {
        BufferedCommandResult result = await Cli.Wrap("mkvextract")
            .WithArguments([inputFile, "tracks", $"{stream.index}:{outputFile}"])
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
            .WithArguments(["-v", "quiet", "-print_format", "json", "-show_streams", "-i", fileName])
            .WithValidation(CommandResultValidation.None)
            .ExecuteBufferedAsync(Encoding.UTF8, cancellationToken);

        if (result.ExitCode != 0)
        {
            _logger.LogWarning("FFprobe exited with code {Code}", result.ExitCode);
            return new ProbeStreamsResult(None, new List<ProbeResult.FFprobeStream>());
        }

        Option<ProbeResult.FFprobe> maybeProbeOutput =
            JsonConvert.DeserializeObject<ProbeResult.FFprobe>(result.StandardOutput);

        var maybeSubtitles = maybeProbeOutput
            .Map(ff => ff.streams.Filter(s => s.codec_type == "subtitle")).Flatten()
            .ToList();

        // only return the audio stream if there are no subtitles
        Option<ProbeResult.FFprobeStream> maybeAudio = None;
        if (!maybeSubtitles.Any())
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

        return $"{baseFileName}.{stream.index}.{Extension(stream.codec_name)}";
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

        public record FFprobeDisposition(int @default, int forced);

        public record FFprobeStream(
            int index,
            string codec_name,
            string codec_type,
            int channels,
            FFprobeDisposition disposition);
    }

    private record ProbeStreamsResult(
        Option<ProbeResult.FFprobeStream> MaybeAudio,
        List<ProbeResult.FFprobeStream> MaybeSubtitles);
}
