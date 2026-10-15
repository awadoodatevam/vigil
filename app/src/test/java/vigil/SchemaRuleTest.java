package vigil;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.relational.core.mapping.Table;
import vigil.testsupport.TableClasses;

class SchemaRuleTest {

  private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
  private static final Path MAIN_JAVA = Path.of("src/main/java/vigil");

  /** Module package to its schema (AR Section 7.1); {@code api} owns none. */
  private static final Map<String, String> MODULE_SCHEMAS = moduleSchemas();

  /** A schema-qualified name: one of the 23 schemas followed by a dot and a name. */
  private static final Pattern QUALIFIED =
      Pattern.compile(
          "(?<![A-Za-z0-9_])(" + String.join("|", TableClasses.SCHEMAS) + ")\\.(?=[\"A-Za-z_])",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern SNAKE_CASE = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)*");

  @Test
  @DisplayName("AR-Q-02 each migration folder names only its own schema")
  void migrationsNameOnlyTheirSchema() throws IOException {
    List<Path> files = files(MIGRATIONS, ".sql");
    assertThat(files).isNotEmpty();
    for (Path file : files) {
      String schema = MIGRATIONS.relativize(file).getName(0).toString();
      assertThat(qualifiedSchemas(Files.readString(file, StandardCharsets.UTF_8)))
          .as("schemas named in %s", file)
          .isSubsetOf(schema);
    }
  }

  @Test
  @DisplayName("AR-R-07 module source names only the module's own schema")
  void moduleSourceNamesOnlyItsSchema() throws IOException {
    for (Map.Entry<String, String> module : MODULE_SCHEMAS.entrySet()) {
      Path folder = MAIN_JAVA.resolve(module.getKey());
      assertThat(folder).as("module folder").isDirectory();
      for (Path file : files(folder, ".java")) {
        List<String> named = new ArrayList<>();
        for (String literal : literals(Files.readString(file, StandardCharsets.UTF_8))) {
          named.addAll(qualifiedSchemas(literal));
        }
        if (module.getValue() == null) {
          assertThat(named).as("schemas named in %s", file).isEmpty();
        } else {
          assertThat(named).as("schemas named in %s", file).isSubsetOf(module.getValue());
        }
      }
    }
    for (Map.Entry<JavaClass, Table> mapped : tableMappings().entrySet()) {
      String module = mapped.getKey().getPackageName().split("\\.")[1];
      assertThat(mapped.getValue().schema())
          .as("@Table schema of %s", mapped.getKey().getName())
          .isNotNull()
          .isEqualTo(MODULE_SCHEMAS.get(module));
    }
  }

  @Test
  @DisplayName("AR-P-02 every @Table names a table in snake case")
  void tableNamesAreSnakeCase() {
    for (Map.Entry<JavaClass, Table> mapped : tableMappings().entrySet()) {
      assertThat(mapped.getValue().name())
          .as("@Table name of %s", mapped.getKey().getName())
          .matches(SNAKE_CASE);
    }
  }

  @Test
  @DisplayName("AR-R-07 the literal scanner finds schema names in strings and text blocks")
  void literalScannerFindsSchemaNames() {
    String source =
        "class X { // \"audit.x\" in a comment\n"
            + " String a = \"SELECT * FROM incidents.incident\";\n"
            + " char c = '\"';\n"
            + " String b = \"\"\"\n   UPDATE intake.alert\n   \"\"\";\n"
            + " /* \"routing.rule\" */ String d = \"say \\\"engine.run\\\"\"; }";
    List<String> named = new ArrayList<>();
    for (String literal : literals(source)) {
      named.addAll(qualifiedSchemas(literal));
    }
    assertThat(named).containsExactly("incidents", "intake", "engine");
  }

  private static Map<String, String> moduleSchemas() {
    Map<String, String> schemas = new LinkedHashMap<>();
    for (String schema : TableClasses.SCHEMAS) {
      if (!schema.equals("platform")) {
        schemas.put(schema.replace("_", ""), schema);
      }
    }
    schemas.put("api", null);
    return schemas;
  }

  private static Map<JavaClass, Table> tableMappings() {
    Map<JavaClass, Table> mappings = new LinkedHashMap<>();
    for (JavaClass type :
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("vigil")) {
      if (type.isAnnotatedWith(Table.class)) {
        mappings.put(type, AnnotatedElementUtils.findMergedAnnotation(type.reflect(), Table.class));
      }
    }
    return mappings;
  }

  private static List<String> qualifiedSchemas(String text) {
    List<String> schemas = new ArrayList<>();
    Matcher matcher = QUALIFIED.matcher(text);
    while (matcher.find()) {
      schemas.add(matcher.group(1).toLowerCase(Locale.ROOT));
    }
    return schemas;
  }

  private static List<Path> files(Path folder, String suffix) throws IOException {
    try (Stream<Path> paths = Files.walk(folder)) {
      return paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(suffix)).toList();
    }
  }

  /** The string literals and text blocks of Java source, comments and char literals skipped. */
  static List<String> literals(String source) {
    List<String> literals = new ArrayList<>();
    int i = 0;
    while (i < source.length()) {
      if (source.startsWith("//", i)) {
        int end = source.indexOf('\n', i);
        i = end < 0 ? source.length() : end + 1;
      } else if (source.startsWith("/*", i)) {
        int end = source.indexOf("*/", i + 2);
        i = end < 0 ? source.length() : end + 2;
      } else if (source.startsWith("\"\"\"", i)) {
        int end = closing(source, i + 3, "\"\"\"");
        literals.add(source.substring(i + 3, end));
        i = end + 3;
      } else if (source.charAt(i) == '"') {
        int end = closing(source, i + 1, "\"");
        literals.add(source.substring(i + 1, end));
        i = end + 1;
      } else if (source.charAt(i) == '\'') {
        i = closing(source, i + 1, "'") + 1;
      } else {
        i++;
      }
    }
    return literals;
  }

  /** The index of the first unescaped {@code delimiter} at or after {@code from}. */
  private static int closing(String source, int from, String delimiter) {
    int i = from;
    while (i < source.length()) {
      if (source.charAt(i) == '\\') {
        i += 2;
      } else if (source.startsWith(delimiter, i)) {
        return i;
      } else {
        i++;
      }
    }
    return source.length();
  }
}
