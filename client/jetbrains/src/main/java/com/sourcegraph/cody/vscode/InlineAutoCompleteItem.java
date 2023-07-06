package com.sourcegraph.cody.vscode;

public class InlineAutoCompleteItem {
  public final String insertText;
  public final String filterText;
  public final Range range;
  public final Command command;

  public InlineAutoCompleteItem(
      String insertText, String filterText, Range range, Command command) {
    this.insertText = insertText;
    this.filterText = filterText;
    this.range = range;
    this.command = command;
  }

  public InlineAutoCompleteItem withInsertText(String newInsertText) {
    return new InlineAutoCompleteItem(newInsertText, this.filterText, this.range, this.command);
  }

  public InlineAutoCompleteItem withFilterText(String newFilterText) {
    return new InlineAutoCompleteItem(this.insertText, newFilterText, this.range, this.command);
  }

  public InlineAutoCompleteItem withRange(Range newRange) {
    return new InlineAutoCompleteItem(this.insertText, this.filterText, newRange, this.command);
  }

  public InlineAutoCompleteItem withCommand(Command newCommand) {
    return new InlineAutoCompleteItem(this.insertText, this.filterText, this.range, newCommand);
  }

  public static InlineAutoCompleteItem fromCompletion(Completion completion) {
    return new InlineAutoCompleteItem(
        completion.content,
        completion.prefix,
        new Range(new Position(0, 0), new Position(0, completion.content.length())),
        null);
  }

  @Override
  public String toString() {
    return "InlineAutoCompleteItem{"
        + "insertText='"
        + insertText
        + '\''
        + ", filterText='"
        + filterText
        + '\''
        + ", range="
        + range
        + ", command="
        + command
        + '}';
  }
}