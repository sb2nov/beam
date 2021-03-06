/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.kinesis;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Lists.newArrayList;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;

import org.apache.beam.sdk.io.UnboundedSource;
import org.apache.beam.sdk.transforms.Min;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.MovingFunction;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads data from multiple kinesis shards in a single thread.
 * It uses simple round robin algorithm when fetching data from shards.
 */
class KinesisReader extends UnboundedSource.UnboundedReader<KinesisRecord> {

  private static final Logger LOG = LoggerFactory.getLogger(KinesisReader.class);
  /**
   * Period of samples to determine watermark.
   */
  private static final Duration SAMPLE_PERIOD = Duration.standardMinutes(1);

  /**
   * Period of updates to determine watermark.
   */
  private static final Duration SAMPLE_UPDATE = Duration.standardSeconds(5);

  /**
   * Minimum number of unread messages required before considering updating watermark.
   */
  static final int MIN_WATERMARK_MESSAGES = 10;

  /**
   * Minimum number of SAMPLE_UPDATE periods over which unread messages should be spread
   * before considering updating watermark.
   */
  private static final int MIN_WATERMARK_SPREAD = 2;

  private final SimplifiedKinesisClient kinesis;
  private final UnboundedSource<KinesisRecord, ?> source;
  private final CheckpointGenerator initialCheckpointGenerator;
  private RoundRobin<ShardRecordsIterator> shardIterators;
  private CustomOptional<KinesisRecord> currentRecord = CustomOptional.absent();
  private MovingFunction minReadTimestampMsSinceEpoch;
  private Instant lastWatermark = BoundedWindow.TIMESTAMP_MIN_VALUE;

  public KinesisReader(SimplifiedKinesisClient kinesis,
      CheckpointGenerator initialCheckpointGenerator,
      UnboundedSource<KinesisRecord, ?> source) {
    this.kinesis = checkNotNull(kinesis, "kinesis");
    this.initialCheckpointGenerator =
        checkNotNull(initialCheckpointGenerator, "initialCheckpointGenerator");
    this.source = source;
    this.minReadTimestampMsSinceEpoch = new MovingFunction(SAMPLE_PERIOD.getMillis(),
        SAMPLE_UPDATE.getMillis(),
        MIN_WATERMARK_SPREAD,
        MIN_WATERMARK_MESSAGES,
        Min.ofLongs());
  }

  /**
   * Generates initial checkpoint and instantiates iterators for shards.
   */
  @Override
  public boolean start() throws IOException {
    LOG.info("Starting reader using {}", initialCheckpointGenerator);

    try {
      KinesisReaderCheckpoint initialCheckpoint =
          initialCheckpointGenerator.generate(kinesis);
      List<ShardRecordsIterator> iterators = newArrayList();
      for (ShardCheckpoint checkpoint : initialCheckpoint) {
        iterators.add(checkpoint.getShardRecordsIterator(kinesis));
      }
      shardIterators = new RoundRobin<>(iterators);
    } catch (TransientKinesisException e) {
      throw new IOException(e);
    }

    return advance();
  }

  /**
   * Moves to the next record in one of the shards.
   * If current shard iterator can be move forward (i.e. there's a record present) then we do it.
   * If not, we iterate over shards in a round-robin manner.
   */
  @Override
  public boolean advance() throws IOException {
    try {
      for (int i = 0; i < shardIterators.size(); ++i) {
        currentRecord = shardIterators.getCurrent().next();
        if (currentRecord.isPresent()) {
          Instant approximateArrivalTimestamp = currentRecord.get()
              .getApproximateArrivalTimestamp();
          minReadTimestampMsSinceEpoch.add(Instant.now().getMillis(),
              approximateArrivalTimestamp.getMillis());
          return true;
        } else {
          shardIterators.moveForward();
        }
      }
    } catch (TransientKinesisException e) {
      LOG.warn("Transient exception occurred", e);
    }
    return false;
  }

  @Override
  public byte[] getCurrentRecordId() throws NoSuchElementException {
    return currentRecord.get().getUniqueId();
  }

  @Override
  public KinesisRecord getCurrent() throws NoSuchElementException {
    return currentRecord.get();
  }

  /**
   * Returns the approximate time that the current record was inserted into the stream.
   * It is not guaranteed to be accurate - this could lead to mark some records as "late"
   * even if they were not. Beware of this when setting
   * {@link org.apache.beam.sdk.values.WindowingStrategy#withAllowedLateness}
   */
  @Override
  public Instant getCurrentTimestamp() throws NoSuchElementException {
    return currentRecord.get().getApproximateArrivalTimestamp();
  }

  @Override
  public void close() throws IOException {
  }

  @Override
  public Instant getWatermark() {
    Instant now = Instant.now();
    long readMin = minReadTimestampMsSinceEpoch.get(now.getMillis());
    if (readMin == Long.MAX_VALUE) {
      lastWatermark = now;
    } else if (minReadTimestampMsSinceEpoch.isSignificant()) {
      Instant minReadTime = new Instant(readMin);
      if (minReadTime.isAfter(lastWatermark)) {
        lastWatermark = minReadTime;
      }
    }
    return lastWatermark;
  }

  @Override
  public UnboundedSource.CheckpointMark getCheckpointMark() {
    return KinesisReaderCheckpoint.asCurrentStateOf(shardIterators);
  }

  @Override
  public UnboundedSource<KinesisRecord, ?> getCurrentSource() {
    return source;
  }

}
