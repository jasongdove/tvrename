using System.CommandLine;
using System.CommandLine.Invocation;
using TvRename.Logic;

namespace TvRename.Commands;

public class Verify : Command
{
    public Verify() : base("verify")
    {
        AddOption(new System.CommandLine.Option<string>("--imdb", "The imdb id of the series") { IsRequired = true });
        AddOption(new System.CommandLine.Option<string?>("--title", "The title of the series") { IsRequired = false });
        AddOption(new System.CommandLine.Option<int?>("--season", "The season number") { IsRequired = false });
        AddOption(
            new System.CommandLine.Option<int?>("--confidence", "The minimum confidence required to match")
                { IsRequired = false });
        AddArgument(
            new Argument<string>("folder", "The folder containing the media") { Arity = ArgumentArity.ExactlyOne });
    }

    public new class Handler : ICommandHandler
    {
        private readonly VerifyLogic _verifyLogic;

        public Handler(VerifyLogic verifyLogic) => _verifyLogic = verifyLogic;

        public string? Imdb { get; set; }
        public string? Title { get; set; }
        public int? Season { get; set; }
        public string? Folder { get; set; }
        public int? Confidence { get; set; }

        public int Invoke(InvocationContext context) => throw new NotImplementedException();

        public async Task<int> InvokeAsync(InvocationContext context) =>
            await _verifyLogic.Run(Imdb!, Title, Season, Folder!, Confidence, context.GetCancellationToken());
    }
}
