package democontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
          .containsExactly("index.html");
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
}
