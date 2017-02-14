/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core;

import com.datastax.driver.core.querybuilder.BuiltStatement;
import com.datastax.driver.core.schemabuilder.SchemaStatement;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static com.datastax.driver.core.StatementFormatter.StatementWriter.*;

/**
 * A customizable component to format instances of {@link Statement}.
 * <p/>
 * Instances of {@link StatementFormatter} can be obtained
 * through the {@link #builder()} method.
 * <p/>
 * Instances of this class are thread-safe.
 */
public final class StatementFormatter {

    public static final StatementFormatter DEFAULT_INSTANCE = StatementFormatter.builder().build();

    /**
     * Creates a new {@link StatementFormatter.Builder} instance.
     *
     * @return the new StatementFormatter builder.
     */
    public static StatementFormatter.Builder builder() {
        return new StatementFormatter.Builder();
    }

    /**
     * The desired statement format verbosity.
     * <p/>
     * This should be used as a guideline as to how much information
     * about the statement should be extracted and formatted.
     */
    public enum StatementFormatVerbosity {

        // the enum order matters

        /**
         * Formatters should only print a basic information in summarized form.
         */
        ABRIDGED,

        /**
         * Formatters should print basic information in summarized form,
         * and the statement's query string, if available.
         * <p/>
         * For batch statements, this verbosity level should
         * allow formatters to print information about the batch's
         * inner statements.
         */
        NORMAL,

        /**
         * Formatters should print full information, including
         * the statement's query string, if available,
         * and the statement's bound values, if available.
         */
        EXTENDED

    }

    /**
     * A statement printer is responsible for printing a specific type of {@link Statement statement},
     * with a given {@link StatementFormatVerbosity verbosity level},
     * and using a given {@link StatementWriter statement writer}.
     *
     * @param <S> The type of statement that this printer handles
     */
    public interface StatementPrinter<S extends Statement> {

        /**
         * The concrete {@link Statement} subclass that this printer handles.
         * <p/>
         * In case of subtype polymorphism, if this printer
         * handles more than one concrete subclass,
         * the most specific common ancestor should be returned here.
         *
         * @return The concrete {@link Statement} subclass that this printer handles.
         */
        Class<S> getSupportedStatementClass();

        /**
         * Prints the given {@link Statement statement},
         * using the given {@link StatementWriter statement writer} and
         * the given {@link StatementFormatVerbosity verbosity level}.
         *
         * @param statement the statement to print
         * @param out       the writer to use
         * @param verbosity the verbosity to use
         */
        void print(S statement, StatementWriter out, StatementFormatVerbosity verbosity);

    }

    /**
     * Thrown when a {@link StatementFormatter} encounters an error
     * while formatting a {@link Statement}.
     */
    public static class StatementFormatException extends RuntimeException {

        private final Statement statement;
        private final StatementFormatVerbosity verbosity;
        private final ProtocolVersion protocolVersion;
        private final CodecRegistry codecRegistry;

        public StatementFormatException(Statement statement, StatementFormatVerbosity verbosity, ProtocolVersion protocolVersion, CodecRegistry codecRegistry, Throwable t) {
            super(t);
            this.statement = statement;
            this.verbosity = verbosity;
            this.protocolVersion = protocolVersion;
            this.codecRegistry = codecRegistry;
        }

        /**
         * @return The statement that failed to format.
         */
        public Statement getStatement() {
            return statement;
        }

        /**
         * @return The requested verbosity.
         */
        public StatementFormatVerbosity getVerbosity() {
            return verbosity;
        }

        /**
         * @return The protocol version in use.
         */
        public ProtocolVersion getProtocolVersion() {
            return protocolVersion;
        }

        /**
         * @return The codec registry in use.
         */
        public CodecRegistry getCodecRegistry() {
            return codecRegistry;
        }
    }

    /**
     * A set of user-defined limitation rules that {@link StatementPrinter printers}
     * should strive to comply with when formatting statements.
     * <p/>
     * Limits defined in this class should be considered on a per-statement basis;
     * i.e. if the maximum query string length is 100 and the statement to format
     * is a {@link BatchStatement} with 5 inner statements, each inner statement
     * should be allowed to print a maximum of 100 characters of its query string.
     * <p/>
     * This class is NOT thread-safe.
     */
    public static final class StatementFormatterLimits {

        /**
         * A special value that conveys the notion of "unlimited".
         * All fields in this class accept this value.
         */
        public static final int UNLIMITED = -1;

        public final int maxQueryStringLength;
        public final int maxBoundValueLength;
        public final int maxBoundValues;
        public final int maxInnerStatements;
        public final int maxOutgoingPayloadEntries;
        public final int maxOutgoingPayloadValueLength;

        private StatementFormatterLimits(int maxQueryStringLength, int maxBoundValueLength, int maxBoundValues, int maxInnerStatements, int maxOutgoingPayloadEntries, int maxOutgoingPayloadValueLength) {
            this.maxQueryStringLength = maxQueryStringLength;
            this.maxBoundValueLength = maxBoundValueLength;
            this.maxBoundValues = maxBoundValues;
            this.maxInnerStatements = maxInnerStatements;
            this.maxOutgoingPayloadEntries = maxOutgoingPayloadEntries;
            this.maxOutgoingPayloadValueLength = maxOutgoingPayloadValueLength;
        }
    }

    /**
     * A registry for {@link StatementPrinter statement printers}.
     * <p/>
     * This class is thread-safe.
     */
    @SuppressWarnings("rawtypes")
    public static final class StatementPrinterRegistry {

        private final LoadingCache<Class<? extends Statement>, StatementPrinter> printers;

        private StatementPrinterRegistry() {
            printers = CacheBuilder.newBuilder().build(new CacheLoader<Class<? extends Statement>, StatementPrinter>() {
                @SuppressWarnings({"raw", "unchecked"})
                @Override
                public StatementPrinter load(Class key) throws Exception {
                    StatementPrinter printer = null;
                    while (printer == null) {
                        key = key.getSuperclass();
                        printer = printers.get(key);
                    }
                    return printer;
                }
            });

        }

        /**
         * Attempts to locate the best {@link StatementPrinter printer} for the given
         * statement.
         *
         * @param statement The statement find a printer for.
         * @return The best {@link StatementPrinter printer} for the given
         * statement. Cannot be {@code null}.
         */
        @SuppressWarnings("unchecked")
        public <S extends Statement> StatementPrinter<? super S> findPrinter(S statement) {
            try {
                return printers.get(statement.getClass());
            } catch (ExecutionException e) {
                // will never happen as long as a default statement printer is registered
                throw Throwables.propagate(e);
            }
        }

        private <S extends Statement> void register(StatementPrinter<S> printer) {
            printers.put(printer.getSupportedStatementClass(), printer);
        }

    }

    /**
     * This class exposes utility methods to help
     * {@link StatementPrinter statement printers} in formatting
     * a statement.
     * <p/>
     * Instances of this class are designed to format one single statement;
     * they keep internal counters such as the current number of printed bound values,
     * and for this reason, they should not be reused to format more than one statement.
     * When formatting more than one statement (e.g. when formatting a {@link BatchStatement} and its children),
     * one should call {@link #createChildWriter()} to create child instances of the main writer
     * to format each individual statement.
     * <p/>
     * This class is NOT thread-safe.
     */
    public static final class StatementWriter implements Appendable {

        public static final String summaryStart = " [";
        public static final String summaryEnd = "]";
        public static final String boundValuesCount = "%s bound values";
        public static final String statementsCount = "%s inner statements";
        public static final String queryStringStart = ": ";
        public static final String queryStringEnd = " ";
        public static final String boundValuesStart = "{ ";
        public static final String boundValuesEnd = " }";
        public static final String outgoingPayloadStart = "< ";
        public static final String outgoingPayloadEnd = " >";
        public static final String truncatedOutput = "...";
        public static final String nullValue = "<NULL>";
        public static final String unsetValue = "<UNSET>";
        public static final String listElementSeparator = ", ";
        public static final String nameValueSeparator = " : ";

        private static final int MAX_EXCEEDED = -2;

        private final StringBuilder buffer;
        private final StatementPrinterRegistry printerRegistry;
        private final StatementFormatterLimits limits;
        private final ProtocolVersion protocolVersion;
        private final CodecRegistry codecRegistry;
        private int remainingQueryStringChars;
        private int remainingBoundValues;

        private StatementWriter(StringBuilder buffer,
                                StatementPrinterRegistry printerRegistry,
                                StatementFormatterLimits limits,
                                ProtocolVersion protocolVersion,
                                CodecRegistry codecRegistry) {
            this.buffer = buffer;
            this.printerRegistry = printerRegistry;
            this.limits = limits;
            this.protocolVersion = protocolVersion;
            this.codecRegistry = codecRegistry;
            remainingQueryStringChars = limits.maxQueryStringLength == StatementFormatterLimits.UNLIMITED
                    ? Integer.MAX_VALUE
                    : limits.maxQueryStringLength;
            remainingBoundValues = limits.maxBoundValues == StatementFormatterLimits.UNLIMITED
                    ? Integer.MAX_VALUE
                    : limits.maxBoundValues;
        }

        /**
         * Creates and returns a child {@link StatementWriter}.
         * <p/>
         * A child writer shares the same buffer as its parent, but has its own independent state.
         * It is most useful when dealing with inner statements in batches (each inner statement should
         * use a child writer).
         *
         * @return a child {@link StatementWriter}.
         */
        public StatementWriter createChildWriter() {
            return new StatementWriter(buffer, printerRegistry, limits, protocolVersion, codecRegistry);
        }

        /**
         * @return The {@link StatementPrinterRegistry printer registry}.
         */
        public StatementPrinterRegistry getPrinterRegistry() {
            return printerRegistry;
        }

        /**
         * @return The current limits.
         */
        public StatementFormatterLimits getLimits() {
            return limits;
        }

        /**
         * @return The protocol version in use.
         */
        public ProtocolVersion getProtocolVersion() {
            return protocolVersion;
        }

        /**
         * @return The codec registry version in use.
         */
        public CodecRegistry getCodecRegistry() {
            return codecRegistry;
        }

        /**
         * @return The number of remaining query string characters that can be printed
         * without exceeding the maximum allowed length.
         */
        public int getRemainingQueryStringChars() {
            return remainingQueryStringChars;
        }

        /**
         * @return The number of remaining bound values per statement that can be printed
         * without exceeding the maximum allowed number.
         */
        public int getRemainingBoundValues() {
            return remainingBoundValues;
        }

        /**
         * @return {@code true} if the maximum query string length is exceeded, {@code false} otherwise.
         */
        public boolean maxQueryStringLengthExceeded() {
            return remainingQueryStringChars == MAX_EXCEEDED;
        }

        /**
         * @return {@code true} if the maximum number of bound values per statement is exceeded, {@code false} otherwise.
         */
        public boolean maxAppendedBoundValuesExceeded() {
            return remainingBoundValues == MAX_EXCEEDED;
        }

        @Override
        public StatementWriter append(CharSequence csq) {
            buffer.append(csq);
            return this;
        }

        @Override
        public StatementWriter append(CharSequence csq, int start, int end) {
            buffer.append(csq, start, end);
            return this;
        }

        @Override
        public StatementWriter append(char c) {
            buffer.append(c);
            return this;
        }

        public StatementWriter append(Object obj) {
            buffer.append(obj);
            return this;
        }

        public StatementWriter append(String str) {
            buffer.append(str);
            return this;
        }

        /**
         * Appends the statement's class name and hash code, as done by {@link Object#toString()}.
         *
         * @param statement The statement to format.
         * @return this
         */
        public StatementWriter appendClassNameAndHashCode(Statement statement) {
            String fqcn = statement.getClass().getName();
            if (fqcn.startsWith("com.datastax.driver.core.querybuilder"))
                fqcn = "BuiltStatement";
            if (fqcn.startsWith("com.datastax.driver.core.schemabuilder"))
                fqcn = "SchemaStatement";
            else if (fqcn.startsWith("com.datastax.driver.core."))
                fqcn = fqcn.substring(25);
            buffer.append(fqcn);
            buffer.append('@');
            buffer.append(Integer.toHexString(statement.hashCode()));
            return this;
        }

        /**
         * Appends the given fragment as a query string fragment.
         * <p>
         * This method can be called multiple times, in case the printer
         * needs to compute the query string by pieces.
         * <p>
         * This methods also keeps track of the amount of characters used so far
         * to print the query string, and automatically detects when
         * the query string exceeds {@link StatementFormatterLimits#maxQueryStringLength the maximum length},
         * in which case it truncates the output.
         *
         * @param queryStringFragment The query string fragment to append
         * @return this writer (for method chaining).
         */
        public StatementWriter appendQueryStringFragment(String queryStringFragment) {
            if (maxQueryStringLengthExceeded())
                return this;
            else if (queryStringFragment.isEmpty())
                return this;
            else if (limits.maxQueryStringLength == StatementFormatterLimits.UNLIMITED)
                buffer.append(queryStringFragment);
            else if (queryStringFragment.length() > remainingQueryStringChars) {
                if (remainingQueryStringChars > 0) {
                    queryStringFragment = queryStringFragment.substring(0, remainingQueryStringChars);
                    buffer.append(queryStringFragment);
                }
                buffer.append(truncatedOutput);
                remainingQueryStringChars = MAX_EXCEEDED;
            } else {
                buffer.append(queryStringFragment);
                remainingQueryStringChars -= queryStringFragment.length();
            }
            return this;
        }

        /**
         * Appends the statement's outgoing payload.
         *
         * @param outgoingPayload The payload to append
         * @return this
         */
        public StatementWriter appendOutgoingPayload(Map<String, ByteBuffer> outgoingPayload) {
            int remaining = limits.maxOutgoingPayloadEntries;
            Iterator<Map.Entry<String, ByteBuffer>> it = outgoingPayload.entrySet().iterator();
            if (it.hasNext()) {
                buffer.append(outgoingPayloadStart);
                while (it.hasNext()) {
                    if (limits.maxOutgoingPayloadEntries != StatementFormatterLimits.UNLIMITED && remaining == 0) {
                        buffer.append(truncatedOutput);
                        break;
                    }
                    Map.Entry<String, ByteBuffer> entry = it.next();
                    String name = entry.getKey();
                    buffer.append(name);
                    buffer.append(nameValueSeparator);
                    ByteBuffer value = entry.getValue();
                    String formatted;
                    boolean lengthExceeded = false;
                    if (value == null) {
                        formatted = nullValue;
                    } else {
                        if (limits.maxOutgoingPayloadValueLength != StatementFormatterLimits.UNLIMITED) {
                            // prevent large blobs from being converted to strings
                            lengthExceeded = value.remaining() > limits.maxOutgoingPayloadValueLength;
                            if (lengthExceeded)
                                value = (ByteBuffer) value.duplicate().limit(limits.maxOutgoingPayloadValueLength);
                        }
                        formatted = TypeCodec.blob().format(value);
                    }
                    buffer.append(formatted);
                    if (lengthExceeded)
                        buffer.append(truncatedOutput);
                    if (it.hasNext())
                        buffer.append(listElementSeparator);
                    if (limits.maxOutgoingPayloadEntries != StatementFormatterLimits.UNLIMITED)
                        remaining--;
                }
                buffer.append(outgoingPayloadEnd);
            }
            return this;
        }

        public StatementWriter appendBoundValue(int index, ByteBuffer serialized, DataType type) {
            if (maxAppendedBoundValuesExceeded())
                return this;
            return appendBoundValue(Integer.toString(index), serialized, type);
        }

        public StatementWriter appendBoundValue(String name, ByteBuffer serialized, DataType type) {
            if (maxAppendedBoundValuesExceeded())
                return this;
            TypeCodec<Object> codec = codecRegistry.codecFor(type);
            Object value = codec.deserialize(serialized, protocolVersion);
            return appendBoundValue(name, value, type);
        }

        public StatementWriter appendBoundValue(int index, Object value, DataType type) {
            if (maxAppendedBoundValuesExceeded())
                return this;
            return appendBoundValue(Integer.toString(index), value, type);
        }

        public StatementWriter appendBoundValue(String name, Object value, DataType type) {
            if (maxAppendedBoundValuesExceeded())
                return this;
            if (value == null) {
                doAppendBoundValue(name, nullValue);
                return this;
            } else if (value instanceof ByteBuffer && limits.maxBoundValueLength != StatementFormatterLimits.UNLIMITED) {
                ByteBuffer byteBuffer = (ByteBuffer) value;
                int maxBufferLengthInBytes = Math.max(2, limits.maxBoundValueLength / 2) - 1;
                boolean bufferLengthExceeded = byteBuffer.remaining() > maxBufferLengthInBytes;
                // prevent large blobs from being converted to strings
                if (bufferLengthExceeded) {
                    byteBuffer = (ByteBuffer) byteBuffer.duplicate().limit(maxBufferLengthInBytes);
                    // force usage of blob codec as any other codec would probably fail to format
                    // a cropped byte buffer anyway
                    String formatted = TypeCodec.blob().format(byteBuffer);
                    doAppendBoundValue(name, formatted);
                    buffer.append(truncatedOutput);
                    return this;
                }
            }
            TypeCodec<Object> codec = type == null ? codecRegistry.codecFor(value) : codecRegistry.codecFor(type, value);
            doAppendBoundValue(name, codec.format(value));
            return this;
        }

        public StatementWriter appendUnsetBoundValue(int index) {
            return appendUnsetBoundValue(Integer.toString(index));
        }

        public StatementWriter appendUnsetBoundValue(String name) {
            doAppendBoundValue(name, unsetValue);
            return this;
        }

        private void doAppendBoundValue(String name, String value) {
            if (maxAppendedBoundValuesExceeded())
                return;
            if (remainingBoundValues == 0) {
                buffer.append(truncatedOutput);
                remainingBoundValues = MAX_EXCEEDED;
                return;
            }
            boolean lengthExceeded = false;
            if (limits.maxBoundValueLength != StatementFormatterLimits.UNLIMITED && value.length() > limits.maxBoundValueLength) {
                value = value.substring(0, limits.maxBoundValueLength);
                lengthExceeded = true;
            }
            if (name != null) {
                buffer.append(name);
                buffer.append(nameValueSeparator);
            }
            buffer.append(value);
            if (lengthExceeded)
                buffer.append(truncatedOutput);
            if (limits.maxBoundValues != StatementFormatterLimits.UNLIMITED)
                remainingBoundValues--;
        }

        @Override
        public String toString() {
            return buffer.toString();
        }

    }

    /**
     * A common parent class for {@link StatementPrinter} implementations.
     * <p/>
     * This class assumes a common formatting pattern comprised of the following
     * sections:
     * <ol>
     * <li>Header: this section should contain two subsections:
     * <ol>
     *     <li>The actual statement class and the statement's hash code;</li>
     *     <li>The statement "summary"; examples of typical information
     *     that could be included here are: the statement's consistency level;
     *     its default timestamp; its idempotence flag; the number of bound values; etc.</li>
     * </ol>
     * </li>
     * <li>Query String: this section should print the statement's query string, if it is available;
     * this section is only enabled if the verbosity is {@link StatementFormatVerbosity#NORMAL NORMAL} or higher;</li>
     * <li>Bound Values: this section should print the statement's bound values, if available;
     * this section is only enabled if the verbosity is {@link StatementFormatVerbosity#EXTENDED EXTENDED};</li>
     * <li>Outgoing Payload: this section should print the statement's outgoing payload, if available;
     * this section is only enabled if the verbosity is {@link StatementFormatVerbosity#EXTENDED EXTENDED};</li>
     * <li>Footer: an optional section, empty by default.</li>
     * </ol>
     */
    public static class StatementPrinterBase<S extends Statement> implements StatementPrinter<S> {

        private final Class<S> supportedStatementClass;

        protected StatementPrinterBase(Class<S> supportedStatementClass) {
            this.supportedStatementClass = supportedStatementClass;
        }

        @Override
        public Class<S> getSupportedStatementClass() {
            return supportedStatementClass;
        }

        @Override
        public void print(S statement, StatementWriter out, StatementFormatVerbosity verbosity) {
            printHeader(statement, out, verbosity);
            if (verbosity.compareTo(StatementFormatVerbosity.NORMAL) >= 0) {
                printQueryString(statement, out, verbosity);
                if (verbosity.compareTo(StatementFormatVerbosity.EXTENDED) >= 0) {
                    printBoundValues(statement, out, verbosity);
                    printOutgoingPayload(statement, out, verbosity);
                }
            }
            printFooter(statement, out, verbosity);
        }

        protected void printHeader(S statement, StatementWriter out, StatementFormatVerbosity verbosity) {
            out.appendClassNameAndHashCode(statement);
            out.append(summaryStart);
            printSummary(statement, out, verbosity);
            out.append(summaryEnd);
        }

        protected void printSummary(S statement, StatementWriter out, StatementFormatVerbosity verbosity) {
            Boolean idempotent = statement.isIdempotent();
            ConsistencyLevel consistencyLevel = statement.getConsistencyLevel();
            out.append(String.format("idempotent=%s", idempotent == null ? unsetValue : idempotent));
            out.append(listElementSeparator);
            out.append(String.format("CL=%s", consistencyLevel == null ? unsetValue : consistencyLevel));
            if (verbosity.compareTo(StatementFormatVerbosity.NORMAL) >= 0) {
                ConsistencyLevel serialConsistencyLevel = statement.getSerialConsistencyLevel();
                long defaultTimestamp = statement.getDefaultTimestamp();
                int readTimeoutMillis = statement.getReadTimeoutMillis();
                out.append(listElementSeparator);
                out.append(String.format("SCL=%s", serialConsistencyLevel == null ? unsetValue : serialConsistencyLevel));
                out.append(listElementSeparator);
                out.append(String.format("defaultTimestamp=%s", defaultTimestamp == Long.MIN_VALUE ? unsetValue : defaultTimestamp));
                out.append(listElementSeparator);
                out.append(String.format("readTimeoutMillis=%s", readTimeoutMillis == Integer.MIN_VALUE ? unsetValue : readTimeoutMillis));
            }
        }

        protected void printQueryString(S statement, StatementWriter out, StatementFormatVerbosity verbosity) {
        }

        protected void printBoundValues(S statement, StatementWriter out, StatementFormatVerbosity verbosity) {
        }

        protected void printOutgoingPayload(S statement, StatementWriter out, StatementFormatVerbosity verbosity) {
            Map<String, ByteBuffer> outgoingPayload = statement.getOutgoingPayload();
            if (outgoingPayload != null)
                out.appendOutgoingPayload(outgoingPayload);
        }

        protected void printFooter(S statement, StatementWriter out, StatementFormatVerbosity verbosity) {
        }

    }

    /**
     * Common parent class for {@link StatementPrinter} implementations dealing with subclasses of {@link RegularStatement}.
     */
    public abstract static class AbstractRegularStatementPrinter<S extends RegularStatement> extends StatementPrinterBase<S> {

        protected AbstractRegularStatementPrinter(Class<S> supportedStatementClass) {
            super(supportedStatementClass);
        }

        @Override
        protected void printQueryString(S statement, StatementWriter out, StatementFormatVerbosity verbosity) {
            out.append(queryStringStart);
            out.appendQueryStringFragment(statement.getQueryString(out.getCodecRegistry()));
            out.append(queryStringEnd);
        }

    }

    public static class SimpleStatementPrinter extends AbstractRegularStatementPrinter<SimpleStatement> {

        public SimpleStatementPrinter() {
            super(SimpleStatement.class);
        }

        @Override
        protected void printSummary(SimpleStatement statement, StatementWriter out, StatementFormatVerbosity verbosity) {
            super.printSummary(statement, out, verbosity);
            out.append(listElementSeparator);
            out.append(String.format(boundValuesCount, statement.valuesCount()));
        }

        @Override
        protected void printBoundValues(SimpleStatement statement, StatementWriter out, StatementFormatVerbosity verbosity) {
            if (statement.valuesCount() > 0) {
                out.append(boundValuesStart);
                if (statement.usesNamedValues()) {
                    boolean first = true;
                    for (String valueName : statement.getValueNames()) {
                        if (first)
                            first = false;
                        else
                            out.append(listElementSeparator);
                        out.appendBoundValue(valueName, statement.getObject(valueName), null);
                        if (out.maxAppendedBoundValuesExceeded())
                            break;
                    }
                } else {
                    for (int i = 0; i < statement.valuesCount(); i++) {
                        if (i > 0)
                            out.append(listElementSeparator);
                        out.appendBoundValue(i, statement.getObject(i), null);
                        if (out.maxAppendedBoundValuesExceeded())
                            break;
                    }
                }
                out.append(boundValuesEnd);
            }
        }

    }

    public static class BuiltStatementPrinter extends AbstractRegularStatementPrinter<BuiltStatement> {

        public BuiltStatementPrinter() {
            super(BuiltStatement.class);
        }

        @Override
        protected void printSummary(BuiltStatement statement, StatementWriter out, StatementFormatVerbosity verbosity) {
            super.printSummary(statement, out, verbosity);
            out.append(listElementSeparator);
            out.append(String.format(boundValuesCount, statement.valuesCount(out.getCodecRegistry())));
        }

        @Override
        protected void printBoundValues(BuiltStatement statement, StatementWriter out, StatementFormatVerbosity verbosity) {
            if (statement.valuesCount(out.getCodecRegistry()) > 0) {
                out.append(boundValuesStart);
                // BuiltStatement does not use named values
                for (int i = 0; i < statement.valuesCount(out.getCodecRegistry()); i++) {
                    if (i > 0)
                        out.append(listElementSeparator);
                    out.appendBoundValue(i, statement.getObject(i), null);
                    if (out.maxAppendedBoundValuesExceeded())
                        break;
                }
                out.append(boundValuesEnd);
            }
        }

    }

    public static class BoundStatementPrinter extends StatementPrinterBase<BoundStatement> {

        public BoundStatementPrinter() {
            super(BoundStatement.class);
        }

        @Override
        protected void printSummary(BoundStatement statement, StatementWriter out, StatementFormatVerbosity verbosity) {
            super.printSummary(statement, out, verbosity);
            out.append(listElementSeparator);
            ColumnDefinitions metadata = statement.preparedStatement().getVariables();
            out.append(String.format(boundValuesCount, metadata.size()));
        }

        @Override
        protected void printQueryString(BoundStatement statement, StatementWriter out, StatementFormatVerbosity verbosity) {
            out.append(queryStringStart);
                out.appendQueryStringFragment(statement.preparedStatement().getQueryString());
            out.append(queryStringEnd);
        }

        @Override
        protected void printBoundValues(BoundStatement statement, StatementWriter out, StatementFormatVerbosity verbosity) {
            if (statement.preparedStatement().getVariables().size() > 0) {
                out.append(boundValuesStart);
                ColumnDefinitions metadata = statement.preparedStatement().getVariables();
                if (metadata.size() > 0) {
                    for (int i = 0; i < metadata.size(); i++) {
                        if (i > 0)
                            out.append(listElementSeparator);
                        if (statement.isSet(i))
                            out.appendBoundValue(metadata.getName(i), statement.wrapper.values[i], metadata.getType(i));
                        else
                            out.appendUnsetBoundValue(metadata.getName(i));
                        if (out.maxAppendedBoundValuesExceeded())
                            break;
                    }
                }
                out.append(boundValuesEnd);
            }
        }

    }

    public static class BatchStatementPrinter implements StatementPrinter<BatchStatement> {

        @Override
        public Class<BatchStatement> getSupportedStatementClass() {
            return BatchStatement.class;
        }

        @Override
        public void print(BatchStatement statement, StatementWriter out, StatementFormatVerbosity verbosity) {
            out.appendClassNameAndHashCode(statement);
            out.append(summaryStart);
            out.append(getBatchType(statement));
            out.append(listElementSeparator);
            out.append(String.format(statementsCount, statement.size()));
            out.append(listElementSeparator);
            int totalBoundValuesCount = 0;
            for (List<ByteBuffer> values : getValues(statement, out)) {
                totalBoundValuesCount += values.size();
            }
            out.append(String.format(boundValuesCount, totalBoundValuesCount));
            out.append(summaryEnd);
            if (verbosity.compareTo(StatementFormatVerbosity.NORMAL) >= 0 && out.getLimits().maxInnerStatements > 0) {
                out.append(' ');
                int i = 1;
                for (Statement stmt : statement.getStatements()) {
                    if (i > 1)
                        out.append(listElementSeparator);
                    if (i > out.getLimits().maxInnerStatements) {
                        out.append(truncatedOutput);
                        break;
                    }
                    out.append(i++);
                    out.append(listElementSeparator);
                    StatementPrinter<? super Statement> printer = out.getPrinterRegistry().findPrinter(stmt);
                    printer.print(stmt, out.createChildWriter(), verbosity);
                }
            }
        }

        protected BatchStatement.Type getBatchType(BatchStatement statement) {
            return statement.batchType;
        }

        protected List<List<ByteBuffer>> getValues(BatchStatement statement, StatementWriter out) {
            return statement.getIdAndValues(out.getProtocolVersion(), out.getCodecRegistry()).values;
        }

    }

    public static class StatementWrapperPrinter implements StatementPrinter<StatementWrapper> {

        @Override
        public Class<StatementWrapper> getSupportedStatementClass() {
            return StatementWrapper.class;
        }

        @Override
        public void print(StatementWrapper statement, StatementWriter out, StatementFormatVerbosity verbosity) {
            Statement wrappedStatement = statement.getWrappedStatement();
            StatementPrinter<? super Statement> printer = out.getPrinterRegistry().findPrinter(wrappedStatement);
            printer.print(wrappedStatement, out, verbosity);
        }

    }

    public static class SchemaStatementPrinter extends AbstractRegularStatementPrinter<SchemaStatement> {

        public SchemaStatementPrinter() {
            super(SchemaStatement.class);
        }

    }

    public static class RegularStatementPrinter extends AbstractRegularStatementPrinter<RegularStatement> {

        public RegularStatementPrinter() {
            super(RegularStatement.class);
        }

    }

    public static class DefaultStatementPrinter extends StatementPrinterBase<Statement> {

        public DefaultStatementPrinter() {
            super(Statement.class);
        }

    }

    /**
     * Helper class to build {@link StatementFormatter} instances with a fluent API.
     */
    public static class Builder {

        public static final int DEFAULT_MAX_QUERY_STRING_LENGTH = 500;
        public static final int DEFAULT_MAX_BOUND_VALUE_LENGTH = 50;
        public static final int DEFAULT_MAX_BOUND_VALUES = 10;
        public static final int DEFAULT_MAX_INNER_STATEMENTS = 5;
        public static final int DEFAULT_MAX_OUTGOING_PAYLOAD_ENTRIES = 10;
        public static final int DEFAULT_MAX_OUTGOING_PAYLOAD_VALUE_LENGTH = 50;

        private int maxQueryStringLength = DEFAULT_MAX_QUERY_STRING_LENGTH;
        private int maxBoundValueLength = DEFAULT_MAX_BOUND_VALUE_LENGTH;
        private int maxBoundValues = DEFAULT_MAX_BOUND_VALUES;
        private int maxInnerStatements = DEFAULT_MAX_INNER_STATEMENTS;
        private int maxOutgoingPayloadEntries = DEFAULT_MAX_OUTGOING_PAYLOAD_ENTRIES;
        private int maxOutgoingPayloadValueLength = DEFAULT_MAX_OUTGOING_PAYLOAD_VALUE_LENGTH;

        private final List<StatementPrinter<?>> printers = new ArrayList<StatementPrinter<?>>();

        private Builder() {
        }

        public Builder addStatementPrinter(StatementPrinter<?> printer) {
            printers.add(printer);
            return this;
        }

        public Builder addStatementPrinters(StatementPrinter<?>... printers) {
            this.printers.addAll(Arrays.asList(printers));
            return this;
        }

        /**
         * Sets the maximum length allowed for query strings.
         * The default is {@value DEFAULT_MAX_QUERY_STRING_LENGTH}.
         * <p/>
         * If the query string length exceeds this threshold,
         * printers should truncate it.
         *
         * @param maxQueryStringLength the maximum length allowed for query strings.
         * @throws IllegalArgumentException if the value is not > 0, or {@value StatementFormatterLimits#UNLIMITED} (unlimited).
         */
        public Builder withMaxQueryStringLength(int maxQueryStringLength) {
            if (maxQueryStringLength <= 0 && maxQueryStringLength != StatementFormatterLimits.UNLIMITED)
                throw new IllegalArgumentException("Invalid maxQueryStringLength, should be > 0 or -1 (unlimited), got " + maxQueryStringLength);
            this.maxQueryStringLength = maxQueryStringLength;
            return this;
        }

        /**
         * Sets the maximum length, in numbers of printed characters,
         * allowed for a single bound value.
         * The default is {@value DEFAULT_MAX_BOUND_VALUE_LENGTH}.
         * <p/>
         * If the bound value length exceeds this threshold,
         * printers should truncate it.
         *
         * @param maxBoundValueLength the maximum length, in numbers of printed characters,
         *                            allowed for a single bound value.
         * @throws IllegalArgumentException if the value is not > 0, or {@value StatementFormatterLimits#UNLIMITED} (unlimited).
         */
        public Builder withMaxBoundValueLength(int maxBoundValueLength) {
            if (maxBoundValueLength <= 0 && maxBoundValueLength != StatementFormatterLimits.UNLIMITED)
                throw new IllegalArgumentException("Invalid maxBoundValueLength, should be > 0 or -1 (unlimited), got " + maxBoundValueLength);
            this.maxBoundValueLength = maxBoundValueLength;
            return this;
        }

        /**
         * Sets the maximum number of printed bound values.
         * The default is {@value DEFAULT_MAX_BOUND_VALUES}.
         * <p/>
         * If the number of bound values exceeds this threshold,
         * printers should truncate it.
         *
         * @param maxBoundValues the maximum number of printed bound values.
         * @throws IllegalArgumentException if the value is not > 0, or {@value StatementFormatterLimits#UNLIMITED} (unlimited).
         */
        public Builder withMaxBoundValues(int maxBoundValues) {
            if (maxBoundValues <= 0 && maxBoundValues != StatementFormatterLimits.UNLIMITED)
                throw new IllegalArgumentException("Invalid maxBoundValues, should be > 0 or -1 (unlimited), got " + maxBoundValues);
            this.maxBoundValues = maxBoundValues;
            return this;
        }

        /**
         * Sets the maximum number of printed inner statements
         * of a {@link BatchStatement}.
         * The default is {@value DEFAULT_MAX_INNER_STATEMENTS}.
         * Setting this value to zero should disable the printing
         * of inner statements.
         * <p/>
         * If the number of inner statements exceeds this threshold,
         * printers should truncate it.
         * <p/>
         * If the statement to format is not a batch statement,
         * then this withting should be ignored.
         *
         * @param maxInnerStatements the maximum number of printed inner statements
         *                           of a {@link BatchStatement}.
         * @throws IllegalArgumentException if the value is not >= 0, or {@value StatementFormatterLimits#UNLIMITED} (unlimited).
         */
        public Builder withMaxInnerStatements(int maxInnerStatements) {
            if (maxInnerStatements < 0 && maxInnerStatements != StatementFormatterLimits.UNLIMITED)
                throw new IllegalArgumentException("Invalid maxInnerStatements, should be >= 0 or -1 (unlimited), got " + maxInnerStatements);
            this.maxInnerStatements = maxInnerStatements;
            return this;
        }

        /**
         * Sets the maximum number of printed outgoing payload entries.
         * The default is {@value DEFAULT_MAX_OUTGOING_PAYLOAD_ENTRIES}.
         * <p/>
         * If the number of entries exceeds this threshold,
         * printers should truncate it.
         *
         * @param maxOutgoingPayloadEntries the maximum number of printed outgoing payload entries.
         * @throws IllegalArgumentException if the value is not > 0, or {@value StatementFormatterLimits#UNLIMITED} (unlimited).
         */
        public Builder withMaxOutgoingPayloadEntries(int maxOutgoingPayloadEntries) {
            if (maxOutgoingPayloadEntries <= 0 && maxOutgoingPayloadEntries != StatementFormatterLimits.UNLIMITED)
                throw new IllegalArgumentException("Invalid maxOutgoingPayloadEntries, should be > 0 or -1 (unlimited), got " + maxOutgoingPayloadEntries);
            this.maxOutgoingPayloadEntries = maxOutgoingPayloadEntries;
            return this;
        }

        /**
         * Sets the maximum length, in bytes, allowed for a single outgoing payload value.
         * The default is {@value DEFAULT_MAX_OUTGOING_PAYLOAD_VALUE_LENGTH}.
         * <p/>
         * If the payload value length in bytes exceeds this threshold,
         * printers should truncate it.
         *
         * @param maxOutgoingPayloadValueLength the maximum length, in bytes, allowed for a single outgoing payload value.
         * @throws IllegalArgumentException if the value is not > 0, or {@value StatementFormatterLimits#UNLIMITED} (unlimited).
         */
        public Builder withMaxOutgoingPayloadValueLength(int maxOutgoingPayloadValueLength) {
            if (maxOutgoingPayloadValueLength <= 0 && maxOutgoingPayloadValueLength != StatementFormatterLimits.UNLIMITED)
                throw new IllegalArgumentException("Invalid maxOutgoingPayloadValueLength, should be > 0 or -1 (unlimited), got " + maxOutgoingPayloadValueLength);
            this.maxOutgoingPayloadValueLength = maxOutgoingPayloadValueLength;
            return this;
        }

        /**
         * Build the {@link StatementFormatter} instance.
         *
         * @return the {@link StatementFormatter} instance.
         * @throws IllegalArgumentException if the builder is unable to build a valid instance due to incorrect settings.
         */
        public StatementFormatter build() {
            StatementPrinterRegistry registry = new StatementPrinterRegistry();
            registerDefaultPrinters(registry);
            for (StatementPrinter<?> printer : printers) {
                registry.register(printer);
            }
            StatementFormatterLimits limits = new StatementFormatterLimits(
                    maxQueryStringLength, maxBoundValueLength, maxBoundValues, maxInnerStatements, maxOutgoingPayloadEntries, maxOutgoingPayloadValueLength);
            return new StatementFormatter(registry, limits);
        }

        private void registerDefaultPrinters(StatementPrinterRegistry registry) {
            registry.register(new DefaultStatementPrinter());
            registry.register(new SimpleStatementPrinter());
            registry.register(new RegularStatementPrinter());
            registry.register(new BuiltStatementPrinter());
            registry.register(new SchemaStatementPrinter());
            registry.register(new BoundStatementPrinter());
            registry.register(new BatchStatementPrinter());
            registry.register(new StatementWrapperPrinter());
        }

    }

    private final StatementPrinterRegistry printerRegistry;
    private final StatementFormatterLimits limits;

    private StatementFormatter(StatementPrinterRegistry printerRegistry, StatementFormatterLimits limits) {
        this.printerRegistry = printerRegistry;
        this.limits = limits;
    }

    /**
     * Formats the given {@link Statement statement}.
     *
     * @param statement       The statement to format.
     * @param verbosity       The verbosity to use.
     * @param protocolVersion The protocol version in use.
     * @param codecRegistry   The codec registry in use.
     * @return The statement as a formatted string.
     * @throws StatementFormatException if the formatting failed.
     */
    public <S extends Statement> String format(
            S statement, StatementFormatVerbosity verbosity,
            ProtocolVersion protocolVersion, CodecRegistry codecRegistry) {
        try {
            StatementPrinter<? super S> printer = printerRegistry.findPrinter(statement);
            assert printer != null : "Could not find printer for statement class " + statement.getClass();
            StatementWriter out = new StatementWriter(new StringBuilder(), printerRegistry, limits, protocolVersion, codecRegistry);
            printer.print(statement, out, verbosity);
            return out.toString().trim();
        } catch (RuntimeException e) {
            throw new StatementFormatException(statement, verbosity, protocolVersion, codecRegistry, e);
        }
    }

}
