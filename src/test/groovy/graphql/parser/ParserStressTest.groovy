package graphql.parser

import graphql.TestUtil
import spock.lang.Specification

/**
 * Tests related to how the Parser can be stress tested
 */
class ParserStressTest extends Specification {

    public static final String MAX_TOKENS_ERROR_MESSAGE = "More than 15000 'grammar' tokens have been presented. To prevent Denial Of Service attacks, parsing has been cancelled."
    public static final String MAX_CHARACTERS_ERROR_MESSAGE = "More than 1048576 characters have been presented. To prevent Denial Of Service attacks, parsing has been cancelled."
    public static final String MAX_DEPTH_ERROR_MESSAGE = "More than 500 deep 'grammar' rules have been entered. To prevent Denial Of Service attacks, parsing has been cancelled."

    def "a billion laughs attack will be prevented by default"() {
        def lol = "@lol" * 10000 // two tokens = 20000+ tokens
        def text = "query { f $lol }"
        when:
        def parser = new Parser()
        parser.parseDocument(text)

        then:
        def e = thrown(InvalidSyntaxException)
        println e.getMessage()
        e.getMessage().contains(MAX_TOKENS_ERROR_MESSAGE)

        when: "integration test to prove it cancels by default"

        def sdl = """type Query { f : ID} """
        def graphQL = TestUtil.graphQL(sdl).build()
        def er = graphQL.execute(text)
        then:
        er.errors.size() == 1
        er.errors[0].message.contains(MAX_TOKENS_ERROR_MESSAGE)
    }

    def "a large whitespace laughs attack will be prevented by default"() {
        def spaces = " " * 1_200_000
        def text = "query { f $spaces }"
        when:
        def parser = new Parser()
        parser.parseDocument(text)

        then:
        def e = thrown(InvalidSyntaxException)
        println e.getMessage()
        e.getMessage().contains(MAX_CHARACTERS_ERROR_MESSAGE)

        when: "integration test to prove it cancels by default"

        def sdl = """type Query { f : ID} """
        def graphQL = TestUtil.graphQL(sdl).build()
        def er = graphQL.execute(text)
        then:
        er.errors.size() == 1
        er.errors[0].message.contains(MAX_CHARACTERS_ERROR_MESSAGE)
    }


    def "deep query stack overflows are prevented by limiting the depth of rules"() {
        String text = mkDeepQuery(10000)

        when:
        def parser = new Parser()
        parser.parseDocument(text)

        then:
        def e = thrown(InvalidSyntaxException)
        println e.getMessage()
        e.getMessage().contains(MAX_DEPTH_ERROR_MESSAGE)
    }

    def "wide queries are prevented by max token counts"() {
        String text = mkWideQuery(10000)

        when:
        def parser = new Parser()
        parser.parseDocument(text)

        then:
        def e = thrown(InvalidSyntaxException)
        println e.getMessage()
        e.getMessage().contains(MAX_TOKENS_ERROR_MESSAGE)
    }

    def "large single token attack parse can be prevented"() {
        String text = "q" * 10_000_000
        text = "query " + text + " {f}"

        when:
        def parser = new Parser()
        parser.parseDocument(text)

        then:
        def e = thrown(InvalidSyntaxException)
        println e.getMessage()
        e.getMessage().contains(MAX_CHARACTERS_ERROR_MESSAGE)
    }

    def "inside limits single token attack parse will be accepted"() {
        String text = "q" * 900_000
        text = "query " + text + " {f}"

        when:
        def parser = new Parser()
        def document = parser.parseDocument(text)

        then:
        document != null // its parsed - its invalid of course but parsed
    }

    String mkDeepQuery(int howMany) {
        def field = 'f(a:"")'
        StringBuilder sb = new StringBuilder("query q{")
        for (int i = 0; i < howMany; i++) {
            sb.append(field)
            if (i < howMany - 1) {
                sb.append("{")
            }
        }
        for (int i = 0; i < howMany - 1; i++) {
            sb.append("}")
        }
        sb.append("}")
        return sb.toString()
    }

    String mkWideQuery(int howMany) {
        StringBuilder sb = new StringBuilder("query q{f(")
        for (int i = 0; i < howMany; i++) {
            sb.append('a:1,')
        }
        sb.append(")}")
        return sb.toString()
    }
}
