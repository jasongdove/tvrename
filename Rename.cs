using System.CommandLine;
using System.CommandLine.Invocation;
using TvRename.Logic;

namespace TvRename;

public class Rename : RootCommand
{
    public Rename()
    {
        AddOption(new System.CommandLine.Option<string>("--imdb", "The imdb id of the series") { IsRequired = true });
        AddOption(new System.CommandLine.Option<string?>("--title", "The title of the series") { IsRequired = false });
        AddOption(new System.CommandLine.Option<int?>("--season", "The season number") { IsRequired = false });
        AddOption(
            new System.CommandLine.Option<int?>("--confidence", "The minimum confidence required to match")
                { IsRequired = false });
        AddOption(
            new System.CommandLine.Option<bool>("--dry-run", "Dry run")
                { IsRequired = false, Arity = ArgumentArity.Zero });
        AddArgument(
            new Argument<string>("folder", "The folder containing the media") { Arity = ArgumentArity.ExactlyOne });
    }

    public new class Handler : ICommandHandler
    {
        private readonly RemuxLogic _remuxLogic;

        public Handler(RemuxLogic remuxLogic) => _remuxLogic = remuxLogic;

        public string? Imdb { get; set; }
        public string? Title { get; set; }
        public int? Season { get; set; }
        public string? Folder { get; set; }
        public int? Confidence { get; set; }

        public bool DryRun { get; set; }

        public async Task<int> InvokeAsync(InvocationContext context) =>
            await _remuxLogic.Run(Imdb!, Title, Season, Folder!, Confidence, DryRun);
    }
}
