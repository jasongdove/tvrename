using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Text;
using CliWrap;
using CliWrap.Buffered;
using CliWrap.Builders;
using Newtonsoft.Json;
using Serilog;

namespace TvRename.Subtitles;

public static class SubtitleExtractor
{
    private static readonly string AppDataFolder = Path.Combine(
        Environment.GetFolderPath(
            Environment.SpecialFolder.LocalApplicationData,
            Environment.SpecialFolderOption.Create),
        "tvrename");

    private static readonly string ExtractedFolder = Path.Combine(AppDataFolder, "cache", "extracted");

    public static async Task<Either<Exception, string>> ExtractSubtitles(string fileName)
    {
        string hash = OpenSubtitlesHasher.ComputeMovieHash(fileName);
        Log.Information(
            "Found unknown episode {File} with hash {Hash}",
            Path.GetFileName(fileName),
            hash);

        Option<ProbeResult.FFprobeStream> maybeStream = await ProbeSubtitlesStream(fileName);
        if (maybeStream.IsNone)
        {
            // TODO: generate subtitles
            return string.Empty;
        }

        foreach (ProbeResult.FFprobeStream stream in maybeStream)
        {
            Log.Information(
                "Probed subtitles stream index {Index} with codec {Codec}",
                stream.index,
                stream.codec_name);

            // TODO: extract subtitles
            string targetFile = GetFileName(hash, stream);
            if (!File.Exists(targetFile))
            {
                Log.Information("Target file: {File}", targetFile);

                if (!await ExtractSubtitlesStream(fileName, stream, targetFile))
                {
                    return string.Empty;
                }
            }
        }

        return string.Empty;
    }

    private static async Task<bool> ExtractSubtitlesStream(
        string inputFile,
        ProbeResult.FFprobeStream stream,
        string outputFile)
    {
        ArgumentsBuilder args = new ArgumentsBuilder()
            .Add("-nostdin")
            .Add("-hide_banner")
            .Add("-i").Add(inputFile)
            .Add("-map").Add($"0:{stream.index}")
            .Add("-c:s").Add(stream.codec_name == "mov_text" ? "text" : "copy")
            .Add(outputFile);

        BufferedCommandResult result = await Cli.Wrap(OperatingSystem.IsWindows() ? "ffmpeg.exe" : "ffmpeg")
            .WithArguments(args.Build())
            .WithValidation(CommandResultValidation.None)
            .ExecuteBufferedAsync();

        if (result.ExitCode == 0)
        {
            Log.Debug("Successfully extracted subtitles");
            return true;
        }

        Log.Error("Failed to extract subtitles. {Error}", result.StandardError);
        return false;
    }

    private static async Task<Option<ProbeResult.FFprobeStream>> ProbeSubtitlesStream(string fileName)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = OperatingSystem.IsWindows() ? "ffprobe.exe" : "ffprobe",
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };

        startInfo.ArgumentList.Add("-v");
        startInfo.ArgumentList.Add("quiet");
        startInfo.ArgumentList.Add("-print_format");
        startInfo.ArgumentList.Add("json");
        startInfo.ArgumentList.Add("-show_streams");
        startInfo.ArgumentList.Add("-i");
        startInfo.ArgumentList.Add(fileName);

        var probe = new Process
        {
            StartInfo = startInfo
        };

        probe.Start();
        string output = await probe.StandardOutput.ReadToEndAsync();
        await probe.WaitForExitAsync();
        if (probe.ExitCode != 0)
        {
            Log.Warning("FFprobe exited with code {Code}", probe.ExitCode);
            return None;
        }

        Option<ProbeResult.FFprobe> maybeProbeOutput = JsonConvert.DeserializeObject<ProbeResult.FFprobe>(output);
        var subtitleStreams = maybeProbeOutput
            .Map(ff => ff.streams.Filter(s => s.codec_type == "subtitle")).Flatten()
            .OrderBy(s => s.disposition.@default == 1 ? 0 : 1)
            .ThenBy(s => CodecPriority(s.codec_name))
            .ToList();

        return subtitleStreams.HeadOrNone();
    }

    private static int CodecPriority(string codecName) =>
        codecName switch
        {
            "subrip" or "mov_text" => 0,
            "dvd_subtitle" => 1,
            _ => 2 // pgs
        };

    private static string GetFileName(string hash, ProbeResult.FFprobeStream stream)
    {
        string folderOne = hash[..2];
        string folderTwo = hash[2..4];

        string targetFolder = Path.Combine(ExtractedFolder, folderOne, folderTwo);
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
            "dvd_subtitle" => "vob",
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

        public record FFprobeStream(int index, string codec_name, string codec_type, FFprobeDisposition disposition);
    }
}
