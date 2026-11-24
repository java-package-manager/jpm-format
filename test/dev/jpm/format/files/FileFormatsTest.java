package dev.jpm.format.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * jpm-format's own formatters for {@code .properties}, JSON and XML. Where they follow IntelliJ IDEA's default code
 * style, the expected output is what IntelliJ's {@code format.sh -allowDefaults} makes of the input.
 */
class FileFormatsTest {
    private static void properties(String in, String out) {
        assertEquals(out, PropertiesFormat.format(in, 2));
        assertEquals(out, PropertiesFormat.format(out, 2), "idempotent");
    }

    private static void json(String in, String out) {
        assertEquals(out, JsonFormat.format(in, 2, 2));
        assertEquals(out, JsonFormat.format(out, 2, 2), "idempotent");
    }

    private static void xml(String in, String out) {
        assertEquals(out, XmlFormat.format(in, 4, 2));
        assertEquals(out, XmlFormat.format(out, 4, 2), "idempotent");
    }

    @Test
    void propertiesLikeIntelliJ() {
        properties("""
                # comment
                  ! bang comment
                key=value
                key2 = value with spaces  \s
                  indented.key   =   v
                colon2 : v
                a    b c
                key
                  x\t=\ty
                escaped\\ key=v
                multi=line one \\
                      line two \\
                  line three
                key.with.equals=a=b
                """, """
                # comment
                ! bang comment
                key=value
                key2=value with spaces  \s
                indented.key=v
                colon2:v
                a b c
                key
                x=y
                escaped\\ key=v
                multi=line one \\
                      line two \\
                  line three
                key.with.equals=a=b
                """);
    }

    @Test
    void propertiesKeepBlankLinesUpToTheMaximum() {
        properties("\n\na=1\n\n\n\n# group\nb=2\n   \n\n", "a=1\n\n\n# group\nb=2\n");
        properties("a=1", "a=1");
        assertEquals("a=1\n\nb=2\n", PropertiesFormat.format("a=1\n\n\nb=2\n", 1));
    }

    @Test
    void jsonKeepsItsLineBreaks() {
        json("""
                {"a":1,"b":[1,2,{"c":null}],
                "d" : {  },
                    "e": [],
                "f":"x"  ,"g": { "h": true}
                }
                """, """
                {"a": 1, "b": [1, 2, {"c": null}],
                  "d": {},
                  "e": [],
                  "f": "x", "g": { "h": true}
                }
                """);
    }

    @Test
    void jsonIndentsFromTheLineABracketOpensOn() {
        json("""
                [{
                "a": [
                1,
                    2
                ],
                "b": {"c":
                {"d": 1}}
                }]
                """, """
                [{
                  "a": [
                    1,
                    2
                  ],
                  "b": {"c":
                    {"d": 1}}
                }]
                """);
    }

    @Test
    void jsoncCommentsStayWhereTheyAre() {
        json("""
                // leading comment
                {
                    "a": 1, // trailing
                  /* block */ "b": [1,
                      2],



                  "s": "with \\"quote\\" and // not comment"
                }
                """, """
                // leading comment
                {
                  "a": 1, // trailing
                  /* block */ "b": [1,
                    2],


                  "s": "with \\"quote\\" and // not comment"
                }
                """);
    }

    @Test
    void jsonThatDoesNotLexIsLeftAlone() {
        for (String bad : new String[]{"{\"a\": [1, 2}\n", "{\"a\": \"open\n", "/* open\n"}) {
            assertEquals(bad, JsonFormat.format(bad, 2, 2));
        }
    }

    @Test
    void xmlLikeIntelliJ() {
        xml("""
                <?xml version="1.0"?>
                <project>
                <a   x="1"  y = "2" >text</a>
                  <b>
                <c/>
                      <d attr="1"
                other="2">
                </d>
                <!-- comment -->



                <e><f>v</f></e>
                  </b>
                </project>
                """, """
                <?xml version="1.0"?>
                <project>
                    <a x="1" y="2">text</a>
                    <b>
                        <c/>
                        <d attr="1"
                           other="2">
                        </d>
                        <!-- comment -->


                        <e>
                            <f>v</f>
                        </e>
                    </b>
                </project>
                """);
    }

    @Test
    void xmlNeverChangesText() {
        xml("""
                <root>
                <p>Some <b>bold</b> text
                   continues here.</p>
                <script><![CDATA[
                  if (a < b) { x(); }
                ]]></script>
                <pre xml:space="preserve">  keep   this  </pre>
                <empty>  </empty>
                <url>https://example.com/a/very/long/url/that/IntelliJ/would/wrap/at/the/right/margin/but/that/changes/the/value</url>
                </root>
                """, """
                <root>
                    <p>Some <b>bold</b> text
                   continues here.</p>
                    <script><![CDATA[
                  if (a < b) { x(); }
                ]]></script>
                    <pre xml:space="preserve">  keep   this  </pre>
                    <empty>  </empty>
                    <url>https://example.com/a/very/long/url/that/IntelliJ/would/wrap/at/the/right/margin/but/that/changes/the/value</url>
                </root>
                """);
    }

    @Test
    void xmlCommentsMoveWithTheirElement() {
        xml("""
                <a>
                  <b> <!-- trailing -->
                    <c/><!-- right after -->
                  </b>
                  <!-- one
                  \t   two
                       three -->
                </a>
                """, """
                <a>
                    <b> <!-- trailing -->
                        <c/><!-- right after -->
                    </b>
                    <!-- one
                         two
                         three -->
                </a>
                """);
    }

    @Test
    void xmlThatDoesNotParseIsLeftAlone() {
        for (String bad : new String[]{"<a><b></a>\n", "<a>\n", "text <a/>\n", "<a x=\"1></a>\n"}) {
            assertEquals(bad, XmlFormat.format(bad, 4, 2));
        }
    }
}
