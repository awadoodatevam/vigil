package democontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InterfaceIT {

  private static final Path STATIC = Path.of("src/main/resources/static");
  private static final Path ROOT = Path.of("../..");
  private static final Path FONTSOURCE = ROOT.resolve("web/node_modules/@fontsource-variable");
  private static final Pattern TOKEN = Pattern.compile("(--vd-[a-z0-9-]+)\\s*:\\s*([^;]+);");
  private static final Pattern USE = Pattern.compile("var\\((--vd-[a-z0-9-]+)\\)");
  private static final Pattern COLOUR =
      Pattern.compile("#[0-9a-fA-F]{3,8}\\b|(?:rgba?|hsla?)\\([^)]*\\)");

  /**
   * The colour literals VD-R-04 allows outside the tokens (complete list of its Section 4 to 8
   * colours).
   */
  private static final Set<String> ALLOWED_COLOURS =
      Set.of(
          "#ffffff",
          "#5aa2ff",
          "#f5f9ff",
          "#24406f",
          "#1b3d73",
          "rgba(0, 0, 0, 0.06)",
          "rgba(11, 21, 38, 0.5)");

  @LocalServerPort int port;

  private String page() throws Exception {
    HttpResponse<String> page = LoginIT.send(port, "GET", "/", null, null, null);
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
    return page.body();
  }

  @Test
  @DisplayName("AR-T-21 the page is one static HTML file with inline script and no library")
  void onePage() throws Exception {
    try (Stream<Path> files = Files.walk(STATIC)) {
      assertThat(files.filter(Files::isRegularFile).map(p -> STATIC.relativize(p).toString()))
          .containsExactlyInAnyOrder(
              "index.html",
              "fonts/inter-latin-wght-normal.woff2",
              "fonts/jetbrains-mono-latin-wght-normal.woff2");
    }
    String page = page();
    assertThat(page).isEqualTo(Files.readString(STATIC.resolve("index.html")));
    assertThat(page).contains("<title>Demo control panel</title>");
    Matcher scripts = Pattern.compile("<script([^>]*)>").matcher(page);
    int count = 0;
    while (scripts.find()) {
      assertThat(scripts.group(1)).doesNotContain("src");
      count++;
    }
    assertThat(count).isEqualTo(1);
    assertThat(page).doesNotContain("<link").doesNotContain("import ").doesNotContain("@import");
    assertThat(page).doesNotContainPattern("https?://");
    assertThat(page).contains("Demo control panel — demo-only, not part of ");
    assertThat(page)
        .contains(">Username</label>")
        .contains(">Password</label>")
        .contains(">Sign in</button>");
  }

  @Test
  @DisplayName("DC-U-11 the page's Reset question and buttons, Cancel first")
  void resetDialog() throws Exception {
    String page = page();
    Matcher dialog =
        Pattern.compile("<dialog id=\"confirm\">(.*?)</dialog>", Pattern.DOTALL).matcher(page);
    assertThat(dialog.find()).isTrue();
    String content = dialog.group(1);
    assertThat(content).contains("<p>Reset now? This ends the current demo run.</p>");
    int cancel = content.indexOf(">Cancel</button>");
    int reset = content.indexOf(">Reset</button>");
    assertThat(cancel).isPositive().isLessThan(reset);
    assertThat(Pattern.compile("<button").matcher(content).results().count()).isEqualTo(2);
    assertThat(page).contains("dialog.showModal();\n          byId(\"confirm-cancel\").focus();");
  }

  @Test
  @DisplayName("SL-A4-12 the control panel is its own application with its own login and page")
  void ownApplication() throws Exception {
    String page = page();
    assertThat(page).contains("<title>Demo control panel</title>");
    HttpResponse<String> state = LoginIT.send(port, "GET", "/api/state", null, null, null);
    assertThat(state.statusCode()).isEqualTo(401);
    assertThat(state.body()).isEqualTo("{\"product\":\"Techfinity\"}");
    String pom = Files.readString(Path.of("pom.xml"));
    assertThat(pom)
        .contains("<artifactId>demo-control</artifactId>")
        .doesNotContain("<artifactId>vigil-app");
  }

  @Test
  @DisplayName("DC-S12-07 the link Open {product} opens the Vigil login in a new tab")
  void link() throws Exception {
    String page = page();
    assertThat(page)
        .contains("<a id=\"open\" target=\"_blank\" rel=\"noopener noreferrer\"></a>")
        .contains("open.textContent = \"Open \" + state.product;")
        .contains("open.href = state.vigil_url + \"/login\";");
  }

  private static String style(String page) {
    Matcher style = Pattern.compile("<style>(.*?)</style>", Pattern.DOTALL).matcher(page);
    assertThat(style.find()).isTrue();
    return style.group(1).replaceAll("(?s)/\\*.*?\\*/", "");
  }

  private static String rootBlock(String css) {
    Matcher root = Pattern.compile(":root\\s*\\{([^}]*)}").matcher(css);
    assertThat(root.find()).as(":root in the styles").isTrue();
    return root.group(1);
  }

  /** The custom properties of a {@code :root} block, values with their white space collapsed. */
  private static Map<String, String> tokens(String root) {
    Map<String, String> tokens = new LinkedHashMap<>();
    Matcher token = TOKEN.matcher(root);
    while (token.find()) {
      assertThat(tokens.put(token.group(1), token.group(2).trim().replaceAll("\\s+", " ")))
          .as("%s declared once", token.group(1))
          .isNull();
    }
    return tokens;
  }

  /** The declarations {property: value} of each {@code @font-face} rule, quotes removed. */
  private static List<Map<String, String>> fontFaces(String css) {
    List<Map<String, String>> faces = new ArrayList<>();
    Matcher face = Pattern.compile("@font-face\\s*\\{([^}]*)}").matcher(css);
    while (face.find()) {
      Map<String, String> declarations = new LinkedHashMap<>();
      for (String declaration : face.group(1).split(";")) {
        int colon = declaration.indexOf(':');
        if (colon > 0) {
          declarations.put(
              declaration.substring(0, colon).trim(),
              declaration
                  .substring(colon + 1)
                  .replaceAll("[\"']", "")
                  .replaceAll("\\s*,\\s*", ",")
                  .replaceAll("\\s+", " ")
                  .trim());
        }
      }
      faces.add(declarations);
    }
    return faces;
  }

  /** The Latin normal rule of a fontsource package's index.css. */
  private static Map<String, String> latinNormal(String packageName, String file) throws Exception {
    String css = Files.readString(FONTSOURCE.resolve(packageName).resolve("index.css"));
    Matcher rule =
        Pattern.compile("/\\* " + Pattern.quote(file) + " \\*/\\s*(@font-face\\s*\\{[^}]*})")
            .matcher(css);
    assertThat(rule.find()).as("%s in %s's index.css", file, packageName).isTrue();
    return fontFaces(rule.group(1)).getFirst();
  }

  @Test
  @DisplayName("VD-R-09 the panel serves its two fonts from its own origin")
  void fonts() throws Exception {
    HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    Map<String, String> files =
        Map.of(
            "inter-latin-wght-normal.woff2", "inter",
            "jetbrains-mono-latin-wght-normal.woff2", "jetbrains-mono");
    for (Map.Entry<String, String> file : files.entrySet()) {
      HttpResponse<byte[]> font =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/fonts/" + file.getKey()))
                  .header("Accept-Encoding", "gzip")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertThat(font.statusCode()).as(file.getKey()).isEqualTo(200);
      assertThat(font.headers().firstValue("Content-Type"))
          .as(file.getKey())
          .contains("font/woff2");
      assertThat(font.headers().firstValue("Content-Encoding")).as(file.getKey()).isEmpty();
      assertThat(font.body())
          .as(file.getKey())
          .isEqualTo(
              Files.readAllBytes(
                  FONTSOURCE.resolve(file.getValue()).resolve("files").resolve(file.getKey())));
    }
    List<Map<String, String>> faces = fontFaces(style(page()));
    assertThat(faces).hasSize(2);
    assertThat(faces)
        .extracting(face -> face.get("font-family"))
        .containsExactly("Inter Variable", "JetBrains Mono Variable");
    assertThat(faces)
        .extracting(face -> face.get("src"))
        .containsExactly(
            "url(/fonts/inter-latin-wght-normal.woff2) format(woff2-variations)",
            "url(/fonts/jetbrains-mono-latin-wght-normal.woff2) format(woff2-variations)");
    Map<String, String> inter = latinNormal("inter", "inter-latin-wght-normal");
    Map<String, String> mono = latinNormal("jetbrains-mono", "jetbrains-mono-latin-wght-normal");
    for (String property :
        List.of("font-family", "font-style", "font-display", "font-weight", "unicode-range")) {
      assertThat(faces.get(0).get(property))
          .as("Inter %s", property)
          .isEqualTo(inter.get(property));
      assertThat(faces.get(1).get(property)).as("Mono %s", property).isEqualTo(mono.get(property));
    }
  }

  @Test
  @DisplayName("VD-R-15 the panel's tokens are those of tokens.css")
  void tokens() throws Exception {
    String css = style(page());
    String root = rootBlock(css);
    Map<String, String> declared = tokens(root);
    Map<String, String> web =
        tokens(
            rootBlock(
                Files.readString(ROOT.resolve("web/src/app/tokens.css"))
                    .replaceAll("(?s)/\\*.*?\\*/", "")));
    assertThat(declared).isNotEmpty();
    for (Map.Entry<String, String> token : declared.entrySet()) {
      assertThat(web).as(token.getKey()).containsEntry(token.getKey(), token.getValue());
    }
    String outside = css.replace(root, "");
    Set<String> used = new LinkedHashSet<>();
    USE.matcher(outside).results().forEach(match -> used.add(match.group(1)));
    Set<String> needed = new LinkedHashSet<>(used);
    for (String name : used) {
      USE.matcher(declared.getOrDefault(name, ""))
          .results()
          .forEach(match -> needed.add(match.group(1)));
    }
    assertThat(declared.keySet())
        .as("the tokens the page uses")
        .containsExactlyInAnyOrderElementsOf(needed);
    List<String> colours = COLOUR.matcher(outside).results().map(m -> m.group()).toList();
    assertThat(colours).isNotEmpty();
    assertThat(ALLOWED_COLOURS).containsAll(colours);
  }

  @Test
  @DisplayName("VD-R-01 the panel's texts and controls are unchanged")
  void unchanged() throws Exception {
    String page = page();
    Matcher script = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL).matcher(page);
    assertThat(script.find()).isTrue();
    assertThat(
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(script.group(1).getBytes(StandardCharsets.UTF_8))))
        .as("the script of 4fd1c27")
        .isEqualTo("87a7f2bc90c79fe4840d315fae3f9901bde8f999e2a3abc4df95ba6f05763ad1");
    String markup =
        page.replaceAll("(?s)<style>.*?</style>", "").replaceAll("(?s)<script>.*?</script>", "");
    assertThat(
            Pattern.compile(">([^<]*)<")
                .matcher(markup)
                .results()
                .map(m -> m.group(1).strip())
                .filter(text -> !text.isEmpty()))
        .containsExactly(
            "Demo control panel",
            "Username",
            "Password",
            "Sign in",
            "Reset now? This ends the current demo run.",
            "Cancel",
            "Reset");
    assertThat(Pattern.compile("\\sid=\"([^\"]+)\"").matcher(markup).results().map(m -> m.group(1)))
        .containsExactly(
            "top",
            "login",
            "login-form",
            "username",
            "password",
            "login-error",
            "panel",
            "buttons",
            "faults",
            "status",
            "clock",
            "open",
            "confirm",
            "confirm-cancel",
            "confirm-reset");
    assertThat(
            Pattern.compile("<(?:button|input|label|a|form|dialog)\\b[^>]*>")
                .matcher(markup)
                .results()
                .map(m -> m.group()))
        .containsExactly(
            "<form id=\"login-form\">",
            "<label for=\"username\">",
            "<input id=\"username\" name=\"username\" autocomplete=\"username\" />",
            "<label for=\"password\">",
            "<input id=\"password\" name=\"password\" type=\"password\""
                + " autocomplete=\"current-password\" />",
            "<button type=\"submit\">",
            "<a id=\"open\" target=\"_blank\" rel=\"noopener noreferrer\">",
            "<dialog id=\"confirm\">",
            "<button type=\"button\" id=\"confirm-cancel\">",
            "<button type=\"button\" id=\"confirm-reset\">");
    // The elements in their order; the only new ones are the wrapping cards (class only, no id).
    assertThat(
            Pattern.compile("<([a-z]+)\\b([^>]*)>")
                .matcher(markup.substring(markup.indexOf("<body>")))
                .results()
                .filter(m -> !(m.group(1).equals("div") && !m.group(2).contains("id=")))
                .map(m -> m.group(1)))
        .containsExactly(
            "body", "p", "main", "section", "form", "label", "input", "label", "input", "button",
            "p", "section", "ul", "div", "p", "p", "p", "a", "dialog", "p", "button", "button");
  }
}
