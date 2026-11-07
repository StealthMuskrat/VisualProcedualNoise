package dev.visualengine.authoring;

import java.util.*;

/** A bounded authoring failure with editor-compatible UTF-16 offsets and original node IDs. */
public final class AuthoringDiagnostic extends IllegalArgumentException {
  /** Zero-based, half-open offsets; line and column are one-based UTF-16 positions. */
  public record Span(int start, int end, int line, int column) {
    public Span {
      if (start < 0 || end < start || line < 1 || column < 1)
        throw new IllegalArgumentException("Invalid diagnostic span");
    }
  }

  private final String detail;
  private final Span span;
  private final List<String> nodePath;

  private AuthoringDiagnostic(String detail, Span span, List<String> nodePath, Throwable cause) {
    super(format(detail, span, nodePath), cause);
    this.detail = detail;
    this.span = span;
    this.nodePath = List.copyOf(nodePath);
  }

  public static AuthoringDiagnostic at(String source, int start, int end, String detail) {
    Objects.requireNonNull(source);
    if (start < 0 || end < start || end > source.length())
      throw new IllegalArgumentException("Diagnostic outside source");
    int line = 1, column = 1;
    for (int i = 0; i < start; i++) {
      char c = source.charAt(i);
      if (c == '\r' || c == '\n' && (i == 0 || source.charAt(i - 1) != '\r')) {
        line++;
        column = 1;
      } else if (c != '\n') column++;
    }
    return new AuthoringDiagnostic(detail, new Span(start, end, line, column), List.of(), null);
  }

  public static AuthoringDiagnostic inNodes(List<String> path, IllegalArgumentException cause) {
    if (cause instanceof AuthoringDiagnostic diagnostic) {
      if (!diagnostic.nodePath.isEmpty()) return diagnostic;
      return new AuthoringDiagnostic(diagnostic.detail, diagnostic.span, path, diagnostic);
    }
    return new AuthoringDiagnostic(cause.getMessage(), null, path, cause);
  }

  public String detail() {
    return detail;
  }

  public Span span() {
    return span;
  }

  public List<String> nodePath() {
    return nodePath;
  }

  private static String format(String detail, Span span, List<String> path) {
    return (path.isEmpty() ? "" : "Node " + String.join(" / ", path) + ": ")
        + detail
        + (span == null ? "" : " at line " + span.line() + ", column " + span.column());
  }
}
