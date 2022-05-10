using System.CommandLine;
using System.CommandLine.Builder;
using System.CommandLine.Hosting;
using System.CommandLine.Parsing;
using System.Diagnostics;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Serilog;
using TvRename;
using TvRename.Logic;

string executablePath = Process.GetCurrentProcess().MainModule.FileName;
string executable = Path.GetFileNameWithoutExtension(executablePath);

IConfigurationBuilder builder = new ConfigurationBuilder();

string basePath = Path.GetDirectoryName(
    "dotnet".Equals(executable, StringComparison.InvariantCultureIgnoreCase)
        ? typeof(Program).Assembly.Location
        : executablePath);

IConfigurationRoot configuration = builder
    .SetBasePath(basePath)
    .AddJsonFile("TvRename.json", false, true)
    .AddEnvironmentVariables()
    .Build();

Log.Logger = new LoggerConfiguration()
    .ReadFrom.Configuration(configuration)
    .Enrich.FromLogContext()
    .CreateLogger();

var root = new RootCommand();

root.AddCommand(new Rename());
root.AddCommand(new Verify());

Parser runner = new CommandLineBuilder(root)
    .UseHost(
        _ => new HostBuilder(),
        builder => builder
            .ConfigureServices(
                (_, services) => { services.AddSingleton<RemuxLogic>(); })
            .UseCommandHandler<Rename, Rename.Handler>()
            .UseCommandHandler<Verify, Verify.Handler>()
            .UseSerilog())
    .UseDefaults().Build();

await runner.InvokeAsync(args);
