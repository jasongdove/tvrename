using System.CommandLine;
using System.CommandLine.Invocation;
using TvRename.Logic;

namespace TvRename.Commands;

public class Rename : Command
{
    public Rename() : base("rename")
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
        private readonly RenameLogic _renameLogic;

        public Handler(RenameLogic renameLogic) => _renameLogic = renameLogic;

        public string? Imdb { get; set; }
        public string? Title { get; set; }
        public int? Season { get; set; }
        public string? Folder { get; set; }
        public int? Confidence { get; set; }

        public bool DryRun { get; set; }

        public int Invoke(InvocationContext context) => throw new NotImplementedException();
        public async Task<int> InvokeAsync(InvocationContext context) =>
            await _renameLogic.Run(Imdb!, Title, Season, Folder!, Confidence, DryRun, context.GetCancellationToken());
    }
}
