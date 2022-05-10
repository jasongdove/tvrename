using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Text;
using CliWrap;
using CliWrap.Buffered;
using Newtonsoft.Json;
using Serilog;
using TvRename.Models;

namespace TvRename.Subtitles;

public static class SubtitleExtractor
{
    private static readonly string AppDataFolder = Path.Combine(
        Environment.GetFolderPath(
            Environment.SpecialFolder.LocalApplicationData,
            Environment.SpecialFolderOption.Create),
        "tvrename");

    private static readonly string ExtractedFolder = Path.Combine(AppDataFolder, "cache", "extracted");

    public static async Task<Either<Exception, ExtractedSubtitles>> ExtractSubtitles(string fileName)
    {
        string hash = OpenSubtitlesHasher.ComputeMovieHash(fileName);
        Log.Information(
            "Found unknown episode {File} with hash {Hash}",
            Path.GetFileName(fileName),
            hash);

        string srtFileName = GetFileName(
            hash,
            new ProbeResult.FFprobeStream(0, "subrip", string.Empty, new ProbeResult.FFprobeDisposition(0)));

        if (File.Exists(srtFileName))
        {
            return new ExtractedSrtSubtitles(srtFileName);
        }

        Option<ProbeResult.FFprobeStream> maybeStream = await ProbeSubtitlesStream(fileName);
        if (maybeStream.IsNone)
        {
            // TODO: generate subtitles
            return new NotSupportedException("Subtitle generation is not yet supported");
        }

        foreach (ProbeResult.FFprobeStream stream in maybeStream)
        {
            Log.Information(
                "Probed subtitles stream index {Index} with codec {Codec}",
                stream.index,
                stream.codec_name);

            string targetFile = GetFileName(hash, stream);
            if (File.Exists(targetFile))
            {
                return ExtractedSubtitles.ForCodec(stream.codec_name, targetFile);
            }

            Log.Information("Extracting subtitles to: {File}", targetFile);

            if (await ExtractSubtitlesStream(fileName, stream, targetFile))
            {
                return ExtractedSubtitles.ForCodec(stream.codec_name, targetFile);
            }
        }

        // this shouldn't happen
        return new Exception("Unable to probe for subtitles");
    }

    private static async Task<bool> ExtractSubtitlesStream(
        string inputFile,
        ProbeResult.FFprobeStream stream,
        string outputFile)
    {
        BufferedCommandResult result = await Cli.Wrap("mkvextract")
            .WithArguments(new[] { inputFile, "tracks", $"{stream.index}:{outputFile}" })
            .WithValidation(CommandResultValidation.None)
            .ExecuteBufferedAsync();

        if (result.ExitCode == 0)
        {
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

        public record FFprobeStream(int index, string codec_name, string codec_type, FFprobeDisposition disposition);
    }
}
