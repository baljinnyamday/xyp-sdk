package io.github.baljinnyamday.xyp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The README's Java snippets are compiled: each one must appear, token for token, in
 * readme/ReadmeExamples.java, which the test build compiles against the real API.
 */
class ReadmeTest {

  private static final Path README = Path.of("README.md");
  private static final Path EXAMPLES =
      Path.of("src/test/java/io/github/baljinnyamday/xyp/readme/ReadmeExamples.java");
  private static final Pattern JAVA_BLOCK = Pattern.compile("```java\\n(.*?)```", Pattern.DOTALL);
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private static List<String> snippets() throws IOException {
    Matcher block = JAVA_BLOCK.matcher(Files.readString(README));
    List<String> snippets = new ArrayList<>();
    while (block.find()) {
      snippets.add(block.group(1));
    }
    return snippets;
  }

  private static String tokens(String code) {
    return WHITESPACE.matcher(code).replaceAll("");
  }

  @Test
  void everyJavaSnippetInTheReadmeIsCompiled() throws IOException {
    // A downloaded source jar holds neither file.
    assumeTrue(Files.exists(README) && Files.exists(EXAMPLES), "not a checkout");
    String examples = Files.readString(EXAMPLES);
    String compiled = tokens(examples);
    List<String> snippets = snippets();
    assertTrue(snippets.size() >= 12, "only " + snippets.size() + " Java snippets");
    for (String snippet : snippets) {
      if (snippet.startsWith("module ")) {
        continue; // a module-info.java of the reader's own; checked below
      }
      StringBuilder body = new StringBuilder();
      for (String line : snippet.split("\n", -1)) {
        if (line.startsWith("import ")) {
          assertTrue(examples.contains(line), "ReadmeExamples.java lacks " + line);
        } else {
          body.append(line).append('\n');
        }
      }
      assertTrue(
          compiled.contains(tokens(body.toString())),
          "this README snippet is not in ReadmeExamples.java, so nothing compiles it:\n" + snippet);
    }
  }

  @Test
  void theModuleSnippetRequiresThisModule() throws IOException {
    assumeTrue(Files.exists(README), "not a checkout");
    List<String> modules = snippets().stream().filter(s -> s.startsWith("module ")).toList();
    assertEquals(1, modules.size());
    assertTrue(modules.get(0).contains("requires io.github.baljinnyamday.xyp;"), modules.get(0));
    if (XypClient.class.getModule().isNamed()) {
      assertEquals("io.github.baljinnyamday.xyp", XypClient.class.getModule().getName());
    }
  }
}
