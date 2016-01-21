/*******************************************************************************
 *  _  _ ___ ___     _ _
 * | \| | __/ __| __| | |__
 * | .` | _|\__ \/ _` | '_ \
 * |_|\_|_| |___/\__,_|_.__/
 *
 * Copyright (c) 2014-2015. The NFSdb project and its contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.nfsdb.logging;

import com.nfsdb.concurrent.RingQueue;
import com.nfsdb.concurrent.Sequence;
import com.nfsdb.io.sink.CharSink;
import com.nfsdb.misc.Misc;

public class AsyncLogger implements LogRecord {
    private final RingQueue<LogRecordSink> debugRing;
    private final Sequence debugSeq;
    private final RingQueue<LogRecordSink> infoRing;
    private final Sequence infoSeq;
    private final RingQueue<LogRecordSink> errorRing;
    private final Sequence errorSeq;
    private final ThreadLocalCursor tl = new ThreadLocalCursor();

    public AsyncLogger(
            RingQueue<LogRecordSink> debugRing,
            Sequence debugSeq,
            RingQueue<LogRecordSink> infoRing,
            Sequence infoSeq,
            RingQueue<LogRecordSink> errorRing,
            Sequence errorSeq
    ) {
        this.debugRing = debugRing;
        this.debugSeq = debugSeq;
        this.infoRing = infoRing;
        this.infoSeq = infoSeq;
        this.errorRing = errorRing;
        this.errorSeq = errorSeq;
    }

    @Override
    public void $() {
        _(Misc.EOL);
        Holder h = tl.get();
        h.seq.done(h.cursor);
    }

    @Override
    public LogRecord _(CharSequence sequence) {
        sink().put(sequence);
        return this;
    }

    @Override
    public LogRecord _(int x) {
        sink().put(x);
        return this;
    }

    @Override
    public LogRecord _(char c) {
        sink().put(c);
        return this;
    }

    @Override
    public LogRecord ts() {
        sink().putISODate(System.currentTimeMillis());
        return this;
    }

    public LogRecord debug() {
        return next(debugSeq, debugRing)._("DEBUG")._(' ');
    }

    public LogRecord error() {
        return next(errorSeq, errorRing)._("ERROR")._(' ');
    }

    public LogRecord info() {
        return next(infoSeq, infoRing)._("INFO")._(' ');
    }

    private LogRecord next(Sequence seq, RingQueue<LogRecordSink> ring) {
        long cursor = seq.next();
        if (cursor < 0) {
            return NullLogRecord.INSTANCE;
        }
        Holder h = tl.get();
        h.cursor = cursor;
        h.seq = seq;
        h.ring = ring;
        ring.get(cursor).clear(0);
        ts()._(' ');
        return this;
    }

    private CharSink sink() {
        Holder h = tl.get();
        return h.ring.get(h.cursor);
    }

    private static class Holder {
        private long cursor;
        private Sequence seq;
        private RingQueue<LogRecordSink> ring;
    }

    private static class ThreadLocalCursor extends ThreadLocal<Holder> {
        @Override
        protected Holder initialValue() {
            return new Holder();
        }
    }
}