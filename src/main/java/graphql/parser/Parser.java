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

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.List;

import static graphql.parser.SourceLocationHelper.mkSourceLocation;

@Internal
public class Parser {
    private final int MAX_QUERY_CHARACTERS = 1024 * 1024; // 1 MB, copied from https://github.com/graphql-java/graphql-java/blob/master/src/main/java/graphql/parser/ParserOptions.java#L24
    private final int MAX_RULE_DEPTH = 500; // Copied from https://github.com/graphql-java/graphql-java/blob/master/src/main/java/graphql/parser/ParserOptions.java#L54
    private final int MAX_QUERY_TOKENS = 15_000; // Copied from https://github.com/graphql-java/graphql-java/blob/master/src/main/java/graphql/parser/ParserOptions.java#L35

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

        SafeTokenReader safeReader = new SafeTokenReader(multiSourceReader, MAX_QUERY_CHARACTERS);
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
                if (depth > MAX_RULE_DEPTH) {
                    Token startToken = ctx.getStart();
                    SourceLocation sourceLocation = mkSourceLocation(multiSourceReader, startToken);
                    // Copied from 'ParseCancelled.tooDeep' error message. See https://github.com/graphql-java/graphql-java/blob/master/src/main/resources/i18n/Parsing.properties#L23
                    throw new InvalidSyntaxException(sourceLocation,
                            String.format("More than %s deep 'grammar' rules have been entered. To prevent Denial Of Service attacks, parsing has been cancelled.", MAX_RULE_DEPTH),
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
                if (count > MAX_QUERY_TOKENS) {
                    SourceLocation sourceLocation = mkSourceLocation(multiSourceReader, token);
                    // Copied from 'ParseCancelled.full' error message. See https://github.com/graphql-java/graphql-java/blob/master/src/main/resources/i18n/Parsing.properties#L22
                    throw new InvalidSyntaxException(sourceLocation,
                            String.format("More than %s 'grammar' tokens have been presented. To prevent Denial Of Service attacks, parsing has been cancelled.", MAX_QUERY_TOKENS),
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
