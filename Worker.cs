using System.CommandLine;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using TvRename.Logic;

namespace TvRename;

public class Worker(IServiceScopeFactory serviceScopeFactory, IHostApplicationLifetime appLifetime) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        RootCommand rootCommand = ConfigureCommandLine();

        // need to strip program name (head) from command line args
        string[] arguments = Environment.GetCommandLineArgs().Skip(1).ToArray();

        ParseResult parseResult = rootCommand.Parse(arguments);
        await parseResult.InvokeAsync(cancellationToken: stoppingToken);

        appLifetime.StopApplication();
    }

    private RootCommand ConfigureCommandLine()
    {
        var imdbOption = new System.CommandLine.Option<string>("--imdb")
        {
            Description = "The imdb id of the series",
            Required = true
        };

        var titleOption = new System.CommandLine.Option<string?>("--title")
        {
            Description = "The title of the series",
            Required = false
        };

        var seasonOption = new System.CommandLine.Option<int?>("--season")
        {
            Description = "The season number",
            Required = false
        };

        var confidenceOption = new System.CommandLine.Option<int?>("--confidence")
        {
            Description = "The minimum confidence required to match",
            Required = false
        };

        var dryRunOption = new System.CommandLine.Option<bool>("--dry-run")
        {
            Description = "Dry run",
            Required = false,
            Arity = ArgumentArity.Zero
        };

        var folderArgument = new Argument<string>("folder")
        {
            Description = "The folder containing the media"
        };

        var renameCommand = new Command("rename");
        renameCommand.Arguments.Add(folderArgument);
        renameCommand.Options.Add(imdbOption);
        renameCommand.Options.Add(titleOption);
        renameCommand.Options.Add(seasonOption);
        renameCommand.Options.Add(confidenceOption);
        renameCommand.Options.Add(dryRunOption);
        
        renameCommand.SetAction(async (parseResult, token) =>
        {
            string? imdb = parseResult.GetValue(imdbOption);
            string? title = parseResult.GetValue(titleOption);
            int? season = parseResult.GetValue(seasonOption);
            int? confidence = parseResult.GetValue(confidenceOption);
            bool dryRun = parseResult.GetValue(dryRunOption);
            string? folder = parseResult.GetValue(folderArgument);

            if (imdb is null || folder is null)
            {
                return -1;
            }
            
            using IServiceScope scope = serviceScopeFactory.CreateScope();
            RenameLogic renameLogic = scope.ServiceProvider.GetRequiredService<RenameLogic>();
            return await renameLogic.Run(imdb, title, season, folder, confidence, dryRun, token);
        });
        
        var verifyCommand = new Command("verify");
        verifyCommand.Arguments.Add(folderArgument);
        verifyCommand.Options.Add(imdbOption);
        verifyCommand.Options.Add(titleOption);
        verifyCommand.Options.Add(seasonOption);
        verifyCommand.Options.Add(confidenceOption);
        
        verifyCommand.SetAction(async (parseResult, token) =>
        {
            string? imdb = parseResult.GetValue(imdbOption);
            string? title = parseResult.GetValue(titleOption);
            int? season = parseResult.GetValue(seasonOption);
            int? confidence = parseResult.GetValue(confidenceOption);
            string? folder = parseResult.GetValue(folderArgument);

            if (imdb is null || folder is null)
            {
                return -1;
            }
            
            using IServiceScope scope = serviceScopeFactory.CreateScope();
            VerifyLogic verifyLogic = scope.ServiceProvider.GetRequiredService<VerifyLogic>();
            return await verifyLogic.Run(imdb, title, season, folder, confidence, token);
        });
        
        var root = new RootCommand();
        root.Subcommands.Add(renameCommand);
        root.Subcommands.Add(verifyCommand);

        return root;
    }
}
