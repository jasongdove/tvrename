using System.CommandLine;
using Serilog;
using TvRename.Logic;

var imdbOption = new System.CommandLine.Option<string>("--imdb", "The imdb id of the series") { IsRequired = true };
var titleOption = new System.CommandLine.Option<string?>("--title", "The title of the series") { IsRequired = false };
var seasonOption = new System.CommandLine.Option<int?>("--season", "The season number") { IsRequired = false };
var folderArgument = new Argument<string>("folder", "The folder containing the media")
    { Arity = ArgumentArity.ExactlyOne };

var rootCommand = new RootCommand
{
    imdbOption,
    titleOption,
    seasonOption,
    folderArgument
};

rootCommand.Description = "Tv Rename";

rootCommand.SetHandler(
    async (string imdb, string? title, int? season, string folder) =>
    {
        Log.Logger = new LoggerConfiguration()
            .MinimumLevel.Debug()
            .WriteTo.Console()
            .CreateLogger();

        await RemuxLogic.Run(imdb, title, season, folder);
    },
    imdbOption,
    titleOption,
    seasonOption,
    folderArgument);

return await rootCommand.InvokeAsync(args);
