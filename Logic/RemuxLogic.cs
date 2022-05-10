using System.Text;
using System.Text.RegularExpressions;
using Serilog;
using SubtitlesParser.Classes;
using SubtitlesParser.Classes.Parsers;
using TvRename.Classifier;
using TvRename.Models;
using TvRename.Subtitles;

namespace TvRename.Logic;

public class RemuxLogic
{
    private static readonly Regex ReferencePattern = new(@".*s([\d]{2})e([\d]{2}).*");

    public async Task<int> Run(string imdb, string? title, int? season, string folder, int? confidence, bool dryRun)
    {
        string fullPath = Path.GetFullPath(folder);

        // TODO: validate parameters
        if (!Directory.Exists(fullPath))
        {
            Console.WriteLine("folder must be a directory");
        }

        string verifiedFileName = Path.Combine(fullPath, ".tvrename-verified");
        if (File.Exists(verifiedFileName))
        {
            return 0;
        }

        Option<string> maybeShowTitle = GetTitleFromFolder(Optional(title), fullPath);
        Option<int> maybeSeasonNumber = GetSeasonFromFolder(Optional(season), fullPath);
        if (maybeShowTitle.IsNone || maybeSeasonNumber.IsNone)
        {
            Log.Fatal(
                "Unable to detect show title {ShowTitle} or season number {SeasonNumber}",
                maybeShowTitle,
                maybeSeasonNumber);
            return 1;
        }

        foreach (string showTitle in maybeShowTitle)
        {
            foreach (int seasonNumber in maybeSeasonNumber)
            {
                Log.Information("Detected show title {ShowTitle}", showTitle);
                Log.Information("Detected season number {SeasonNumber}", seasonNumber);

                // download expected subtitles
                var downloader = new ReferenceSubtitleDownloader(showTitle, imdb, seasonNumber, fullPath);
                int _ = await downloader.Download();

                // load reference subtitles
                List<ReferenceSubtitles> referenceSubtitles = await LoadReferenceSubtitles(fullPath);

                // find unknown episodes
                foreach (string unknownEpisode in RemuxEpisodeClassifier.FindUnknownEpisodes(fullPath))
                {
                    // probe and extract subtitles from episode
                    Either<Exception, ExtractedSubtitles> extractResult =
                        await SubtitleExtractor.ExtractSubtitles(unknownEpisode);
                    if (extractResult.IsLeft)
                    {
                        return 2;
                    }

                    // process subtitles
                    foreach (ExtractedSubtitles extractedSubtitles in extractResult.RightToSeq())
                    {
                        Either<Exception, List<string>> extractedLines =
                            await SubtitleProcessor.ConvertToLines(extractedSubtitles);
                        foreach (List<string> lines in extractedLines.RightToSeq())
                        {
                            Option<MatchedEpisode> maybeMatch = await SubtitleMatcher.Match(referenceSubtitles, lines);
                            foreach (MatchedEpisode match in maybeMatch)
                            {
                                if (match.Confidence >= (confidence ?? 40))
                                {
                                    Log.Information(
                                        "Matched to Season {SeasonNumber} Episode {EpisodeNumber} with confidence {Confidence}",
                                        match.SeasonNumber,
                                        match.EpisodeNumber,
                                        match.Confidence);

                                    if (!dryRun)
                                    {
                                        string newFileName =
                                            $"{showTitle} - s{match.SeasonNumber:00}e{match.EpisodeNumber:00}.mkv";
                                        string newFullPath = Path.Combine(folder, newFileName);

                                        Log.Information("Renaming {Source} to {Dest}", unknownEpisode, newFullPath);
                                        File.Move(unknownEpisode, newFullPath);
                                    }
                                }
                                else
                                {
                                    Log.Warning("Match failed; confidence of {Confidence} is too low", match.Confidence);
                                }
                            }
                        }
                    }
                }
            }
        }

        return 0;
    }

    private static async Task<List<ReferenceSubtitles>> LoadReferenceSubtitles(string targetFolder)
    {
        var parser = new SrtParser();
        var result = new List<ReferenceSubtitles>();

        string referenceFolder = Path.Combine(targetFolder, ".tvrename", "reference");
        foreach (string referenceFile in Directory.EnumerateFiles(
                     referenceFolder,
                     "*.srt",
                     SearchOption.TopDirectoryOnly))
        {
            Match match = ReferencePattern.Match(referenceFile);
            if (match.Success)
            {
                var seasonNumber = int.Parse(match.Groups[1].Value);
                var episodeNumber = int.Parse(match.Groups[2].Value);
                await using FileStream fs = File.OpenRead(referenceFile);
                Option<List<SubtitleItem>> maybeParsed = parser.ParseStream(fs, Encoding.UTF8);
                foreach (List<SubtitleItem> parsed in maybeParsed)
                {
                    var allLines = new List<string>();
                    foreach (SubtitleItem item in parsed)
                    {
                        allLines.AddRange(item.PlaintextLines.Map(s => s.Replace("-", string.Empty)));
                    }

                    var contents = string.Join(' ', allLines);
                    result.Add(new ReferenceSubtitles(seasonNumber, episodeNumber, contents));
                }
            }
        }

        return result;
    }

    private static Option<string> GetTitleFromFolder(Option<string> maybeTitle, string folder)
    {
        foreach (string title in maybeTitle)
        {
            return title;
        }

        Option<DirectoryInfo> maybeSeasonFolder = Optional(new DirectoryInfo(folder));
        foreach (DirectoryInfo seasonFolder in maybeSeasonFolder)
        {
            Option<DirectoryInfo> maybeShowFolder = Optional(seasonFolder.Parent);
            foreach (DirectoryInfo showFolder in maybeShowFolder)
            {
                const string PATTERN = @"^(.*?)[\s.]+?[.\(](\d{4})[.\)].*$";
                Match match = Regex.Match(showFolder.Name, PATTERN);
                if (match.Success)
                {
                    return match.Groups[1].Value;
                }

                return showFolder.Name;
            }
        }

        return None;
    }

    private static Option<int> GetSeasonFromFolder(Option<int> maybeSeason, string folder)
    {
        foreach (int season in maybeSeason)
        {
            return season;
        }

        Option<DirectoryInfo> maybeSeasonFolder = Optional(new DirectoryInfo(folder));
        foreach (DirectoryInfo seasonFolder in maybeSeasonFolder)
        {
            if (int.TryParse(seasonFolder.Name.Split(" ").Last(), out int seasonNumber))
            {
                return seasonNumber;
            }

            if (seasonFolder.Name.EndsWith("specials", StringComparison.OrdinalIgnoreCase))
            {
                return 0;
            }
        }

        return None;
    }
}
