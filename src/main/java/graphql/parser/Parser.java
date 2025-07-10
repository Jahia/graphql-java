package graphql.parser;

import graphql.Internal;
import graphql.language.Document;
import graphql.language.SourceLocation;
import graphql.parser.antlr.GraphqlBaseListener;
import graphql.parser.antlr.GraphqlLexer;
import graphql.parser.antlr.GraphqlParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.List;

import static graphql.parser.SourceLocationHelper.mkSourceLocation;

@Internal
public class Parser {
    private static final Logger log = LoggerFactory.getLogger(Parser.class);
    private final int MAX_QUERY_CHARACTERS = 1024 * 1024; // 1 MB, copied from https://github.com/graphql-java/graphql-java/blob/master/src/main/java/graphql/parser/ParserOptions.java#L24
    private final int MAX_RULE_DEPTH = 500; // Copied from https://github.com/graphql-java/graphql-java/blob/master/src/main/java/graphql/parser/ParserOptions.java#L54
    private final int MAX_QUERY_TOKENS = 15_000; // Copied from https://github.com/graphql-java/graphql-java/blob/master/src/main/java/graphql/parser/ParserOptions.java#L35
    private final int maxQueryCharacters;
    private final int maxRuleDepth;
    private final int maxQueryTokens;

    public Parser() {
        this.maxQueryCharacters = getSystemEnvOrDefault("GRAPHQL_MAX_QUERY_CHARACTERS", MAX_QUERY_CHARACTERS);
        this.maxRuleDepth = getSystemEnvOrDefault("GRAPHQL_MAX_RULE_DEPTH", MAX_RULE_DEPTH);
        this.maxQueryTokens = getSystemEnvOrDefault("GRAPHQL_MAX_QUERY_TOKENS", MAX_QUERY_TOKENS);
        log.debug("Parser settings: maxQueryCharacters={}, maxRuleDepth={}, maxQueryTokens={}", maxQueryCharacters, maxRuleDepth, maxQueryTokens);
    }

    private int getSystemEnvOrDefault(String systemEnv, int defaultValue) {
        String envValue = System.getenv(systemEnv);
        int value = defaultValue;
        if (envValue != null) {
            try {
                value = Integer.parseInt(envValue);
            } catch (NumberFormatException ignored) {
                // fallback to default
                log.warn("Invalid number for system env {}: {}", systemEnv, envValue);
            }
        }
        return value;
    }

    public Document parseDocument(String input) throws InvalidSyntaxException {
        return parseDocument(input, null);
    }

    public Document parseDocument(String input, String sourceName) throws InvalidSyntaxException {
        MultiSourceReader multiSourceReader = MultiSourceReader.newMultiSourceReader()
                .string(input, sourceName)
                .trackData(true)
                .build();
        return parseDocument(multiSourceReader);
    }

    public Document parseDocument(Reader reader) throws InvalidSyntaxException {
        MultiSourceReader multiSourceReader;
        if (reader instanceof MultiSourceReader) {
            multiSourceReader = (MultiSourceReader) reader;
        } else {
            multiSourceReader = MultiSourceReader.newMultiSourceReader()
                    .reader(reader, null).build();
        }

        SafeTokenReader safeReader = new SafeTokenReader(multiSourceReader, maxQueryCharacters);
        CodePointCharStream charStream;
        try {
            charStream = CharStreams.fromReader(safeReader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        GraphqlLexer lexer = new GraphqlLexer(charStream);

        CommonTokenStream tokens = new CommonTokenStream(lexer);

        GraphqlParser parser = new GraphqlParser(tokens);
        parser.removeErrorListeners();
        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);

        ExtendedBailStrategy bailStrategy = new ExtendedBailStrategy(multiSourceReader);
        parser.setErrorHandler(bailStrategy);

        ParseTreeListener listener = new GraphqlBaseListener() {
            int count = 0;
            int depth = 0;


            @Override
            public void enterEveryRule(ParserRuleContext ctx) {
                depth++;
                if (depth > maxRuleDepth) {
                    Token startToken = ctx.getStart();
                    SourceLocation sourceLocation = mkSourceLocation(multiSourceReader, startToken);
                    // Copied from 'ParseCancelled.tooDeep' error message. See https://github.com/graphql-java/graphql-java/blob/master/src/main/resources/i18n/Parsing.properties#L23
                    throw new InvalidSyntaxException(sourceLocation,
                            String.format("More than %s deep 'grammar' rules have been entered. To prevent Denial Of Service attacks, parsing has been cancelled.", maxRuleDepth),
                            null, startToken.getText(), null);
                }
            }

            @Override
            public void exitEveryRule(ParserRuleContext ctx) {
                depth--;
            }

            @Override
            public void visitTerminal(TerminalNode node) {

                final Token token = node.getSymbol();

                count++;
                if (count > maxQueryTokens) {
                    SourceLocation sourceLocation = mkSourceLocation(multiSourceReader, token);
                    // Copied from 'ParseCancelled.full' error message. See https://github.com/graphql-java/graphql-java/blob/master/src/main/resources/i18n/Parsing.properties#L22
                    throw new InvalidSyntaxException(sourceLocation,
                            String.format("More than %s 'grammar' tokens have been presented. To prevent Denial Of Service attacks, parsing has been cancelled.", maxQueryTokens),
                            null, token.getText(), null);
                }
            }
        };
        parser.addParseListener(listener);
        GraphqlAntlrToLanguage toLanguage = new GraphqlAntlrToLanguage(tokens, multiSourceReader);
        GraphqlParser.DocumentContext documentContext = parser.document();

        Document doc = toLanguage.createDocument(documentContext);

        Token stop = documentContext.getStop();
        List<Token> allTokens = tokens.getTokens();
        if (stop != null && allTokens != null && !allTokens.isEmpty()) {
            Token last = allTokens.get(allTokens.size() - 1);
            //
            // do we have more tokens in the stream than we consumed in the parse?
            // if yes then its invalid.  We make sure its the same channel
            boolean notEOF = last.getType() != Token.EOF;
            boolean lastGreaterThanDocument = last.getTokenIndex() > stop.getTokenIndex();
            boolean sameChannel = last.getChannel() == stop.getChannel();
            if (notEOF && lastGreaterThanDocument && sameChannel) {
                throw bailStrategy.mkMoreTokensException(last);
            }
        }
        return doc;
    }

}
