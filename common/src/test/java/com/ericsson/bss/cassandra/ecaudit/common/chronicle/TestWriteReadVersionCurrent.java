/*
 * Copyright 2019 Telefonaktiebolaget LM Ericsson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ericsson.bss.cassandra.ecaudit.common.chronicle;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ericsson.bss.cassandra.ecaudit.common.record.AuditRecord;
import com.ericsson.bss.cassandra.ecaudit.common.record.SimpleAuditOperation;
import com.ericsson.bss.cassandra.ecaudit.common.record.SimpleAuditRecord;
import com.ericsson.bss.cassandra.ecaudit.common.record.Status;
import com.ericsson.bss.cassandra.ecaudit.common.record.StoredAuditRecord;

import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.WriteMarshallable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

public class TestWriteReadVersionCurrent
{
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private ChronicleQueue chronicleQueue;

    @Before
    public void before()
    {
        chronicleQueue = SingleChronicleQueueBuilder.single(temporaryFolder.getRoot()).blockSize(1024).build();
    }

    @After
    public void after()
    {
        chronicleQueue.close();
    }

    @Test
    public void writeReadSubject() throws  Exception
    {
        AuditRecord expectedAuditRecord = likeGenericRecord().withSubject("bob-the-subject").build();

        FieldSelector fieldsWithSubject = FieldSelector.DEFAULT_FIELDS.withField(FieldSelector.Field.SUBJECT);

        writeAuditRecordToChronicle(expectedAuditRecord, fieldsWithSubject);

        StoredAuditRecord actualAuditRecord = readAuditRecordFromChronicle();

        assertThatRecordsMatch(actualAuditRecord, expectedAuditRecord);
    }

    @Test
    public void writeReadBatch() throws Exception
    {
        AuditRecord expectedAuditRecord = likeGenericRecord().withBatchId(UUID.randomUUID()).build();

        writeAuditRecordToChronicle(expectedAuditRecord);

        StoredAuditRecord actualAuditRecord = readAuditRecordFromChronicle();

        assertThatRecordsMatch(actualAuditRecord, expectedAuditRecord);
    }

    @Test
    public void writeReadSingle() throws Exception
    {
        AuditRecord expectedAuditRecord = likeGenericRecord().withStatus(Status.FAILED).build();

        writeAuditRecordToChronicle(expectedAuditRecord);

        StoredAuditRecord actualAuditRecord = readAuditRecordFromChronicle();

        assertThatRecordsMatch(actualAuditRecord, expectedAuditRecord);
    }

    @Test
    public void tryReuseOnRead() throws Exception
    {
        AuditRecord expectedAuditRecord = likeGenericRecord().build();

        writeAuditRecordToChronicle(expectedAuditRecord);
        writeAuditRecordToChronicle(expectedAuditRecord);

        AuditRecordReadMarshallable readMarshallable = new AuditRecordReadMarshallable();

        ExcerptTailer tailer = chronicleQueue.createTailer();
        tailer.readDocument(readMarshallable);

        assertThatExceptionOfType(IORuntimeException.class)
        .isThrownBy(() -> tailer.readDocument(readMarshallable))
        .withMessage("Tried to read from wire with used marshallable");
    }

    private SimpleAuditRecord.Builder likeGenericRecord() throws UnknownHostException
    {
        return SimpleAuditRecord
        .builder()
        .withClientAddress(new InetSocketAddress(InetAddress.getByName("0.1.2.3"), 876))
        .withCoordinatorAddress(InetAddress.getByName("4.5.6.7"))
        .withStatus(Status.ATTEMPT)
        .withOperation(new SimpleAuditOperation("SELECT SOMETHING"))
        .withUser("bob")
        .withTimestamp(System.currentTimeMillis());
    }

    private void writeAuditRecordToChronicle(AuditRecord auditRecord)
    {
        writeAuditRecordToChronicle(auditRecord, FieldSelector.DEFAULT_FIELDS);
    }

    private void writeAuditRecordToChronicle(AuditRecord auditRecord, FieldSelector fields)
    {
        WriteMarshallable writeMarshallable = new AuditRecordWriteMarshallable(auditRecord, fields);

        ExcerptAppender appender = chronicleQueue.acquireAppender();
        appender.writeDocument(writeMarshallable);
    }

    private StoredAuditRecord readAuditRecordFromChronicle()
    {
        AuditRecordReadMarshallable readMarshallable = new AuditRecordReadMarshallable();

        ExcerptTailer tailer = chronicleQueue.createTailer();
        tailer.readDocument(readMarshallable);

        return readMarshallable.getAuditRecord();
    }

    private void assertThatRecordsMatch(StoredAuditRecord actualAuditRecord, AuditRecord expectedAuditRecord)
    {
        assertThat(actualAuditRecord.getBatchId()).isEqualTo(expectedAuditRecord.getBatchId());
        assertThat(actualAuditRecord.getClientAddress()).contains(expectedAuditRecord.getClientAddress().getAddress());
        assertThat(actualAuditRecord.getClientPort()).contains(expectedAuditRecord.getClientAddress().getPort());
        assertThat(actualAuditRecord.getCoordinatorAddress()).contains(expectedAuditRecord.getCoordinatorAddress());
        assertThat(actualAuditRecord.getStatus()).contains(expectedAuditRecord.getStatus());
        assertThat(actualAuditRecord.getOperation()).contains(expectedAuditRecord.getOperation().getOperationString());
        assertThat(actualAuditRecord.getNakedOperation()).isEmpty();
        assertThat(actualAuditRecord.getUser()).contains(expectedAuditRecord.getUser());
        assertThat(actualAuditRecord.getTimestamp()).contains(expectedAuditRecord.getTimestamp());
        assertThat(actualAuditRecord.getSubject()).isEqualTo(expectedAuditRecord.getSubject());
    }
}
