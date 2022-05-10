using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Text;
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
            List<string> targetFiles = GetFileNames(hash, stream);
            if (targetFiles.Any(f => !File.Exists(f)))
            {
                Log.Information("Target files: {Files}", targetFiles);
                
                string head = targetFiles.Head();
                // TODO: extract with ffmpeg
            }
        }

        return string.Empty;
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
            "subrip" => 0,
            "dvd_subtitle" => 1,
            _ => 2 // pgs
        };

    private static List<string> GetFileNames(string hash, ProbeResult.FFprobeStream stream)
    {
        string folderOne = hash[..2];
        string folderTwo = hash[2..4];

        string targetFolder = Path.Combine(ExtractedFolder, folderOne, folderTwo);
        if (!Directory.Exists(targetFolder))
        {
            Directory.CreateDirectory(targetFolder);
        }

        string baseFileName = Path.Combine(targetFolder, hash);

        return Extensions(stream.codec_name).Map(ext => $"{baseFileName}.{ext}").ToList();
    }

    private static IEnumerable<string> Extensions(string codecName) =>
        codecName switch
        {
            "subrip" => new[] { "srt" },
            "dvd_subtitle" => new[] { "sub", "idx" },
            "hdmv_pgs_subtitle" => new[] { "sup" },
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
