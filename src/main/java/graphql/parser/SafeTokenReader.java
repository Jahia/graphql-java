package graphql.parser;

import graphql.Internal;

import java.io.IOException;
import java.io.Reader;

/**
 * Inspired by <a href="https://github.com/graphql-java/graphql-java/blob/master/src/main/java/graphql/parser/SafeTokenReader.java">SafeTokenReader</a>
 */
@Internal
public class SafeTokenReader extends Reader {
    private final Reader delegate;
    private final int maxChars;
    private int charsRead = 0;

    public SafeTokenReader(Reader delegate, int maxChars) {
        this.delegate = delegate;
        this.maxChars = maxChars;
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        if (charsRead >= maxChars) {
            // Copied from 'ParseCancelled.tooManyChars' error message. See https://github.com/graphql-java/graphql-java/blob/master/src/main/resources/i18n/Parsing.properties#L24
            throw new InvalidSyntaxException(null,
                    String.format("More than %d characters have been presented. To prevent Denial Of Service attacks, parsing has been cancelled.", maxChars),
                    null, null, null);
        }
        int toRead = Math.min(len, maxChars - charsRead);
        int n = delegate.read(cbuf, off, toRead);
        if (n > 0) {
            charsRead += n;
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
