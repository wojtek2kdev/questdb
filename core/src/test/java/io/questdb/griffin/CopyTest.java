/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.MessageBus;
import io.questdb.MessageBusImpl;
import io.questdb.cairo.*;
import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.cairo.sql.*;
import io.questdb.griffin.engine.functions.bind.BindVariableService;
import io.questdb.std.*;
import io.questdb.test.tools.TestUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.*;

import java.io.File;

public class CopyTest extends AbstractCairoTest {

    protected static final BindVariableService bindVariableService = new BindVariableService();
    private static final MessageBus messageBus = new MessageBusImpl();
    protected static final SqlExecutionContext sqlExecutionContext = new SqlExecutionContextImpl(configuration, messageBus, 1).with(
            AllowAllCairoSecurityContext.INSTANCE,
            bindVariableService,
            null
    );

    private static final LongList rows = new LongList();
    private static CairoEngine engine;
    private static SqlCompiler compiler;

    public static void assertVariableColumns(RecordCursorFactory factory, boolean checkSameStr) {
        try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
            RecordMetadata metadata = factory.getMetadata();
            final int columnCount = metadata.getColumnCount();
            final Record record = cursor.getRecord();
            while (cursor.hasNext()) {
                for (int i = 0; i < columnCount; i++) {
                    switch (metadata.getColumnType(i)) {
                        case ColumnType.STRING:
                            CharSequence a = record.getStr(i);
                            CharSequence b = record.getStrB(i);
                            if (a == null) {
                                Assert.assertNull(b);
                                Assert.assertEquals(TableUtils.NULL_LEN, record.getStrLen(i));
                            } else {
                                if (checkSameStr) {
                                    Assert.assertNotSame(a, b);
                                }
                                TestUtils.assertEquals(a, b);
                                Assert.assertEquals(a.length(), record.getStrLen(i));
                            }
                            break;
                        case ColumnType.BINARY:
                            BinarySequence s = record.getBin(i);
                            if (s == null) {
                                Assert.assertEquals(TableUtils.NULL_LEN, record.getBinLen(i));
                            } else {
                                Assert.assertEquals(s.length(), record.getBinLen(i));
                            }
                            break;
                        default:
                            break;
                    }
                }
            }
        }
    }

    @BeforeClass
    public static void setUp2() {
        CairoConfiguration configuration = new DefaultCairoConfiguration(AbstractCairoTest.configuration.getRoot()) {
            @Override
            public CharSequence getInputRoot() {
                return new File(".").getAbsolutePath();
            }
        };
        engine = new CairoEngine(configuration, messageBus);
        compiler = new SqlCompiler(engine);
        bindVariableService.clear();
    }

    @AfterClass
    public static void tearDown() {
        engine.close();
        compiler.close();
    }

    protected static void assertCursor(
            CharSequence expected,
            RecordCursorFactory factory,
            boolean supportsRandomAccess,
            boolean checkSameStr
    ) {
        try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
            if (expected == null) {
                Assert.assertFalse(cursor.hasNext());
                cursor.toTop();
                Assert.assertFalse(cursor.hasNext());
                return;
            }

            sink.clear();
            printer.print(cursor, factory.getMetadata(), true);

            TestUtils.assertEquals(expected, sink);

            final RecordMetadata metadata = factory.getMetadata();

            testSymbolAPI(metadata, cursor);
            cursor.toTop();
            testStringsLong256AndBinary(metadata, cursor, checkSameStr);

            // test API where same record is being updated by cursor
            cursor.toTop();
            Record record = cursor.getRecord();
            Assert.assertNotNull(record);
            sink.clear();
            printer.printHeader(metadata);
            long count = 0;
            long cursorSize = cursor.size();
            while (cursor.hasNext()) {
                printer.print(record, metadata);
                count++;
            }

            Assert.assertTrue(cursorSize == -1 || count == cursorSize);

            TestUtils.assertEquals(expected, sink);

            if (supportsRandomAccess) {

                Assert.assertTrue(factory.recordCursorSupportsRandomAccess());

                cursor.toTop();

                sink.clear();
                rows.clear();
                while (cursor.hasNext()) {
                    rows.add(record.getRowId());
                }

                final Record rec = cursor.getRecordB();
                printer.printHeader(metadata);
                for (int i = 0, n = rows.size(); i < n; i++) {
                    cursor.recordAt(rec, rows.getQuick(i));
                    printer.print(rec, metadata);
                }

                TestUtils.assertEquals(expected, sink);

                sink.clear();

                final Record factRec = cursor.getRecordB();
                printer.printHeader(metadata);
                for (int i = 0, n = rows.size(); i < n; i++) {
                    cursor.recordAt(factRec, rows.getQuick(i));
                    printer.print(factRec, metadata);
                }

                TestUtils.assertEquals(expected, sink);

                // test that absolute positioning of record does not affect state of record cursor
                if (rows.size() > 0) {
                    sink.clear();

                    cursor.toTop();
                    int target = rows.size() / 2;
                    printer.printHeader(metadata);
                    while (target-- > 0 && cursor.hasNext()) {
                        printer.print(record, metadata);
                    }

                    // no obliterate record with absolute positioning
                    for (int i = 0, n = rows.size(); i < n; i++) {
                        cursor.recordAt(factRec, rows.getQuick(i));
                    }

                    // not continue normal fetch
                    while (cursor.hasNext()) {
                        printer.print(record, metadata);
                    }

                    TestUtils.assertEquals(expected, sink);

                }
            } else {
                Assert.assertFalse(factory.recordCursorSupportsRandomAccess());
                try {
                    record.getRowId();
                    Assert.fail();
                } catch (UnsupportedOperationException ignore) {
                }

                try {
                    cursor.getRecordB();
                    Assert.fail();
                } catch (UnsupportedOperationException ignore) {
                }

                try {
                    cursor.recordAt(record, 0);
                    Assert.fail();
                } catch (UnsupportedOperationException ignore) {
                }
            }
        }

        try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
            testSymbolAPI(factory.getMetadata(), cursor);
        }
    }

    private static void testStringsLong256AndBinary(RecordMetadata metadata, RecordCursor cursor, boolean checkSameStr) {
        Record record = cursor.getRecord();
        while (cursor.hasNext()) {
            for (int i = 0, n = metadata.getColumnCount(); i < n; i++) {
                switch (metadata.getColumnType(i)) {
                    case ColumnType.STRING:
                        CharSequence s = record.getStr(i);
                        if (s != null) {
                            if (checkSameStr) {
                                Assert.assertNotSame(s, record.getStrB(i));
                            }
                            TestUtils.assertEquals(s, record.getStrB(i));
                            Assert.assertEquals(s.length(), record.getStrLen(i));
                        } else {
                            Assert.assertNull(record.getStrB(i));
                            Assert.assertEquals(TableUtils.NULL_LEN, record.getStrLen(i));
                        }
                        break;
                    case ColumnType.BINARY:
                        BinarySequence bs = record.getBin(i);
                        if (bs != null) {
                            Assert.assertEquals(record.getBin(i).length(), record.getBinLen(i));
                        } else {
                            Assert.assertEquals(TableUtils.NULL_LEN, record.getBinLen(i));
                        }
                        break;
                    case ColumnType.LONG256:
                        Long256 l1 = record.getLong256A(i);
                        Long256 l2 = record.getLong256B(i);
                        if (l1 == Long256Impl.NULL_LONG256) {
                            Assert.assertSame(l1, l2);
                        } else {
                            Assert.assertNotSame(l1, l2);
                        }
                        Assert.assertEquals(l1.getLong0(), l2.getLong0());
                        Assert.assertEquals(l1.getLong1(), l2.getLong1());
                        Assert.assertEquals(l1.getLong2(), l2.getLong2());
                        Assert.assertEquals(l1.getLong3(), l2.getLong3());
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private static void testSymbolAPI(RecordMetadata metadata, RecordCursor cursor) {
        IntList symbolIndexes = null;
        for (int i = 0, n = metadata.getColumnCount(); i < n; i++) {
            if (metadata.getColumnType(i) == ColumnType.SYMBOL) {
                if (symbolIndexes == null) {
                    symbolIndexes = new IntList();
                }
                symbolIndexes.add(i);
            }
        }

        if (symbolIndexes != null) {
            cursor.toTop();
            final Record record = cursor.getRecord();
            while (cursor.hasNext()) {
                for (int i = 0, n = symbolIndexes.size(); i < n; i++) {
                    int column = symbolIndexes.getQuick(i);
                    SymbolTable symbolTable = cursor.getSymbolTable(column);
                    if (symbolTable instanceof StaticSymbolTable) {
                        CharSequence sym = record.getSym(column);
                        int value = record.getInt(column);
                        Assert.assertEquals(value, ((StaticSymbolTable) symbolTable).keyOf(sym));
                        TestUtils.assertEquals(sym, symbolTable.valueOf(value));
                    } else {
                        final int value = record.getInt(column);
                        TestUtils.assertEquals(record.getSym(column), symbolTable.valueOf(value));
                    }
                }
            }
        }
    }

    protected static void assertTimestampColumnValues(RecordCursorFactory factory) {
        int index = factory.getMetadata().getTimestampIndex();
        long timestamp = Long.MIN_VALUE;
        try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
            final Record record = cursor.getRecord();
            while (cursor.hasNext()) {
                long ts = record.getTimestamp(index);
                Assert.assertTrue(timestamp <= ts);
                timestamp = ts;
            }
        }
    }

    protected static void printSqlResult(
            CharSequence expected,
            CharSequence query,
            CharSequence expectedTimestamp,
            CharSequence ddl2,
            CharSequence expected2,
            boolean supportsRandomAccess,
            boolean checkSameStr
    ) throws SqlException {
        RecordCursorFactory factory = compiler.compile(query, sqlExecutionContext).getRecordCursorFactory();
        try {
            assertTimestamp(expectedTimestamp, factory);
            assertCursor(expected, factory, supportsRandomAccess, checkSameStr);
            // make sure we get the same outcome when we get factory to create new cursor
            assertCursor(expected, factory, supportsRandomAccess, checkSameStr);
            // make sure strings, binary fields and symbols are compliant with expected record behaviour
            assertVariableColumns(factory, checkSameStr);

            if (ddl2 != null) {
                compiler.compile(ddl2, sqlExecutionContext);

                int count = 3;
                while (count > 0) {
                    try {
                        assertCursor(expected2, factory, supportsRandomAccess, checkSameStr);
                        // and again
                        assertCursor(expected2, factory, supportsRandomAccess, checkSameStr);
                        return;
                    } catch (ReaderOutOfDateException e) {
                        Misc.free(factory);
                        factory = compiler.compile(query, sqlExecutionContext).getRecordCursorFactory();
                        count--;
                    }
                }
            }
        } finally {
            Misc.free(factory);
        }
    }

    private static void assertQuery(
            CharSequence expected,
            CharSequence query,
            @Nullable CharSequence ddl,
            @Nullable CharSequence verify,
            @Nullable CharSequence expectedTimestamp,
            @Nullable CharSequence ddl2,
            @Nullable CharSequence expected2,
            boolean supportsRandomAccess,
            boolean checkSameStr
    ) throws Exception {
        assertMemoryLeak(() -> {
            if (ddl != null) {
                compiler.compile(ddl, sqlExecutionContext);
            }
            if (verify != null) {
                printSqlResult(null, verify, expectedTimestamp, ddl2, expected2, supportsRandomAccess, checkSameStr);
            }
            printSqlResult(expected, query, expectedTimestamp, ddl2, expected2, supportsRandomAccess, checkSameStr);
        });
    }

    protected static void assertQuery(
            CharSequence expected,
            CharSequence query,
            CharSequence ddl,
            @Nullable CharSequence expectedTimestamp) throws Exception {
        assertQuery(expected, query, ddl, null, expectedTimestamp, null, null, true, true);
    }

    protected static void assertQuery(
            CharSequence expected,
            CharSequence query,
            CharSequence ddl,
            @Nullable CharSequence expectedTimestamp,
            boolean supportsRandomAccess
    ) throws Exception {
        assertQuery(expected, query, ddl, null, expectedTimestamp, null, null, supportsRandomAccess, true);
    }

    private static void assertTimestamp(CharSequence expectedTimestamp, RecordCursorFactory factory) {
        if (expectedTimestamp == null) {
            Assert.assertEquals(-1, factory.getMetadata().getTimestampIndex());
        } else {
            int index = factory.getMetadata().getColumnIndex(expectedTimestamp);
            Assert.assertNotEquals(-1, index);
            Assert.assertEquals(index, factory.getMetadata().getTimestampIndex());
            assertTimestampColumnValues(factory);
        }
    }

    private static void assertMemoryLeak(TestUtils.LeakProneCode code) throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try {
                code.run();
                engine.releaseInactive();
                Assert.assertEquals(0, engine.getBusyWriterCount());
                Assert.assertEquals(0, engine.getBusyReaderCount());
            } finally {
                engine.releaseAllReaders();
                engine.releaseAllWriters();
            }
        });
    }

    @Test
    public void testSimpleCopy() throws Exception {
        assertMemoryLeak(() -> {

            compiler.compile("copy x from '/target/test-classes/csv/test-import.csv'", sqlExecutionContext);

            final String expected = "StrSym\tIntSym\tIntCol\tDoubleCol\tIsoDate\tFmt1Date\tFmt2Date\tPhone\tboolean\tlong\n" +
                    "CMP1\t1\t6992\t2.12060110410675\t2015-01-05T19:15:09.000Z\t2015-01-05T19:15:09.000Z\t2015-01-05T00:00:00.000Z\t6992\ttrue\t4952743\n" +
                    "CMP2\t2\t8014\t5.18098710570484\t2015-01-06T19:15:09.000Z\t2015-01-06T19:15:09.000Z\t2015-01-06T00:00:00.000Z\t8014\tfalse\t10918770\n" +
                    "CMP1\t4\t2599\t1.26877639908344\t2015-01-07T19:15:09.000Z\t2015-01-07T19:15:09.000Z\t2015-01-07T00:00:00.000Z\t2599\ttrue\t80790249\n" +
                    "CMP2\t2\t7610\t0.314211035147309\t2015-01-08T19:15:09.000Z\t2015-01-08T19:15:09.000Z\t2015-01-08T00:00:00.000Z\t7610\tfalse\t62209537\n" +
                    "CMP1\t5\t6608\t6.57507313182577\t2015-01-09T19:15:09.000Z\t2015-01-09T19:15:09.000Z\t2015-01-09T00:00:00.000Z\t6608\ttrue\t86456029\n" +
                    "CMP2\t6\t2699\t3.78073266241699\t2015-01-10T19:15:09.000Z\t2015-01-10T19:15:09.000Z\t2015-01-10T00:00:00.000Z\t2699\tfalse\t28805742\n" +
                    "CMP1\t1\t6902\t2.88266013609245\t2015-01-11T19:15:09.000Z\t2015-01-11T19:15:09.000Z\t2015-01-11T00:00:00.000Z\t6902\ttrue\t32945468\n" +
                    "CMP2\t6\t449\t8.2610409706831\t2015-01-12T19:15:09.000Z\t2015-01-12T19:15:09.000Z\t2015-01-12T00:00:00.000Z\t449\tfalse\t92310232\n" +
                    "CMP1\t7\t8284\t3.2045788760297\t2015-01-13T19:15:09.000Z\t2015-01-13T19:15:09.000Z\t2015-01-13T00:00:00.000Z\t8284\ttrue\t10239799\n" +
                    "CMP2\t3\t1066\t7.5186683377251\t2015-01-14T19:15:09.000Z\t2015-01-14T19:15:09.000Z\t2015-01-14T00:00:00.000Z\t1066\tfalse\t23331405\n" +
                    "CMP1\t4\t6938\t5.11407712241635\t2015-01-15T19:15:09.000Z\t2015-01-15T19:15:09.000Z\t2015-01-15T00:00:00.000Z\t(099)889-776\ttrue\t55296137\n" +
                    "\t6\t4527\t2.48986426275223\t2015-01-16T19:15:09.000Z\t2015-01-16T19:15:09.000Z\t2015-01-16T00:00:00.000Z\t2719\tfalse\t67489936\n" +
                    "CMP1\t7\t6460\t6.39910243218765\t2015-01-17T19:15:09.000Z\t2015-01-17T19:15:09.000Z\t2015-01-17T00:00:00.000Z\t5142\ttrue\t69744070\n" +
                    "CMP2\t1\t7335\t1.07411710545421\t2015-01-18T19:15:09.000Z\t2015-01-18T19:15:09.000Z\t2015-01-18T00:00:00.000Z\t2443\tfalse\t8553585\n" +
                    "CMP1\t5\t1487\t7.40816951030865\t2015-01-19T19:15:09.000Z\t2015-01-19T19:15:09.000Z\t2015-01-19T00:00:00.000Z\t6705\ttrue\t91341680\n" +
                    "CMP2\t5\t8997\t2.71285555325449\t2015-01-20T19:15:09.000Z\t2015-01-20T19:15:09.000Z\t2015-01-20T00:00:00.000Z\t5401\tfalse\t86999930\n" +
                    "CMP1\t1\t7054\t8.12909856671467\t2015-01-21T19:15:09.000Z\t2015-01-21T19:15:09.000Z\t2015-01-21T00:00:00.000Z\t8487\ttrue\t32189412\n" +
                    "\t2\t393\t2.56299464497715\t2015-01-22T19:15:09.000Z\t2015-01-22T19:15:09.000Z\t2015-01-22T00:00:00.000Z\t6862\tfalse\t47274133\n" +
                    "CMP1\t1\t7580\t8.1683173822239\t2015-01-23T19:15:09.000Z\t2015-01-23T19:15:09.000Z\t2015-01-23T00:00:00.000Z\t4646\ttrue\t13982302\n" +
                    "CMP2\t7\t6103\t6.36347207706422\t2015-01-24T19:15:09.000Z\t2015-01-24T19:15:09.000Z\t2015-01-24T00:00:00.000Z\t6047\tfalse\t84767095\n" +
                    "CMP1\t7\t1313\t7.38160170149058\t2015-01-25T19:15:09.000Z\t2015-01-25T19:15:09.000Z\t2015-01-25T00:00:00.000Z\t3837\ttrue\t13178079\n" +
                    "CMP1\t1\t9952\t5.43148486176506\t2015-01-26T19:15:09.000Z\t2015-01-26T19:15:09.000Z\t2015-01-26T00:00:00.000Z\t5578\tfalse\t61000112\n" +
                    "CMP2\t2\t5589\t3.8917106972076003\t2015-01-27T19:15:09.000Z\t\t2015-01-27T00:00:00.000Z\t4153\ttrue\t43900701\n" +
                    "CMP1\t3\t9438\t3.90446535777301\t2015-01-28T19:15:09.000Z\t2015-01-28T19:15:09.000Z\t2015-01-28T00:00:00.000Z\t6363\tfalse\t88289909\n" +
                    "CMP2\t8\t8000\t2.27636352181435\t2015-01-29T19:15:09.000Z\t2015-01-29T19:15:09.000Z\t2015-01-29T00:00:00.000Z\t323\ttrue\t14925407\n" +
                    "CMP1\t2\t1581\t9.01423481060192\t2015-01-30T19:15:09.000Z\t2015-01-30T19:15:09.000Z\t2015-01-30T00:00:00.000Z\t9138\tfalse\t68225213\n" +
                    "CMP2\t8\t7067\t9.6284336107783\t2015-01-31T19:15:09.000Z\t2015-01-31T19:15:09.000Z\t2015-01-31T00:00:00.000Z\t8197\ttrue\t58403960\n" +
                    "CMP1\t8\t5313\t8.87764661805704\t2015-02-01T19:15:09.000Z\t2015-02-01T19:15:09.000Z\t2015-02-01T00:00:00.000Z\t2733\tfalse\t69698373\n" +
                    "\t4\t3883\t7.96873019309714\t2015-02-02T19:15:09.000Z\t2015-02-02T19:15:09.000Z\t2015-02-02T00:00:00.000Z\t6912\ttrue\t91147394\n" +
                    "CMP1\t7\t4256\t2.46553522534668\t2015-02-03T19:15:09.000Z\t2015-02-03T19:15:09.000Z\t2015-02-03T00:00:00.000Z\t9453\tfalse\t50278940\n" +
                    "CMP2\t4\t155\t5.08547495584935\t2015-02-04T19:15:09.000Z\t2015-02-04T19:15:09.000Z\t2015-02-04T00:00:00.000Z\t8919\ttrue\t8671995\n" +
                    "CMP1\t7\t4486\tNaN\t2015-02-05T19:15:09.000Z\t2015-02-05T19:15:09.000Z\t2015-02-05T00:00:00.000Z\t8670\tfalse\t751877\n" +
                    "CMP2\t2\t6641\t0.0381825352087617\t2015-02-06T19:15:09.000Z\t2015-02-06T19:15:09.000Z\t2015-02-06T00:00:00.000Z\t8331\ttrue\t40909232527\n" +
                    "CMP1\t1\t3579\t0.849663221742958\t2015-02-07T19:15:09.000Z\t2015-02-07T19:15:09.000Z\t2015-02-07T00:00:00.000Z\t9592\tfalse\t11490662\n" +
                    "CMP2\t2\t4770\t2.85092033445835\t2015-02-08T19:15:09.000Z\t2015-02-08T19:15:09.000Z\t2015-02-08T00:00:00.000Z\t253\ttrue\t33766814\n" +
                    "CMP1\t5\t4938\t4.42754498450086\t2015-02-09T19:15:09.000Z\t2015-02-09T19:15:09.000Z\t2015-02-09T00:00:00.000Z\t7817\tfalse\t61983099\n" +
                    "CMP2\t6\t5939\t5.26230568997562\t2015-02-10T19:15:09.000Z\t2015-02-10T19:15:09.000Z\t2015-02-10T00:00:00.000Z\t7857\ttrue\t83851352\n" +
                    "CMP1\t6\t2830\t1.92678665509447\t2015-02-11T19:15:09.000Z\t2015-02-11T19:15:09.000Z\t2015-02-11T00:00:00.000Z\t9647\tfalse\t47528916\n" +
                    "CMP2\t3\t3776\t5.4143834207207\t2015-02-12T19:15:09.000Z\t2015-02-12T19:15:09.000Z\t2015-02-12T00:00:00.000Z\t5368\ttrue\t59341626\n" +
                    "CMP1\t8\t1444\t5.33778431359679\t2015-02-13T19:15:09.000Z\t2015-02-13T19:15:09.000Z\t2015-02-13T00:00:00.000Z\t7425\tfalse\t61302397\n" +
                    "CMP2\t2\t2321\t3.65820386214182\t2015-02-14T19:15:09.000Z\t2015-02-14T19:15:09.000Z\t2015-02-14T00:00:00.000Z\t679\ttrue\t90665386\n" +
                    "CMP1\t7\t3870\t3.42176506761461\t2015-02-15T19:15:09.000Z\t2015-02-15T19:15:09.000Z\t2015-02-15T00:00:00.000Z\t5610\tfalse\t50649828\n" +
                    "CMP2\t4\t1253\t0.541768460534513\t2015-02-16T19:15:09.000Z\t2015-02-16T19:15:09.000Z\t2015-02-16T00:00:00.000Z\t4377\ttrue\t21383690\n" +
                    "CMP1\t4\t268\t3.09822975890711\t2015-02-17T19:15:09.000Z\t2015-02-17T19:15:09.000Z\t2015-02-17T00:00:00.000Z\t669\tfalse\t71326228\n" +
                    "CMP2\t8\t5548\t3.7650444637984\t2015-02-18T19:15:09.000Z\t2015-02-18T19:15:09.000Z\t2015-02-18T00:00:00.000Z\t7369\ttrue\t82105548\n" +
                    "CMP1\t4\tNaN\t9.31892040651292\t2015-02-19T19:15:09.000Z\t2015-02-19T19:15:09.000Z\t2015-02-19T00:00:00.000Z\t2022\tfalse\t16097569\n" +
                    "CMP2\t1\t1670\t9.44043743424118\t2015-02-20T19:15:09.000Z\t2015-02-20T19:15:09.000Z\t2015-02-20T00:00:00.000Z\t3235\ttrue\t88917951\n" +
                    "CMP1\t7\t5534\t5.78428176697344\t2015-02-21T19:15:09.000Z\t2015-02-21T19:15:09.000Z\t2015-02-21T00:00:00.000Z\t9650\tfalse\t10261372\n" +
                    "CMP2\t5\t8085\t5.49041963648051\t2015-02-22T19:15:09.000Z\t2015-02-22T19:15:09.000Z\t2015-02-22T00:00:00.000Z\t2211\ttrue\t28722529\n" +
                    "CMP1\t1\t7916\t7.37360095838085\t2015-02-23T19:15:09.000Z\t2015-02-23T19:15:09.000Z\t2015-02-23T00:00:00.000Z\t1598\tfalse\t48269680\n" +
                    "CMP2\t3\t9117\t6.16650991374627\t2015-02-24T19:15:09.000Z\t2015-02-24T19:15:09.000Z\t2015-02-24T00:00:00.000Z\t3588\ttrue\t4354364\n" +
                    "CMP1\t6\t2745\t6.12624417291954\t2015-02-25T19:15:09.000Z\t2015-02-25T19:15:09.000Z\t2015-02-25T00:00:00.000Z\t6149\tfalse\t71925383\n" +
                    "CMP2\t2\t986\t4.00966874323785\t2015-02-26T19:15:09.000Z\t2015-02-26T19:15:09.000Z\t2015-02-26T00:00:00.000Z\t4099\ttrue\t53416732\n" +
                    "CMP1\t7\t8510\t0.8291012421250341\t2015-02-27T19:15:09.000Z\t2015-02-27T19:15:09.000Z\t2015-02-27T00:00:00.000Z\t6459\tfalse\t17817647\n" +
                    "CMP2\t6\t2368\t4.37540231039748\t2015-02-28T19:15:09.000Z\t2015-02-28T19:15:09.000Z\t2015-02-28T00:00:00.000Z\t7812\ttrue\t99185079\n" +
                    "CMP1\t6\t1758\t8.40889546554536\t2015-03-01T19:15:09.000Z\t2015-03-01T19:15:09.000Z\t2015-03-01T00:00:00.000Z\t7485\tfalse\t46226610\n" +
                    "CMP2\t4\t4049\t1.08890570467338\t2015-03-02T19:15:09.000Z\t2015-03-02T19:15:09.000Z\t2015-03-02T00:00:00.000Z\t4412\ttrue\t54936589\n" +
                    "CMP1\t7\t7543\t0.195319654885679\t2015-03-03T19:15:09.000Z\t2015-03-03T19:15:09.000Z\t2015-03-03T00:00:00.000Z\t6599\tfalse\t15161204\n" +
                    "CMP2\t3\t4967\t6.85113925952464\t2015-03-04T19:15:09.000Z\t2015-03-04T19:15:09.000Z\t2015-03-04T00:00:00.000Z\t3854\ttrue\t65617919\n" +
                    "CMP1\t8\t5195\t7.67904466483742\t2015-03-05T19:15:09.000Z\t2015-03-05T19:15:09.000Z\t2015-03-05T00:00:00.000Z\t8790\tfalse\t46057340\n" +
                    "CMP2\t6\t6111\t2.53866507206112\t2015-03-06T19:15:09.000Z\t2015-03-06T19:15:09.000Z\t2015-03-06T00:00:00.000Z\t6644\ttrue\t15179632\n" +
                    "CMP1\t5\t3105\t4.80623316485435\t2015-03-07T19:15:09.000Z\t2015-03-07T19:15:09.000Z\t2015-03-07T00:00:00.000Z\t5801\tfalse\t77929708\n" +
                    "CMP2\t7\t6621\t2.95066241407767\t2015-03-08T19:15:09.000Z\t2015-03-08T19:15:09.000Z\t2015-03-08T00:00:00.000Z\t975\ttrue\t83047755\n" +
                    "CMP1\t7\t7327\t1.22000687522814\t2015-03-09T19:15:09.000Z\t2015-03-09T19:15:09.000Z\t2015-03-09T00:00:00.000Z\t7221\tfalse\t8838331\n" +
                    "CMP2\t2\t3972\t8.57570362277329\t2015-03-10T19:15:09.000Z\t2015-03-10T19:15:09.000Z\t2015-03-10T00:00:00.000Z\t5746\ttrue\t26586255\n" +
                    "CMP1\t5\t2969\t4.82038192916662\t2015-03-11T19:15:09.000Z\t2015-03-11T19:15:09.000Z\t2015-03-11T00:00:00.000Z\t1217\tfalse\t65398530\n" +
                    "CMP2\t1\t1731\t6.87037272611633\t2015-03-12T19:15:09.000Z\t2015-03-12T19:15:09.000Z\t2015-03-12T00:00:00.000Z\t7299\ttrue\t61351111\n" +
                    "CMP1\t7\t6530\t9.17741159442812\t2015-03-13T19:15:09.000Z\t2015-03-13T19:15:09.000Z\t2015-03-13T00:00:00.000Z\t4186\tfalse\t68200832\n" +
                    "CMP2\t6\t441\t9.87805142300203\t2015-03-14T19:15:09.000Z\t2015-03-14T19:15:09.000Z\t2015-03-14T00:00:00.000Z\t6256\ttrue\t25615453\n" +
                    "CMP1\t8\t6476\t0.6236567208543421\t2015-03-15T19:15:09.000Z\t2015-03-15T19:15:09.000Z\t2015-03-15T00:00:00.000Z\t8916\tfalse\t11378657\n" +
                    "CMP2\t3\t9245\t4.85969736473635\t2015-03-16T19:15:09.000Z\t2015-03-16T19:15:09.000Z\t2015-03-16T00:00:00.000Z\t5364\ttrue\t72902099\n" +
                    "CMP1\t5\t135\t0.71932214545086\t2015-03-17T19:15:09.000Z\t2015-03-17T19:15:09.000Z\t2015-03-17T00:00:00.000Z\t6172\tfalse\t94911256\n" +
                    "CMP2\t6\t5662\t0.9344037040136751\t2015-03-18T19:15:09.000Z\t2015-03-18T19:15:09.000Z\t2015-03-18T00:00:00.000Z\t3228\ttrue\t71957668\n" +
                    "CMP1\t7\t8820\t2.26465462474152\t2015-03-19T19:15:09.000Z\t2015-03-19T19:15:09.000Z\t2015-03-19T00:00:00.000Z\t5414\tfalse\t37676934\n" +
                    "CMP2\t1\t1673\t1.13900111755356\t2015-03-20T19:15:09.000Z\t2015-03-20T19:15:09.000Z\t2015-03-20T00:00:00.000Z\t792\ttrue\t45159973\n" +
                    "CMP1\t6\t8704\t7.43929118616506\t2015-03-21T19:15:09.000Z\t2015-03-21T19:15:09.000Z\t2015-03-21T00:00:00.000Z\t4887\tfalse\t27305661\n" +
                    "CMP2\t4\t5380\t8.10803734697402\t2015-03-22T19:15:09.000Z\t2015-03-22T19:15:09.000Z\t2015-03-22T00:00:00.000Z\t8639\ttrue\t90187192\n" +
                    "CMP1\t8\t4176\t8.37395713664591\t2015-03-23T19:15:09.000Z\t2015-03-23T19:15:09.000Z\t2015-03-23T00:00:00.000Z\t7967\tfalse\t32268172\n" +
                    "CMP2\t1\t3419\t3.00495174946263\t2015-03-24T19:15:09.000Z\t2015-03-24T19:15:09.000Z\t2015-03-24T00:00:00.000Z\t7135\ttrue\t42567759\n" +
                    "CMP1\t7\t6785\t3.8469483377412\t2015-03-25T19:15:09.000Z\t2015-03-25T19:15:09.000Z\t2015-03-25T00:00:00.000Z\t9863\tfalse\t154099\n" +
                    "CMP2\t1\t7543\t3.16159424139187\t2015-03-26T19:15:09.000Z\t2015-03-26T19:15:09.000Z\t2015-03-26T00:00:00.000Z\t471\ttrue\t35226692\n" +
                    "CMP1\t2\t178\t1.37678213883191\t2015-03-27T19:15:09.000Z\t2015-03-27T19:15:09.000Z\t2015-03-27T00:00:00.000Z\t1374\tfalse\t80079972\n" +
                    "CMP2\t1\t7256\t6.15871280198917\t2015-03-28T19:15:09.000Z\t2015-03-28T19:15:09.000Z\t2015-03-28T00:00:00.000Z\t7280\ttrue\t86481439\n" +
                    "CMP1\t3\t2116\t7.31438394868746\t2015-03-29T19:15:09.000Z\t2015-03-29T19:15:09.000Z\t2015-03-29T00:00:00.000Z\t6402\tfalse\t60017381\n" +
                    "CMP2\t8\t1606\t8.10372669482604\t2015-03-30T19:15:09.000Z\t2015-03-30T19:15:09.000Z\t2015-03-30T00:00:00.000Z\t4188\ttrue\t74923808\n" +
                    "CMP1\t2\t2361\t2.69874187419191\t2015-03-31T19:15:09.000Z\t2015-03-31T19:15:09.000Z\t2015-03-31T00:00:00.000Z\t5815\tfalse\t16564471\n" +
                    "CMP2\t3\t7280\t8.83913917001337\t2015-04-01T19:15:09.000Z\t2015-04-01T19:15:09.000Z\t2015-04-01T00:00:00.000Z\t9220\ttrue\t7221046\n" +
                    "CMP1\t5\t8158\t1.9249943154864\t2015-04-02T19:15:09.000Z\t2015-04-02T19:15:09.000Z\t2015-04-02T00:00:00.000Z\t3342\tfalse\t28531977\n" +
                    "CMP2\t4\t3006\t8.50523490458727\t2015-04-03T19:15:09.000Z\t2015-04-03T19:15:09.000Z\t2015-04-03T00:00:00.000Z\t7198\ttrue\t17639973\n" +
                    "CMP1\t2\t8058\t3.24236876098439\t2015-04-04T19:15:09.000Z\t2015-04-04T19:15:09.000Z\t2015-04-04T00:00:00.000Z\t890\tfalse\t16188457\n" +
                    "CMP2\t8\t4913\t4.31931799743325\t2015-04-05T19:15:09.000Z\t2015-04-05T19:15:09.000Z\t2015-04-05T00:00:00.000Z\t2151\ttrue\t66148054\n" +
                    "CMP1\t6\t6114\t1.60783329280093\t2015-04-06T19:15:09.000Z\t2015-04-06T19:15:09.000Z\t2015-04-06T00:00:00.000Z\t7156\tfalse\t21576214\n" +
                    "CMP2\t1\t3799\t4.94223219808191\t2015-04-07T19:15:09.000Z\t2015-04-07T19:15:09.000Z\t2015-04-07T00:00:00.000Z\t9016\ttrue\t96119371\n" +
                    "CMP1\t8\t3672\t6.49665022967383\t2015-04-08T19:15:09.000Z\t2015-04-08T19:15:09.000Z\t2015-04-08T00:00:00.000Z\t3467\tfalse\t76381922\n" +
                    "CMP2\t6\t2315\t5.62425469048321\t2015-04-09T19:15:09.000Z\t2015-04-09T19:15:09.000Z\t2015-04-09T00:00:00.000Z\t7586\ttrue\t81396580\n" +
                    "CMP1\t8\t230\t6.72886302694678\t2015-04-10T19:15:09.000Z\t2015-04-10T19:15:09.000Z\t2015-04-10T00:00:00.000Z\t7928\tfalse\t18286886\n" +
                    "CMP2\t2\t2722\t2.23382522119209\t2015-04-11T19:15:09.000Z\t2015-04-11T19:15:09.000Z\t2015-04-11T00:00:00.000Z\t2584\ttrue\t75440358\n" +
                    "CMP1\t7\t3225\t3.55993304867297\t2015-04-12T19:15:09.000Z\t2015-04-12T19:15:09.000Z\t2015-04-12T00:00:00.000Z\t177\tfalse\t87523552\n" +
                    "CMP2\t6\t4692\t2.76645212434232\t2015-04-13T19:15:09.000Z\t2015-04-13T19:15:09.000Z\t2015-04-13T00:00:00.000Z\t4201\ttrue\t28465709\n" +
                    "CMP1\t7\t7116\t6.58135131234303\t2015-04-14T19:15:09.000Z\t2015-04-14T19:15:09.000Z\t2015-04-14T00:00:00.000Z\t3892\tfalse\t48420564\n" +
                    "CMP2\t3\t2457\t5.60338953277096\t2015-04-15T19:15:09.000Z\t2015-04-15T19:15:09.000Z\t2015-04-15T00:00:00.000Z\t7053\ttrue\t33039439\n" +
                    "CMP1\t8\t9975\t0.16938636312261202\t2015-04-16T19:15:09.000Z\t2015-04-16T19:15:09.000Z\t2015-04-16T00:00:00.000Z\t6874\tfalse\t6451182\n" +
                    "CMP2\t5\t4952\t0.968641364015639\t2015-04-17T19:15:09.000Z\t2015-04-17T19:15:09.000Z\t2015-04-17T00:00:00.000Z\t1680\ttrue\t77366482\n" +
                    "CMP1\t6\t2024\t1.11267756437883\t2015-04-18T19:15:09.000Z\t2015-04-18T19:15:09.000Z\t2015-04-18T00:00:00.000Z\t3883\tfalse\t65946538\n" +
                    "CMP2\t2\t7689\t6.29668754525483\t2015-04-19T19:15:09.000Z\t2015-04-19T19:15:09.000Z\t2015-04-19T00:00:00.000Z\t254\ttrue\t15272074\n" +
                    "CMP1\t1\t9916\t0.24603431345894902\t2015-04-20T19:15:09.000Z\t2015-04-20T19:15:09.000Z\t2015-04-20T00:00:00.000Z\t7768\tfalse\t24934386\n" +
                    "CMP2\t8\t2034\t7.2211763379164005\t2015-04-21T19:15:09.000Z\t2015-04-21T19:15:09.000Z\t2015-04-21T00:00:00.000Z\t8514\ttrue\t26112211\n" +
                    "CMP1\t8\t673\t4.48250063927844\t2015-04-22T19:15:09.000Z\t2015-04-22T19:15:09.000Z\t2015-04-22T00:00:00.000Z\t2455\tfalse\t51949360\n" +
                    "CMP2\t3\t6513\t4.39972517313436\t2015-04-23T19:15:09.000Z\t2015-04-23T19:15:09.000Z\t2015-04-23T00:00:00.000Z\t7307\ttrue\t74090772\n" +
                    "CMP1\t2\t8509\t7.21647302387282\t2015-04-24T19:15:09.000Z\t2015-04-24T19:15:09.000Z\t2015-04-24T00:00:00.000Z\t1784\tfalse\t43610015\n" +
                    "CMP2\t1\t9263\t9.72563182003796\t2015-04-25T19:15:09.000Z\t2015-04-25T19:15:09.000Z\t2015-04-25T00:00:00.000Z\t8811\ttrue\t27236992\n" +
                    "CMP1\t7\t9892\t1.50758364936337\t2015-04-26T19:15:09.000Z\t2015-04-26T19:15:09.000Z\t2015-04-26T00:00:00.000Z\t8011\tfalse\t16678001\n" +
                    "CMP2\t4\t4244\t3.88368266867474\t2015-04-27T19:15:09.000Z\t2015-04-27T19:15:09.000Z\t2015-04-27T00:00:00.000Z\t7431\ttrue\t19956646\n" +
                    "CMP1\t6\t9643\t3.09016502927989\t2015-04-28T19:15:09.000Z\t2015-04-28T19:15:09.000Z\t2015-04-28T00:00:00.000Z\t7144\tfalse\t40810637\n" +
                    "CMP2\t5\t3361\t5.21436133189127\t2015-04-29T19:15:09.000Z\t2015-04-29T19:15:09.000Z\t2015-04-29T00:00:00.000Z\t7217\ttrue\t35823849\n" +
                    "CMP1\t2\t5487\t3.5918223625049\t2015-04-30T19:15:09.000Z\t2015-04-30T19:15:09.000Z\t2015-04-30T00:00:00.000Z\t1421\tfalse\t60850489\n" +
                    "CMP2\t8\t4391\t2.72367869038135\t2015-05-01T19:15:09.000Z\t2015-05-01T19:15:09.000Z\t2015-05-01T00:00:00.000Z\t1296\ttrue\t80036797\n" +
                    "CMP1\t4\t2843\t5.22989432094619\t2015-05-02T19:15:09.000Z\t2015-05-02T19:15:09.000Z\t2015-05-02T00:00:00.000Z\t7773\tfalse\t88340142\n" +
                    "CMP2\tNaN\t2848\t5.32819046406075\t2015-05-03T19:15:09.000Z\t2015-05-03T19:15:09.000Z\t2015-05-03T00:00:00.000Z\t7628\ttrue\t36732064\n" +
                    "CMP1\tNaN\t2776\t5.30948682921007\t2015-05-04T19:15:09.000Z\t2015-05-04T19:15:09.000Z\t2015-05-04T00:00:00.000Z\t5917\tfalse\t59635623\n" +
                    "CMP2\t8\t5256\t8.02117716753855\t2015-05-05T19:15:09.000Z\t2015-05-05T19:15:09.000Z\t2015-05-05T00:00:00.000Z\t4088\ttrue\t50247928\n" +
                    "CMP1\t7\t9250\t0.8500805334188041\t2015-05-06T19:15:09.000Z\t2015-05-06T19:15:09.000Z\t2015-05-06T00:00:00.000Z\t519\tfalse\t61373305\n" +
                    "CMP2\t2\t6675\t7.95846320921555\t2015-05-07T19:15:09.000Z\t2015-05-07T19:15:09.000Z\t2015-05-07T00:00:00.000Z\t7530\ttrue\t49634855\n" +
                    "CMP1\t5\t8367\t9.34185237856582\t2015-05-08T19:15:09.000Z\t2015-05-08T19:15:09.000Z\t2015-05-08T00:00:00.000Z\t9714\tfalse\t91106929\n" +
                    "CMP2\t4\t370\t7.84945336403325\t2015-05-09T19:15:09.000Z\t2015-05-09T19:15:09.000Z\t2015-05-09T00:00:00.000Z\t8590\ttrue\t89638043\n" +
                    "CMP1\t7\t4055\t6.49124878691509\t2015-05-10T19:15:09.000Z\t2015-05-10T19:15:09.000Z\t2015-05-10T00:00:00.000Z\t3484\tfalse\t58849380\n" +
                    "CMP2\tNaN\t6132\t2.01015920145437\t2015-05-11T19:15:09.000Z\t2015-05-11T19:15:09.000Z\t2015-05-11T00:00:00.000Z\t8132\ttrue\t51493476\n" +
                    "CMP1\t6\t6607\t0.0829047034494579\t2015-05-12T19:15:09.000Z\t2015-05-12T19:15:09.000Z\t2015-05-12T00:00:00.000Z\t1685\tfalse\t88274174\n" +
                    "CMP2\t8\t1049\t9.39520388608798\t2015-05-13T19:15:09.000Z\t2015-05-13T19:15:09.000Z\t2015-05-13T00:00:00.000Z\t7164\ttrue\t49001539\n";

            assertQuery(
                    expected,
                    "x",
                    null,
                    true
            );
        });
    }

    @Test
    public void testCopyEmptyFileName() throws Exception {
        assertMemoryLeak(() -> assertFailure(
                "copy x from ''",
                null,
                12,
                "file name expected"
        ));
    }

    @Test
    public void testCopyFullHack() throws Exception {
        assertMemoryLeak(() -> assertFailure(
                "copy x from '../../../../../'",
                null,
                12,
                "'.' is not allowed"
        ));
    }

    @Test
    public void testCopyFullHack2() throws Exception {
        assertMemoryLeak(() -> assertFailure(
                "copy x from '\\..\\..\\'",
                null,
                13,
                "'.' is not allowed"
        ));
    }

    @After
    public void tearDownAfterTest() {
        engine.releaseAllReaders();
        engine.releaseAllWriters();
    }

    void assertFactoryCursor(String expected, String expectedTimestamp, RecordCursorFactory factory, boolean supportsRandomAccess) {
        assertTimestamp(expectedTimestamp, factory);
        assertCursor(expected, factory, supportsRandomAccess, true);
        // make sure we get the same outcome when we get factory to create new cursor
        assertCursor(expected, factory, supportsRandomAccess, true);
        // make sure strings, binary fields and symbols are compliant with expected record behaviour
        assertVariableColumns(factory, true);
    }

    protected void assertFailure(
            CharSequence query,
            @Nullable CharSequence ddl,
            int expectedPosition,
            @NotNull CharSequence expectedMessage
    ) throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try {
                if (ddl != null) {
                    compiler.compile(ddl, sqlExecutionContext);
                }
                try {
                    compiler.compile(query, sqlExecutionContext);
                    Assert.fail();
                } catch (SqlException e) {
                    Assert.assertEquals(expectedPosition, e.getPosition());
                    TestUtils.assertContains(e.getFlyweightMessage(), expectedMessage);
                }
                Assert.assertEquals(0, engine.getBusyReaderCount());
                Assert.assertEquals(0, engine.getBusyWriterCount());
            } finally {
                engine.releaseAllWriters();
                engine.releaseAllReaders();
            }
        });
    }

    protected void assertQuery(String expected, String query, String expectedTimestamp) throws SqlException {
        assertQuery(expected, query, expectedTimestamp, false);
    }

    protected void assertQuery(String expected, String query, String expectedTimestamp, boolean supportsRandomAccess) throws SqlException {
        try (final RecordCursorFactory factory = compiler.compile(query, sqlExecutionContext).getRecordCursorFactory()) {
            assertFactoryCursor(expected, expectedTimestamp, factory, supportsRandomAccess);
        }
    }
}
