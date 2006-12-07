/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.csv;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;


/**
 * Parses cvs files according to the specified configuration.
 *
 * Because CSV appears in many different dialects the parser supports many
 * configuration settings. One of the most popular CSV format as supported
 * by the M$ corporation (excel-spreadsheets) are supported out-of-the-box
 * by the corresponding strategy setter (see {@link #setExcelStrategy()}).
 * 
 * <p>Parsing of a csv-string having ';' as separator:</p>
 * <pre>
 *  String[][] data = 
 *      (new CSVParser(new StringReader("a;b\nc;d"),';')).getAllValues();
 * </pre>
 * 
 * <p>The API allows chained method calls, if you like this coding style:</p>
 * <pre>
 *  String[][] data = (new CSVParser(new StringReader("a;b\nc;d"),';'))
 *      .setExcelStrategy().setIgnoreEmptyLines(true).getAllValues();
 * </pre>
 * 
 * <p>
 * Internal parser state is completely covered by the strategy
 * and the reader-state.</p>
 * 
 * <p>see <a href="package-summary.html">package documentation</a> 
 * for more details</p>
 */
public class CSVParser {

  /** length of the initial token (content-)buffer */
  private static final int INITIAL_TOKEN_LENGTH = 50;
  
  // the token types
  /** Token has no valid content, i.e. is in its initilized state. */
  protected static final int TT_INVALID = -1;
  /** Token with content, at beginning or in the middle of a line. */
  protected static final int TT_TOKEN = 0;
  /** Token (which can have content) when end of file is reached. */
  protected static final int TT_EOF = 1;
  /** Token with content when end of a line is reached. */
  protected static final int TT_EORECORD = 2;

  /** Immutable empty String array. */
  private static final String[] EMPTY_STRING_ARRAY = new String[0];
   
  // the input stream
  private final ExtendedBufferedReader in;

  // TODO: this can be made final if setStrategy is removed
  private CSVStrategy strategy;
  
  // the following objects are shared to reduce garbage 
  /** A record buffer for getLine(). Grows as necessary and is reused. */
  private final ArrayList record = new ArrayList();
  private final Token reusableToken = new Token();
  private final CharBuffer wsBuf = new CharBuffer();
  private final CharBuffer code = new CharBuffer(4);

  
  /**
   * Token is an internal token representation.
   * 
   * It is used as contract between the lexer and the parser. 
   */
  class Token {
    /** Token type, see TT_xxx constants. */
    int type = TT_INVALID;
    /** The content buffer. */
    CharBuffer content = new CharBuffer(INITIAL_TOKEN_LENGTH);
    /** Token ready flag: indicates a valid token with content (ready for the parser). */
    boolean isReady;
    
    Token reset() {
        content.clear();
        type = TT_INVALID;
        isReady = false;
        return this;
    }
  }
  
  // ======================================================
  //  the constructor
  // ======================================================
  
  /**
   * Default strategy for the parser follows the default CSV Strategy.
   * 
   * @param input an InputStream containing "csv-formatted" stream
   * @see #setStrategy()
   * @deprecated
   */
  public CSVParser(InputStream input) {
    this(new InputStreamReader(input));
  }
  
  /**
   * CSV parser using the default {@link CSVStrategy}.
   * 
   * @param input a Reader containing "csv-formatted" input
   * @see #setStrategy()
   */
  public CSVParser(Reader input) {
    // note: must match default-CSV-strategy !!
    this(input, ',');
  }
  
  /**
   * Customized value delimiter parser.
   * 
   * The parser follows the default CSV strategy as defined in 
   * {@link #setStrategy()} except for the delimiter setting.
   * 
   * @param input a Reader based on "csv-formatted" input
   * @param delimiter a Char used for value separation
   * @deprecated
   */
  public CSVParser(Reader input, char delimiter) {
    this(input, delimiter, '"', (char) 0);
  }
  
  /**
   * Customized csv parser.
   * 
   * The parser parses according to the given CSV dialect settings.
   * Leading whitespaces are truncated, unicode escapes are
   * not interpreted and empty lines are ignored.
   * 
   * @param input a Reader based on "csv-formatted" input
   * @param delimiter a Char used for value separation
   * @param encapsulator a Char used as value encapsulation marker
   * @param commentStart a Char used for comment identification
   * @deprecated
   */
  public CSVParser(Reader input, char delimiter, char encapsulator, char commentStart) {
    this(input, new CSVStrategy(delimiter, encapsulator, commentStart));
  }

  /**
   * Customized CSV parser using the given {@link CSVStrategy}
   *
   * @param input a Reader containing "csv-formatted" input
   * @param strategy the CSVStrategy used for CSV parsing
   */
  public CSVParser(Reader input, CSVStrategy strategy) {
    this.in = new ExtendedBufferedReader(input);
    this.strategy = strategy;
  }
  
  // ======================================================
  //  the parser
  // ======================================================
  
  /**
   * Parses the CSV according to the given strategy
   * and returns the content as an array of records
   * (whereas records are arrays of single values).
   * <p>
   * The returned content starts at the current parse-position in
   * the stream.
   * 
   * @return matrix of records x values ('null' when end of file)
   * @throws IOException on parse error or input read-failure
   */
  public String[][] getAllValues() throws IOException {
    ArrayList records = new ArrayList();
    String[] values;
    String[][] ret = null;
    while ((values = getLine()) != null)  {
      records.add(values);
    }
    if (records.size() > 0) {
      ret = new String[records.size()][];
      records.toArray(ret);
    }
    return ret;
  }
  
  /**
   * Parses the CSV according to the given strategy
   * and returns the next csv-value as string.
   * 
   * @return next value in the input stream ('null' when end of file)
   * @throws IOException on parse error or input read-failure
   */
  public String nextValue() throws IOException {
    Token tkn = nextToken();
    String ret = null;
    switch (tkn.type) {
      case TT_TOKEN:
      case TT_EORECORD: 
        ret = tkn.content.toString();
        break;
      case TT_EOF:
        ret = null;
        break;
      case TT_INVALID:
      default:
        // error no token available (or error)
        throw new IOException(
          "(line " + getLineNumber() 
          + ") invalid parse sequence");
        // unreachable: break;
    }
    return ret;
  }
  
  /**
   * Parses from the current point in the stream til
   * the end of the current line.
   * 
   * @return array of values til end of line 
   *        ('null' when end of file has been reached)
   * @throws IOException on parse error or input read-failure
   */
  public String[] getLine() throws IOException {
    String[] ret = EMPTY_STRING_ARRAY;
    record.clear();
    while (true) {
        reusableToken.reset();
        nextToken(reusableToken);
        switch (reusableToken.type) {
            case TT_TOKEN:
                record.add(reusableToken.content.toString());
                break;
            case TT_EORECORD:
                record.add(reusableToken.content.toString());
                break;
            case TT_EOF:
                if (reusableToken.isReady) {
                    record.add(reusableToken.content.toString());
                } else {
                    ret = null;
                }
                break;
            case TT_INVALID:
            default:
                // error: throw IOException
                throw new IOException("(line " + getLineNumber() + ") invalid parse sequence");
            // unreachable: break;
        }
        if (reusableToken.type != TT_TOKEN) break;
    }
    if (!record.isEmpty()) {
      ret = (String[]) record.toArray(new String[record.size()]);
    }
    return ret;
  }
  
  /**
   * Returns the current line number in the input stream.
   * 
   * ATTENTION: in case your csv has multiline-values the returned
   *            number does not correspond to the record-number
   * 
   * @return  current line number
   */
  public int getLineNumber() {
    return in.getLineNumber();  
  }
  
  // ======================================================
  //  the lexer(s)
  // ======================================================
 
  /**
   * Convenience method for <code>nextToken(null)</code>.
   */
  protected Token nextToken() throws IOException {
      return nextToken(new Token());
  }
  
 /**
   * Returns the next token.
   * 
   * A token corresponds to a term, a record change or an
   * end-of-file indicator.
   * 
   * @param tkn an existing Token object to reuse. The caller is responsible to initialize the
   * Token.
   * @return the next token found
   * @throws IOException on stream access error
   */
  protected Token nextToken(Token tkn) throws IOException {
    wsBuf.clear(); // resuse
    
    // get the last read char (required for empty line detection)
    int lastChar = in.readAgain();
    
    //  read the next char and set eol
    /* note: unfourtunately isEndOfLine may consumes a character silently.
     *       this has no effect outside of the method. so a simple workaround
     *       is to call 'readAgain' on the stream...
     *       uh: might using objects instead of base-types (jdk1.5 autoboxing!)
     */
    int c = in.read();
    boolean eol = isEndOfLine(c);
    c = in.readAgain();
     
    //  empty line detection: eol AND (last char was EOL or beginning)
    while (strategy.getIgnoreEmptyLines() && eol 
      && (lastChar == '\n' 
      || lastChar == ExtendedBufferedReader.UNDEFINED) 
      && !isEndOfFile(lastChar)) {
      // go on char ahead ...
      lastChar = c;
      c = in.read();
      eol = isEndOfLine(c);
      c = in.readAgain();
      // reached end of file without any content (empty line at the end)
      if (isEndOfFile(c)) {
        tkn.type = TT_EOF;
        return tkn;
      }
    }

    // did we reached eof during the last iteration already ? TT_EOF
    if (isEndOfFile(lastChar) || (lastChar != strategy.getDelimiter() && isEndOfFile(c))) {
      tkn.type = TT_EOF;
      return tkn;
    } 
    
    //  important: make sure a new char gets consumed in each iteration
    while (!tkn.isReady) {
      // ignore whitespaces at beginning of a token
      while (isWhitespace(c) && !eol) {
        wsBuf.append((char) c);
        c = in.read();
        eol = isEndOfLine(c);
      }
      // ok, start of token reached: comment, encapsulated, or token
      if (!strategy.isCommentingDisabled() && c == strategy.getCommentStart()) {
        // ignore everything till end of line and continue (incr linecount)
        in.readLine();
        tkn = nextToken(tkn.reset());
      } else if (c == strategy.getDelimiter()) {
        // empty token return TT_TOKEN("")
        tkn.type = TT_TOKEN;
        tkn.isReady = true;
      } else if (eol) {
        // empty token return TT_EORECORD("")
        //noop: tkn.content.append("");
        tkn.type = TT_EORECORD;
        tkn.isReady = true;
      } else if (c == strategy.getEncapsulator()) {
        // consume encapsulated token
        encapsulatedTokenLexer(tkn, c);
      } else if (isEndOfFile(c)) {
        // end of file return TT_EOF()
        //noop: tkn.content.append("");
        tkn.type = TT_EOF;
        tkn.isReady = true;
      } else {
        // next token must be a simple token
        // add removed blanks when not ignoring whitespace chars...
        if (!strategy.getIgnoreLeadingWhitespaces()) {
          tkn.content.append(wsBuf);
        }
        simpleTokenLexer(tkn, c);
      }
    }
    return tkn;  
  }
  
  /**
   * A simple token lexer
   * 
   * Simple token are tokens which are not surrounded by encapsulators.
   * A simple token might contain escaped delimiters (as \, or \;). The
   * token is finished when one of the following conditions become true:
   * <ul>
   *   <li>end of line has been reached (TT_EORECORD)</li>
   *   <li>end of stream has been reached (TT_EOF)</li>
   *   <li>an unescaped delimiter has been reached (TT_TOKEN)</li>
   * </ul>
   *  
   * @param tkn  the current token
   * @param c    the current character
   * @return the filled token
   * 
   * @throws IOException on stream access error
   */
  private Token simpleTokenLexer(Token tkn, int c) throws IOException {
    wsBuf.clear();
    while (!tkn.isReady) {
      if (isEndOfLine(c)) {
        // end of record
        tkn.type = TT_EORECORD;
        tkn.isReady = true;
      } else if (isEndOfFile(c)) {
        // end of file
        tkn.type = TT_EOF;
        tkn.isReady = true;
      } else if (c == strategy.getDelimiter()) {
        // end of token
        tkn.type = TT_TOKEN;
        tkn.isReady = true;
      } else if (c == '\\' && strategy.getUnicodeEscapeInterpretation() && in.lookAhead() == 'u') {
        // interpret unicode escaped chars (like \u0070 -> p)
        tkn.content.append((char) unicodeEscapeLexer(c));
      } else if (isWhitespace(c)) {
        // gather whitespaces 
        // (as long as they are not at the beginning of a token)
        if (tkn.content.length() > 0) {
          wsBuf.append((char) c);
        }
      } else {
        // prepend whitespaces (if we have)
        if (wsBuf.length() > 0) {
          tkn.content.append(wsBuf);
          wsBuf.clear();
        }
        tkn.content.append((char) c);
      }
      // get the next char
      if (!tkn.isReady) {
        c = in.read();
      }
    }
    return tkn;
  }
  
  
  /**
   * An encapsulated token lexer
   * 
   * Encapsulated tokens are surrounded by the given encapsulating-string.
   * The encapsulator itself might be included in the token using a
   * doubling syntax (as "", '') or using escaping (as in \", \').
   * Whitespaces before and after an encapsulated token are ignored.
   * 
   * @param tkn    the current token
   * @param c      the current character
   * @return a valid token object
   * @throws IOException on invalid state
   */
  private Token encapsulatedTokenLexer(Token tkn, int c) throws IOException {
    // save current line
    int startLineNumber = getLineNumber();
    // ignore the given delimiter
    // assert c == delimiter;
    c = in.read();
    while (!tkn.isReady) {
      if (c == strategy.getEncapsulator() || c == '\\') {
        // check lookahead
        if (in.lookAhead() == strategy.getEncapsulator()) {
          // double or escaped encapsulator -> add single encapsulator to token
          c = in.read();
          tkn.content.append((char) c);
        } else if (c == '\\' && in.lookAhead() == '\\') {
          // doubled escape char, it does not escape itself, only encapsulator 
          // -> add both escape chars to stream
          tkn.content.append((char) c);
          c = in.read();
          tkn.content.append((char) c);
        } else if (
          strategy.getUnicodeEscapeInterpretation()
          && c == '\\' 
          && in.lookAhead() == 'u') {
          // interpret unicode escaped chars (like \u0070 -> p)
          tkn.content.append((char) unicodeEscapeLexer(c));
        } else if (c == '\\') {
          // use a single escape character -> add it to stream
          tkn.content.append((char) c);
        } else {
          // token finish mark (encapsulator) reached: ignore whitespace till delimiter
          while (!tkn.isReady) {
            int n = in.lookAhead();
            if (n == strategy.getDelimiter()) {
              tkn.type = TT_TOKEN;
              tkn.isReady = true;
            } else if (isEndOfFile(n)) {
              tkn.type = TT_EOF;
              tkn.isReady = true;
            } else if (isEndOfLine(n)) {
              // ok eo token reached
              tkn.type = TT_EORECORD;
              tkn.isReady = true;
            } else if (!isWhitespace(n)) {
              // error invalid char between token and next delimiter
              throw new IOException(
                "(line " + getLineNumber() 
                + ") invalid char between encapsualted token end delimiter"
              );
            }
            c = in.read();
          }
        }
      } else if (isEndOfFile(c)) {
        // error condition (end of file before end of token)
        throw new IOException(
          "(startline " + startLineNumber + ")"
          + "eof reached before encapsulated token finished"
          );
      } else {
        // consume character
        tkn.content.append((char) c);
      }
      // get the next char
      if (!tkn.isReady) {
        c = in.read();  
      }
    }
    return tkn;
  }
  
  
  /**
   * Decodes Unicode escapes.
   * 
   * Interpretation of "\\uXXXX" escape sequences
   * where XXXX is a hex-number.
   * @param c current char which is discarded because it's the "\\" of "\\uXXXX"
   * @return the decoded character
   * @throws IOException on wrong unicode escape sequence or read error
   */
  protected int unicodeEscapeLexer(int c) throws IOException {
    int ret = 0;
    // ignore 'u' (assume c==\ now) and read 4 hex digits
    c = in.read();
    code.clear();
    try {
      for (int i = 0; i < 4; i++) {
        c  = in.read();
        if (isEndOfFile(c) || isEndOfLine(c)) {
          throw new NumberFormatException("number too short");
        }
        code.append((char) c);
      }
      ret = Integer.parseInt(code.toString(), 16);
    } catch (NumberFormatException e) {
      throw new IOException(
        "(line " + getLineNumber() + ") Wrong unicode escape sequence found '" 
        + code.toString() + "'" + e.toString());
    }
    return ret;
  }
  
  // ======================================================
  //  strategies
  // ======================================================
  
  /**
   * Sets the specified CSV Strategy
   *
   * @return current instance of CSVParser to allow chained method calls
   * @deprecated
   */
  public CSVParser setStrategy(CSVStrategy strategy) {
    this.strategy = strategy;
    return this;
  }
  
  /**
   * Obtain the specified CSV Strategy
   * 
   * @return strategy currently being used
   */
  public CSVStrategy getStrategy() {
    return this.strategy;
  }
  
  // ======================================================
  //  Character class checker
  // ======================================================
  
  /**
   * @return true if the given char is a whitespace character
   */
  private boolean isWhitespace(int c) {
    return Character.isWhitespace((char) c) && (c != strategy.getDelimiter());
  }
  
  /**
   * Greedy - accepts \n and \r\n 
   * This checker consumes silently the second control-character...
   * 
   * @return true if the given character is a line-terminator
   */
  private boolean isEndOfLine(int c) throws IOException {
    // check if we have \r\n...
    if (c == '\r') {
      if (in.lookAhead() == '\n') {
        // note: does not change c outside of this method !!
        c = in.read();
      }
    }
    return (c == '\n');
  }
  
  /**
   * @return true if the given character indicates end of file
   */
  private boolean isEndOfFile(int c) {
    return c == ExtendedBufferedReader.END_OF_STREAM;
  }
}
