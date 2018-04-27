/*
 * Copyright 2018 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.sweep.queue;

import static org.assertj.core.api.Assertions.assertThat;

import static com.palantir.atlasdb.sweep.queue.ShardAndStrategy.conservative;
import static com.palantir.atlasdb.sweep.queue.ShardAndStrategy.thorough;
import static com.palantir.atlasdb.sweep.queue.WriteInfoPartitioner.SHARDS;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class SweepableCellsReadWriteTest extends SweepQueueReadWriteTest {
    private SweepableCellsReader reader;

    int shard;
    int shard2;

    @Before
    public void setup() {
        super.setup();
        writer = new SweepableCellsWriter(kvs, partitioner);
        reader = new SweepableCellsReader(kvs);

        shard = writeToDefault(writer, TS, true);
        shard2 = writeToDefault(writer, TS2, false);
    }

    @Test
    public void canReadSingleEntryInSingleShard() {
        assertThat(reader.getLatestWrites(TS_REF, conservative(shard)))
                .containsExactly(WriteInfo.of(TABLE_REF, DEFAULT_CELL, false, TS));
        assertThat(reader.getLatestWrites(TS2_REF, thorough(shard2)))
                .containsExactly(WriteInfo.of(TABLE_REF2, DEFAULT_CELL, false, TS2));
    }

    @Test
    public void canReadSingleTombstoneInSameShard() {
        int tombstoneShard = putTombstone(writer, TS + 1, DEFAULT_CELL, true);
        assertThat(reader.getLatestWrites(TS_REF, conservative(tombstoneShard)))
                .containsExactly(WriteInfo.of(TABLE_REF, DEFAULT_CELL, true, TS + 1));
    }

    @Test
    public void getOnlyMostRecentTimestampForCellAndTableRef() {
        writeToDefault(writer, TS - 1, true);
        writeToDefault(writer, TS + 2, true);
        writeToDefault(writer, TS - 2, true);
        writeToDefault(writer, TS + 1, true);
        assertThat(reader.getLatestWrites(TS_REF, conservative(shard)))
                .containsExactly(WriteInfo.of(TABLE_REF, DEFAULT_CELL, false, TS + 2));
    }

    @Test
    public void canReadMultipleEntriesInSingleShardDifferentTransactions() {
        int fixedShard = writeToCell(writer, TS, getCellWithFixedHash(1), true);
        assertThat(writeToCell(writer, TS + 1, getCellWithFixedHash(2), true)).isEqualTo(fixedShard);
        assertThat(reader.getLatestWrites(TS_REF, conservative(fixedShard))).containsExactlyInAnyOrder(
                WriteInfo.of(TABLE_REF, getCellWithFixedHash(1), false, TS),
                WriteInfo.of(TABLE_REF, getCellWithFixedHash(2), false, TS + 1));
    }

    @Test
    public void canReadMultipleEntriesInSingleShardSameTransactionNotDedicated() {
        List<WriteInfo> writes = writeToUniqueCellsInSameShard(writer, TS, 10, true);
        ShardAndStrategy fixedShardAndStrategy = conservative(writes.get(0).toShard(SHARDS));
        assertThat(writes.size()).isEqualTo(10);
        assertThat(reader.getLatestWrites(TS_REF, fixedShardAndStrategy)).hasSameElementsAs(writes);
    }

    @Test
    public void canReadMultipleEntriesInSingleShardSameTransactionDedicated() {
        List<WriteInfo> writes = writeToUniqueCellsInSameShard(writer, TS, 257, true);
        ShardAndStrategy fixedShardAndStrategy = conservative(writes.get(0).toShard(SHARDS));
        assertThat(writes.size()).isEqualTo(257);
        assertThat(reader.getLatestWrites(TS_REF, fixedShardAndStrategy)).hasSameElementsAs(writes);
    }
}