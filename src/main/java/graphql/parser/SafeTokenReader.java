package graphql.parser;

import graphql.Internal;

import java.io.IOException;
import java.io.Reader;
import java.nio.CharBuffer;

/**
 * This reader will only emit a maximum number of characters from it.  This is used to protect us from evil input.
 * <p>
 * If a graphql system does not have some max HTTP input limit, then this will help protect the system.  This is a limit
 * of last resort.  Ideally the http input should be limited, but if its not, we have this.
 */
@Internal
public class SafeTokenReader extends Reader {

    private final Reader delegate;
    private final int maxCharacters;
    private int count;

    public SafeTokenReader(Reader delegate, int maxCharacters) {
        this.delegate = delegate;
        this.maxCharacters = maxCharacters;
        count = 0;
    }

    private int checkHowMany(int read, int howMany) {
        if (read != -1) {
            count += howMany;
            if (count > maxCharacters) {
                // Copied from 'ParseCancelled.tooManyChars' error message. See https://github.com/graphql-java/graphql-java/blob/master/src/main/resources/i18n/Parsing.properties#L24
                throw new InvalidSyntaxException(null,
                        String.format("More than %d characters have been presented. To prevent Denial Of Service attacks, parsing has been cancelled.", maxCharacters),
                        null, null, null);
            }
        }
        return read;
    }

    @Override
    public int read(char[] buff, int off, int len) throws IOException {
        int howMany = delegate.read(buff, off, len);
        return checkHowMany(howMany, howMany);
    }

    @Override
    public int read() throws IOException {
        int ch = delegate.read();
        return checkHowMany(ch, 1);
    }

    @Override
    public int read(CharBuffer target) throws IOException {
        int howMany = delegate.read(target);
        return checkHowMany(howMany, howMany);
    }

    @Override
    public int read(char[] buff) throws IOException {
        int howMany = delegate.read(buff);
        return checkHowMany(howMany, howMany);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public long skip(long n) throws IOException {
        return delegate.skip(n);
    }

    @Override
    public boolean ready() throws IOException {
        return delegate.ready();
    }

    @Override
    public boolean markSupported() {
        return delegate.markSupported();
    }

    @Override
    public void mark(int readAheadLimit) throws IOException {
        delegate.mark(readAheadLimit);
    }

    @Override
    public void reset() throws IOException {
        delegate.reset();
    }
}
