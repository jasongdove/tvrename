using System.Text.RegularExpressions;

namespace TvRename.Logic;

public static class RemuxLogic
{
    public static async Task Run(string imdb, string? title, int? season, string folder)
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
            return;
        }

        Option<string> showTitle = GetTitleFromFolder(Optional(title), fullPath);
        Option<int> seasonNumber = GetSeasonFromFolder(Optional(season), fullPath);

        Console.WriteLine($"Detected show title:\t{showTitle}");
        Console.WriteLine($"Detected season number:\t{seasonNumber}");
        
        // TODO: download expected subtitles
        // TODO: find unknown episodes
        // TODO: identify and validate episodes
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