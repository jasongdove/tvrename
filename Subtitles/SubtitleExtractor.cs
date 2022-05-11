﻿using System.Diagnostics;
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

    public async Task<Either<Exception, ExtractedSubtitles>> ExtractSubtitles(string fileName)
    {
        string hash = OpenSubtitlesHasher.ComputeMovieHash(fileName);
        _logger.LogInformation(
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
            return new NotSupportedException("Subtitle generation via speech-to-text is not yet implemented");
        }

        foreach (ProbeResult.FFprobeStream stream in maybeStream)
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

            if (await ExtractSubtitlesStream(fileName, stream, targetFile))
            {
                return ExtractedSubtitles.ForCodec(stream.codec_name, targetFile);
            }
        }

        // this shouldn't happen
        return new Exception("Unable to probe for subtitles");
    }

    private async Task<bool> ExtractSubtitlesStream(
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

        _logger.LogError("Failed to extract subtitles. {Error}", result.StandardError);
        return false;
    }

    private async Task<Option<ProbeResult.FFprobeStream>> ProbeSubtitlesStream(string fileName)
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
            _logger.LogWarning("FFprobe exited with code {Code}", probe.ExitCode);
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

        public record FFprobeStream(int index, string codec_name, string codec_type, FFprobeDisposition disposition);
    }
}
