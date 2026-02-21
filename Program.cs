using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Serilog;
using TvRename.Logic;
using TvRename.Subtitles;

namespace TvRename;

public class Program
{
    public static async Task<int> Main(string[] args)
    {
        string executablePath = Environment.ProcessPath!;
        string executable = Path.GetFileNameWithoutExtension(executablePath);

        IConfigurationBuilder builder = new ConfigurationBuilder();

        string basePath = Path.GetDirectoryName(
            "dotnet".Equals(executable, StringComparison.InvariantCultureIgnoreCase)
                ? typeof(Program).Assembly.Location
                : executablePath)!;

        IConfigurationRoot configuration = builder
            .SetBasePath(basePath)
            .AddJsonFile("TvRename.json", false, true)
            .AddEnvironmentVariables()
            .Build();

        Log.Logger = new LoggerConfiguration()
            .ReadFrom.Configuration(configuration)
            .Enrich.FromLogContext()
            .CreateLogger();

        try
        {
            Environment.SetEnvironmentVariable("DOTNET_HOSTBUILDER__RELOADCONFIGONCHANGE", "false");
            await CreateHostBuilder(args).Build().RunAsync();
            return 0;
        }
        catch (Exception ex)
        {
            Log.Fatal(ex, "TvRename host terminated unexpectedly");
            return 1;
        }
        finally
        {
            await Log.CloseAndFlushAsync();
        }
    }

    private static IHostBuilder CreateHostBuilder(string[] args)
    {
        return Host.CreateDefaultBuilder(args)
            .ConfigureServices((_, services) =>
            {
                services.AddSingleton<RenameLogic>();
                services.AddSingleton<VerifyLogic>();
                services.AddSingleton<OpenSubtitlesApiClient>();
                services.AddSingleton<ReferenceSubtitleDownloader>();
                services.AddSingleton<SubtitleExtractor>();
                services.AddSingleton<SubtitleProcessor>();
                services.AddSingleton<SubtitleMatcher>();

                services.AddHostedService<Worker>();
            })
            .UseSerilog();
    }
}
